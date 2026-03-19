package com.ws.wsAgenticSecurityGateway.agentRegistry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayHumanUserEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayHumanUserRepository;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Discovery & Registry Service — auto-discovers AI agents when they
 * connect
 * and persists their identity, session history, and request counts.
 *
 * <p>
 * Mirrors the {@code CapabilityRegistryService} pattern:
 * <ul>
 * <li>In-memory {@link ConcurrentHashMap} caches for O(1) lookups</li>
 * <li>{@code @PostConstruct} to warm cache from database</li>
 * <li>{@code @Async} for non-blocking request counting on the hot path</li>
 * <li>Atomic SQL increments to avoid read-modify-write races</li>
 * </ul>
 */
@Service
@Slf4j
public class AgentRegistryService {

    private final GatewayAgentRepository agentRepository;
    private final GatewayAgentSessionRepository sessionRepository;
    private final GatewayHumanUserRepository humanUserRepository;
    private final McpAuditService auditService;

    /**
     * In-memory cache: "agentName:agentVersion" → entity (for fast upsert checks).
     */
    private final ConcurrentHashMap<String, GatewayAgentEntity> agentCache = new ConcurrentHashMap<>();

    /**
     * In-memory: sessionId → agentId (for O(1) request counting without DB lookup).
     */
    private final ConcurrentHashMap<String, UUID> sessionToAgentId = new ConcurrentHashMap<>();

    /**
     * In-memory: sessionId → humanUserId (for O(1) human request counting, null-safe).
     */
    private final ConcurrentHashMap<String, UUID> sessionToHumanUserId = new ConcurrentHashMap<>();

    public AgentRegistryService(GatewayAgentRepository agentRepository,
            GatewayAgentSessionRepository sessionRepository,
            GatewayHumanUserRepository humanUserRepository,
            McpAuditService auditService) {
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
        this.humanUserRepository = humanUserRepository;
        this.auditService = auditService;
    }

    // ════════════════════════════════════════════════════════════════════
    // STARTUP
    // ════════════════════════════════════════════════════════════════════

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void loadFromDatabase() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("🤖 AGENT REGISTRY — Loading from database");
        log.info("═══════════════════════════════════════════════════════════");

        // Mark any orphaned CONNECTED sessions as DISCONNECTED (handles ungraceful
        // shutdowns)
        sessionRepository.markAllDisconnected();
        log.info("   Cleaned up orphaned sessions from previous run");

        // Warm agent cache
        List<GatewayAgentEntity> agents = agentRepository.findAll();
        for (GatewayAgentEntity agent : agents) {
            String key = cacheKey(agent.getAgentName(), agent.getAgentVersion());
            agentCache.put(key, agent);
        }

        log.info("   Loaded {} agent profiles into cache", agents.size());
        for (GatewayAgentEntity agent : agents) {
            log.info("   - {} v{} ({}sessions, {}requests, {})",
                    agent.getAgentName(),
                    agent.getAgentVersion() != null ? agent.getAgentVersion() : "?",
                    agent.getTotalSessions(),
                    agent.getTotalRequests(),
                    agent.getStatus());
        }

        log.info("═══════════════════════════════════════════════════════════");
    }

    // ════════════════════════════════════════════════════════════════════
    // AGENT DISCOVERY — Called on MCP initialize
    // ════════════════════════════════════════════════════════════════════

    /**
     * Discover (or update) an agent profile. Called when an agent sends
     * the MCP {@code initialize} request.
     *
     * <p>
     * Upsert logic: if an agent with the same (name, version) exists,
     * update its protocol version, capabilities, and last_seen_at.
     * Otherwise, create a new agent record.
     *
     * @return the persistent agent entity (existing or newly created)
     */
    @Transactional
    public GatewayAgentEntity discoverAgent(String name, String version,
            String protocolVersion,
            JsonNode capabilities) {
        return discoverAgent(name, version, protocolVersion, capabilities, null, null);
    }

    /**
     * Discover (or update) an agent profile with OAuth2 identity context.
     *
     * @param authClientId OAuth2 client_id (azp claim) — verified identity from JWT, or null
     * @param tokenType    "AUTOMATED_AGENT" or "HUMAN_DELEGATED", or null
     */
    @Transactional
    public GatewayAgentEntity discoverAgent(String name, String version,
            String protocolVersion,
            JsonNode capabilities,
            String authClientId, String tokenType) {
        String key = cacheKey(name, version);

        // Check cache first
        GatewayAgentEntity cached = agentCache.get(key);
        if (cached != null) {
            // BLOCKED agents are rejected at connection time
            if ("BLOCKED".equals(cached.getApprovalStatus())) {
                log.warn("🚫 BLOCKED agent attempted connection: {} v{} (id={})",
                        name, version, cached.getId());
                throw new AgentBlockedException(
                        "Agent '" + name + "' v" + version
                                + " is blocked by admin. Contact your gateway administrator.");
            }
            // Re-approval policy: every new initialize cycle requires explicit approval.
            // Keep BLOCKED untouched (handled above), reset APPROVED back to PENDING.
            if ("APPROVED".equals(cached.getApprovalStatus())) {
                cached.setApprovalStatus("PENDING");
                log.info("🔄 Agent approval reset to PENDING on reconnect: {} v{} (id={})",
                        name, version, cached.getId());
            }
            // Update mutable fields
            cached.setProtocolVersion(protocolVersion);
            cached.setCapabilities(capabilities);
            cached.setStatus("ACTIVE");
            if (authClientId != null) cached.setAuthClientId(authClientId);
            if (tokenType != null) cached.setTokenType(tokenType);
            GatewayAgentEntity updated = agentRepository.saveAndFlush(cached);
            agentCache.put(key, updated);
            log.info("🤖 Agent re-discovered: {} v{} (id={}, approval={}, authClientId={})",
                    name, version, updated.getId(), updated.getApprovalStatus(), authClientId);
            return updated;
        }

        // Check database (cache miss — maybe another instance created it)
        Optional<GatewayAgentEntity> existing = agentRepository.findByAgentNameAndAgentVersion(name, version);

        if (existing.isPresent()) {
            GatewayAgentEntity entity = existing.get();
            // BLOCKED agents are rejected at connection time
            if ("BLOCKED".equals(entity.getApprovalStatus())) {
                agentCache.put(key, entity);
                log.warn("🚫 BLOCKED agent attempted connection: {} v{} (id={})",
                        name, version, entity.getId());
                throw new AgentBlockedException(
                        "Agent '" + name + "' v" + version
                                + " is blocked by admin. Contact your gateway administrator.");
            }
            // Re-approval policy: every new initialize cycle requires explicit approval.
            if ("APPROVED".equals(entity.getApprovalStatus())) {
                entity.setApprovalStatus("PENDING");
                log.info("🔄 Agent approval reset to PENDING on reconnect: {} v{} (id={})",
                        name, version, entity.getId());
            }
            entity.setProtocolVersion(protocolVersion);
            entity.setCapabilities(capabilities);
            entity.setStatus("ACTIVE");
            if (authClientId != null) entity.setAuthClientId(authClientId);
            if (tokenType != null) entity.setTokenType(tokenType);
            GatewayAgentEntity updated = agentRepository.saveAndFlush(entity);
            agentCache.put(key, updated);
            log.info("🤖 Agent re-discovered (from DB): {} v{} (id={}, approval={}, authClientId={})",
                    name, version, updated.getId(), updated.getApprovalStatus(), authClientId);
            return updated;
        }

        // New agent — first time ever seen → PENDING approval
        GatewayAgentEntity newAgent = GatewayAgentEntity.builder()
                .agentName(name)
                .agentVersion(version)
                .protocolVersion(protocolVersion)
                .capabilities(capabilities)
                .status("ACTIVE")
                .approvalStatus("PENDING")
                .authClientId(authClientId)
                .tokenType(tokenType)
                .totalSessions(0)
                .totalRequests(0L)
                .build();

        GatewayAgentEntity saved = agentRepository.saveAndFlush(newAgent);
        agentCache.put(key, saved);
        log.info("🤖 NEW agent discovered (PENDING approval): {} v{} (id={})",
                name, version, saved.getId());
        return saved;
    }

    // ════════════════════════════════════════════════════════════════════
    // SESSION MANAGEMENT
    // ════════════════════════════════════════════════════════════════════

    /**
     * Register a new agent session. Called immediately after agent discovery
     * during the MCP initialize handshake.
     */
    @Transactional
    public GatewayAgentSessionEntity registerSession(UUID agentId, String sessionId,
            String authMethod,
            String authIdentity) {
        return registerSession(agentId, sessionId, authMethod, authIdentity, null, null);
    }

    /**
     * Register a new agent session with OAuth2 context.
     *
     * @param tokenType   "AUTOMATED_AGENT" or "HUMAN_DELEGATED", or null
     * @param humanUserId FK to gateway_human_users (set when HUMAN_DELEGATED), or null
     */
    @Transactional
    public GatewayAgentSessionEntity registerSession(UUID agentId, String sessionId,
            String authMethod, String authIdentity,
            String tokenType, UUID humanUserId) {
        GatewayAgentEntity agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            log.warn("Cannot register session — agent not found: {}", agentId);
            return null;
        }

        GatewayAgentSessionEntity session = GatewayAgentSessionEntity.builder()
                .agent(agent)
                .sessionId(sessionId)
                .authMethod(authMethod)
                .authIdentity(authIdentity)
                .tokenType(tokenType)
                .humanUserId(humanUserId)
                .requestCount(0)
                .status("CONNECTED")
                .build();

        GatewayAgentSessionEntity saved = sessionRepository.saveAndFlush(session);

        // Atomic increment of agent's total session count
        agentRepository.incrementSessionCount(agentId);

        // Refresh cache to prevent discoverAgent() from overwriting the DB increment
        // (discoverAgent calls saveAndFlush on the cached entity — stale totalSessions)
        GatewayAgentEntity refreshed = agentRepository.findById(agentId).orElse(null);
        if (refreshed != null) {
            agentCache.put(cacheKey(refreshed.getAgentName(), refreshed.getAgentVersion()), refreshed);
        }

        // Map session → agent for fast request counting
        sessionToAgentId.put(sessionId, agentId);

        // Map session → human user for fast human request counting (HUMAN_DELEGATED only)
        if (humanUserId != null) {
            sessionToHumanUserId.put(sessionId, humanUserId);
        }

        log.info("📡 Agent session registered: {} → agent {} v{} (session={}, humanUser={})",
                sessionId, agent.getAgentName(), agent.getAgentVersion(), saved.getId(), humanUserId);
        return saved;
    }

    /**
     * Mark a session as disconnected. Called when the agent closes the connection.
     */
    @Transactional
    public void disconnectSession(String sessionId) {
        sessionRepository.markDisconnected(sessionId);
        sessionToAgentId.remove(sessionId);
        sessionToHumanUserId.remove(sessionId);
        log.info("📡 Agent session disconnected: {}", sessionId);
    }

    /**
     * Layer 1 — Active Session Replacement: disconnect all existing CONNECTED
     * sessions
     * for an agent except the newly created one.
     *
     * <p>
     * Called during HTTP {@code initialize} to clean up zombie sessions caused by:
     * <ul>
     * <li>Agent close/reopen (mcp-remote doesn't send DELETE)</li>
     * <li>Agent delete-config/reconfig before timeout</li>
     * <li>Network interruption followed by reconnect</li>
     * </ul>
     *
     * @param agentId          the agent's UUID
     * @param excludeSessionId the new session to keep (just registered)
     * @return count of stale sessions that were disconnected
     */
    @Transactional
    public int disconnectExistingSessionsForAgent(UUID agentId, String excludeSessionId) {
        List<GatewayAgentSessionEntity> staleSessions = sessionRepository.findActiveSessionsForAgentExcluding(agentId,
                excludeSessionId);
        for (GatewayAgentSessionEntity stale : staleSessions) {
            String staleSessionId = stale.getSessionId();
            String agentName = stale.getAgent().getAgentName();
            disconnectSession(staleSessionId);
            // Audit: session replaced on reconnect
            auditService.auditServerSessionDisconnectedSync(staleSessionId, agentName);
        }
        return staleSessions.size();
    }

    /**
     * Layer 2 — Smart Idle Timeout: lightweight activity timestamp update.
     *
     * <p>
     * Called at the END of each orchestration method (after the server-facing call
     * completes) to keep sessions alive during long-running tool calls.
     * The existing {@link #recordRequest(String)} updates lastRequestAt at request
     * START;
     * this method updates it at response COMPLETION — so the reaper won't kill
     * sessions with tools that take 8+ minutes.
     *
     * <p>
     * Runs async on {@code mcpAuditExecutor} — never blocks the hot path.
     *
     * @param sessionId the agent's MCP session ID
     */
    @Async("mcpAuditExecutor")
    @Transactional
    public void updateLastActivity(String sessionId) {
        try {
            sessionRepository.updateLastRequestAt(sessionId);
        } catch (Exception e) {
            log.debug("Failed to update activity for session {}: {}", sessionId, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // REQUEST COUNTING — Async, non-blocking
    // ════════════════════════════════════════════════════════════════════

    /**
     * Record a request from an agent session. Called from the orchestration
     * layer on every tool/prompt/resource call.
     *
     * <p>
     * Runs on {@code mcpAuditExecutor} to avoid blocking the hot path.
     * Uses atomic SQL increments to avoid read-modify-write races.
     */
    @Async("mcpAuditExecutor")
    @Transactional
    public void recordRequest(String sessionId) {
        try {
            sessionRepository.incrementRequestCount(sessionId);

            UUID agentId = sessionToAgentId.get(sessionId);
            if (agentId != null) {
                agentRepository.incrementRequestCount(agentId);
            }

            // Increment human user request count (HUMAN_DELEGATED sessions only)
            UUID humanUserId = sessionToHumanUserId.get(sessionId);
            if (humanUserId != null) {
                humanUserRepository.incrementRequestCount(humanUserId);
            }
        } catch (Exception e) {
            log.debug("Failed to record agent request for session {}: {}",
                    sessionId, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // APPROVAL CHECK — O(1) in-memory, called from orchestration hot path
    // ════════════════════════════════════════════════════════════════════

    /**
     * Check if the agent for a given session is blocked.
     *
     * <p>
     * O(1) — two ConcurrentHashMap lookups, no DB hit.
     * Called from the orchestration layer BEFORE any tool forwarding.
     *
     * @param sessionId the agent's MCP session ID
     * @return {@code true} if the agent is NOT APPROVED (i.e., PENDING, BLOCKED, or
     *         unknown), {@code false} only if APPROVED
     */
    public boolean isAgentBlocked(String sessionId) {
        if (sessionId == null)
            return false;
        UUID agentId = sessionToAgentId.get(sessionId);
        if (agentId == null)
            return false;

        // Find agent in cache by iterating values (small map, typically < 20 agents)
        for (GatewayAgentEntity agent : agentCache.values()) {
            if (agentId.equals(agent.getId())) {
                return !"APPROVED".equals(agent.getApprovalStatus());
            }
        }
        return false;
    }

    /**
     * Resolve session ID to agent UUID — O(1) ConcurrentHashMap lookup.
     * Used by {@code AgentCapabilityFilterService} for capability access checks.
     *
     * @param sessionId the agent's MCP session ID
     * @return the agent's UUID, or null if session is unknown
     */
    public UUID getAgentIdForSession(String sessionId) {
        if (sessionId == null) return null;
        return sessionToAgentId.get(sessionId);
    }

    /**
     * Retrieve the agent name associated with a session ID, even if disconnected.
     * Uses DB lookup for stale sessions not in memory.
     */
    @Transactional(readOnly = true)
    public String getAgentNameBySessionId(String sessionId) {
        if (sessionId == null)
            return "unknown";

        // 1. Try memory map (for fast lookup of active sessions)
        UUID agentId = sessionToAgentId.get(sessionId);
        if (agentId != null) {
            for (GatewayAgentEntity agent : agentCache.values()) {
                if (agentId.equals(agent.getId())) {
                    return agent.getAgentName();
                }
            }
        }

        // 2. Try DB (for stale/disconnected sessions)
        return sessionRepository.findBySessionId(sessionId)
                .map(s -> s.getAgent().getAgentName())
                .orElse("unknown");
    }

    /**
     * Identity-level block check used during HTTP initialize pre-gating.
     *
     * <p>
     * Checks the persisted/cached agent profile by (name, version) without
     * creating/updating sessions. Returns true only when the profile exists and
     * is explicitly BLOCKED.
     */
    public boolean isAgentBlocked(String agentName, String agentVersion) {
        String key = cacheKey(agentName, agentVersion);

        GatewayAgentEntity cached = agentCache.get(key);
        if (cached != null) {
            return "BLOCKED".equals(cached.getApprovalStatus());
        }

        Optional<GatewayAgentEntity> existing = agentRepository.findByAgentNameAndAgentVersion(agentName, agentVersion);
        if (existing.isPresent()) {
            GatewayAgentEntity entity = existing.get();
            agentCache.put(key, entity);
            return "BLOCKED".equals(entity.getApprovalStatus());
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════
    // READ METHODS — For REST API and Dashboard
    // ════════════════════════════════════════════════════════════════════

    /** Return all discovered agents (all statuses). */
    public List<GatewayAgentEntity> getAllAgents() {
        return agentRepository.findAll();
    }

    /** Return a specific agent by ID. */
    public Optional<GatewayAgentEntity> getAgent(UUID id) {
        return agentRepository.findById(id);
    }

    /** Return agents matching the given name (may have multiple versions). */
    public List<GatewayAgentEntity> findAgentsByName(String agentName) {
        return agentRepository.findByAgentName(agentName);
    }

    /** Return session history for a specific agent, newest first. */
    public List<GatewayAgentSessionEntity> getAgentSessions(UUID agentId) {
        return sessionRepository.findByAgentIdOrderByConnectedAtDesc(agentId);
    }

    /** Return all currently connected sessions across all agents. */
    public List<GatewayAgentSessionEntity> getConnectedSessions() {
        return sessionRepository.findByStatus("CONNECTED");
    }

    // ════════════════════════════════════════════════════════════════════
    // LIVE SESSION DETAILS — For real-time monitoring
    // ════════════════════════════════════════════════════════════════════

    /**
     * Return enriched details for all currently connected sessions.
     * Adds agent name/version from cache for O(1) enrichment.
     */
    public List<Map<String, Object>> getLiveSessionDetails() {
        List<GatewayAgentSessionEntity> connected = getConnectedSessions();
        List<Map<String, Object>> result = new ArrayList<>();

        for (GatewayAgentSessionEntity session : connected) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sessionId", session.getSessionId());
            map.put("agentId", session.getAgent().getId());
            map.put("agentName", session.getAgent().getAgentName());
            map.put("agentVersion", session.getAgent().getAgentVersion());
            map.put("approvalStatus", session.getAgent().getApprovalStatus());
            map.put("authMethod", session.getAuthMethod());
            map.put("authIdentity", session.getAuthIdentity());
            map.put("connectedAt", session.getConnectedAt());
            // Compute connected duration
            long connectedMs = Duration.between(
                    session.getConnectedAt(), LocalDateTime.now()).toMillis();
            map.put("connectedDurationMs", connectedMs);
            map.put("requestCount", session.getRequestCount());
            map.put("lastRequestAt", session.getLastRequestAt());
            // Compute idle duration (time since last request, or since connected if no
            // requests)
            LocalDateTime lastActivity = session.getLastRequestAt() != null
                    ? session.getLastRequestAt()
                    : session.getConnectedAt();
            long idleMs = Duration.between(lastActivity, LocalDateTime.now()).toMillis();
            map.put("idleDurationMs", Math.max(0, idleMs));
            map.put("status", session.getStatus());
            result.add(map);
        }

        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    // SESSION QUERY METHODS (for AgentController service-layer access)
    // ════════════════════════════════════════════════════════════════════

    /** Batch session counts grouped by agent — for agent list totals. */
    public Map<UUID, Long> countSessionsByAgent() {
        List<Object[]> raw = sessionRepository.countSessionsByAgent();
        Map<UUID, Long> result = new HashMap<>();
        for (Object[] row : raw) {
            result.put((UUID) row[0], (Long) row[1]);
        }
        return result;
    }

    /** Total sessions for a specific agent. */
    public long countSessionsForAgent(UUID agentId) {
        return sessionRepository.countByAgentId(agentId);
    }

    /** Find a session by its MCP session ID. */
    public Optional<GatewayAgentSessionEntity> findSessionBySessionId(String sessionId) {
        return sessionRepository.findBySessionId(sessionId);
    }

    // ════════════════════════════════════════════════════════════════════
    // APPROVAL WORKFLOW
    // ════════════════════════════════════════════════════════════════════

    /**
     * Approve an agent — sets approval_status to APPROVED.
     * Approved agents are allowed to connect and use all tools.
     */
    @Transactional
    public GatewayAgentEntity approveAgent(UUID agentId, String adminActor, String adminIp) {
        GatewayAgentEntity agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        String previousApprovalStatus = agent.getApprovalStatus();
        agent.setApprovalStatus("APPROVED");
        GatewayAgentEntity updated = agentRepository.saveAndFlush(agent);
        // Update cache
        String key = cacheKey(agent.getAgentName(), agent.getAgentVersion());
        agentCache.put(key, updated);
        auditService.auditAgentApproved(
                updated.getId(),
                updated.getAgentName(),
                updated.getAgentVersion(),
                previousApprovalStatus,
                adminActor,
                adminIp);
        log.info("✅ Agent APPROVED: {} v{} (id={})",
                agent.getAgentName(), agent.getAgentVersion(), agentId);
        return updated;
    }

    /**
     * Block an agent — sets approval_status to BLOCKED.
     * Blocked agents will be rejected on next connection attempt.
     */
    @Transactional
    public GatewayAgentEntity blockAgent(UUID agentId, String adminActor, String adminIp) {
        GatewayAgentEntity agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        String previousApprovalStatus = agent.getApprovalStatus();
        agent.setApprovalStatus("BLOCKED");
        GatewayAgentEntity updated = agentRepository.saveAndFlush(agent);
        // Update cache
        String key = cacheKey(agent.getAgentName(), agent.getAgentVersion());
        agentCache.put(key, updated);
        auditService.auditAgentBlocked(
                updated.getId(),
                updated.getAgentName(),
                updated.getAgentVersion(),
                previousApprovalStatus,
                adminActor,
                adminIp);
        log.info("🚫 Agent BLOCKED: {} v{} (id={})",
                agent.getAgentName(), agent.getAgentVersion(), agentId);
        return updated;
    }

    // ════════════════════════════════════════════════════════════════════
    // HUMAN USER MANAGEMENT
    // ════════════════════════════════════════════════════════════════════

    /**
     * Discover (or update) a human user. Called when a HUMAN_DELEGATED token is seen.
     * Upserts by IdP subject (JWT "sub" — stable UUID).
     *
     * @param idpSubject        JWT "sub" — stable identity from IdP
     * @param preferredUsername  JWT "preferred_username"
     * @param email             JWT "email"
     * @param fullName          JWT "name"
     * @param givenName         JWT "given_name"
     * @param familyName        JWT "family_name"
     * @param idpIssuer         JWT "iss"
     * @param emailVerified     JWT "email_verified"
     * @param realmRoles        realm_access.roles from JWT
     * @param clientRoles       resource_access.<client>.roles from JWT
     * @param customClaims      ws_gateway_* prefixed claims
     * @param rawJwtClaims      Full JWT claims snapshot
     * @return the human user entity
     */
    @Transactional
    public GatewayHumanUserEntity discoverHumanUser(
            String idpSubject, String preferredUsername, String email,
            String fullName, String givenName, String familyName,
            String idpIssuer, Boolean emailVerified,
            java.util.List<String> realmRoles, java.util.List<String> clientRoles,
            java.util.Map<String, Object> customClaims,
            java.util.Map<String, Object> rawJwtClaims) {

        Optional<GatewayHumanUserEntity> existing = humanUserRepository.findByIdpSubject(idpSubject);
        LocalDateTime now = LocalDateTime.now();

        if (existing.isPresent()) {
            GatewayHumanUserEntity user = existing.get();
            // Update mutable fields with latest JWT data
            user.setPreferredUsername(preferredUsername);
            user.setEmail(email);
            user.setFullName(fullName);
            user.setGivenName(givenName);
            user.setFamilyName(familyName);
            user.setIdpIssuer(idpIssuer);
            user.setEmailVerified(emailVerified);
            user.setRealmRoles(realmRoles);
            user.setClientRoles(clientRoles);
            user.setCustomClaims(customClaims);
            user.setLastSeenAt(now);
            user.setLastJwtClaims(rawJwtClaims);
            GatewayHumanUserEntity updated = humanUserRepository.saveAndFlush(user);
            log.info("👤 Human user updated: {} (sub={}, email={})",
                    preferredUsername, idpSubject, email);
            return updated;
        }

        // New human user
        GatewayHumanUserEntity newUser = GatewayHumanUserEntity.builder()
                .idpSubject(idpSubject)
                .preferredUsername(preferredUsername)
                .email(email)
                .fullName(fullName)
                .givenName(givenName)
                .familyName(familyName)
                .idpIssuer(idpIssuer)
                .emailVerified(emailVerified)
                .realmRoles(realmRoles)
                .clientRoles(clientRoles)
                .customClaims(customClaims)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .totalSessions(0)
                .totalRequests(0L)
                .status("ACTIVE")
                .lastJwtClaims(rawJwtClaims)
                .build();

        GatewayHumanUserEntity saved = humanUserRepository.saveAndFlush(newUser);
        log.info("👤 NEW human user discovered: {} (sub={}, email={})",
                preferredUsername, idpSubject, email);
        return saved;
    }

    /**
     * Increment session count for a human user.
     */
    @Transactional
    public void incrementHumanSessionCount(UUID humanUserId) {
        humanUserRepository.findById(humanUserId).ifPresent(user -> {
            user.setTotalSessions(user.getTotalSessions() + 1);
            humanUserRepository.saveAndFlush(user);
        });
    }

    /**
     * Get the human user UUID for a session, if any.
     * Returns null for automated agent sessions.
     */
    public UUID getHumanUserIdForSession(String sessionId) {
        if (sessionId == null) return null;
        return sessionToHumanUserId.get(sessionId);
    }

    /**
     * Check if the human user behind a session is blocked.
     * O(1) — ConcurrentHashMap lookup + DB read (small table, typically < 100 rows).
     *
     * @param sessionId the agent's MCP session ID
     * @return {@code true} if the human user is BLOCKED, {@code false} otherwise
     */
    public boolean isHumanBlocked(String sessionId) {
        if (sessionId == null) return false;
        UUID humanUserId = sessionToHumanUserId.get(sessionId);
        if (humanUserId == null) return false;  // automated agent or unknown — not a human
        return humanUserRepository.findById(humanUserId)
                .map(h -> "BLOCKED".equals(h.getStatus()))
                .orElse(false);
    }

    /**
     * Check if a human user is blocked by their IdP subject (JWT sub).
     * Used during initialize to reject connections from blocked humans.
     *
     * @param idpSubject the JWT "sub" claim
     * @return {@code true} if the human is BLOCKED
     */
    public boolean isHumanBlockedBySubject(String idpSubject) {
        if (idpSubject == null) return false;
        return humanUserRepository.findByIdpSubject(idpSubject)
                .map(h -> "BLOCKED".equals(h.getStatus()))
                .orElse(false);
    }

    // ════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ════════════════════════════════════════════════════════════════════

    private String cacheKey(String name, String version) {
        return (name != null ? name : "unknown") + ":" + (version != null ? version : "?");
    }

    /**
     * Thrown when a BLOCKED agent attempts to connect.
     * Caught in ClientSession.initialize() to reject the connection gracefully.
     */
    public static class AgentBlockedException extends RuntimeException {
        public AgentBlockedException(String message) {
            super(message);
        }
    }
}
