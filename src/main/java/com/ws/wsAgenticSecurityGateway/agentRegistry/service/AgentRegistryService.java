package com.ws.wsAgenticSecurityGateway.agentRegistry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayHumanUserEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayNhiEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayHumanUserRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayNhiRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.event.BlockedSessionEvent;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
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
    private final GatewayNhiRepository nhiRepository;
    private final McpAuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

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

    /**
     * In-memory: sessionId → nhiId (for O(1) NHI request counting, null-safe).
     */
    private final ConcurrentHashMap<String, UUID> sessionToNhiId = new ConcurrentHashMap<>();

    /**
     * In-memory: sessionId → authIdentity (JWT subject).
     * ALWAYS populated regardless of token type — used as fallback for human/NHI block checks
     * when sessionToHumanUserId/sessionToNhiId are not populated (e.g., token misclassification).
     */
    private final ConcurrentHashMap<String, String> sessionToAuthIdentity = new ConcurrentHashMap<>();

    // ── Status caches: zero-DB-query per-request enforcement ──────────
    // Keyed by stable identifiers (not sessionId), survive disconnect/reconnect.
    // Populated: (1) startup warm-up, (2) on discovery, (3) safety-net DB fallback.
    // Invalidated: afterCommit on every admin mutation (approve/block/unblock).

    /** jwtSubject → "PENDING"/"ACTIVE"/"BLOCKED" */
    private final ConcurrentHashMap<String, String> humanStatusBySubject = new ConcurrentHashMap<>();

    /** jwtSubject → "PENDING"/"ACTIVE"/"BLOCKED" */
    private final ConcurrentHashMap<String, String> nhiStatusBySubject = new ConcurrentHashMap<>();

    /** agentId → "PENDING"/"APPROVED"/"BLOCKED" */
    private final ConcurrentHashMap<UUID, String> agentStatusById = new ConcurrentHashMap<>();

    public AgentRegistryService(GatewayAgentRepository agentRepository,
            GatewayAgentSessionRepository sessionRepository,
            GatewayHumanUserRepository humanUserRepository,
            GatewayNhiRepository nhiRepository,
            McpAuditService auditService,
            ApplicationEventPublisher eventPublisher) {
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
        this.humanUserRepository = humanUserRepository;
        this.nhiRepository = nhiRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
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

        // Warm agent cache + agent status cache
        List<GatewayAgentEntity> agents = agentRepository.findAll();
        for (GatewayAgentEntity agent : agents) {
            String key = cacheKey(agent.getAgentName(), agent.getAgentVersion());
            agentCache.put(key, agent);
            agentStatusById.put(agent.getId(), agent.getApprovalStatus());
        }

        // Warm human status cache
        List<GatewayHumanUserEntity> humans = humanUserRepository.findAll();
        for (GatewayHumanUserEntity h : humans) {
            if (h.getIdpSubject() != null) {
                humanStatusBySubject.put(h.getIdpSubject(), h.getStatus());
            }
        }

        // Warm NHI status cache
        List<GatewayNhiEntity> nhis = nhiRepository.findAll();
        for (GatewayNhiEntity n : nhis) {
            if (n.getIdpSubject() != null) {
                nhiStatusBySubject.put(n.getIdpSubject(), n.getStatus());
            }
        }

        log.info("   Loaded {} agent profiles into cache", agents.size());
        log.info("   Warmed status caches: {} humans, {} NHIs, {} agents",
                humanStatusBySubject.size(), nhiStatusBySubject.size(), agentStatusById.size());
        for (GatewayAgentEntity agent : agents) {
            log.info("   - {} v{} ({}sessions, {}requests, approval={})",
                    agent.getAgentName(),
                    agent.getAgentVersion() != null ? agent.getAgentVersion() : "?",
                    agent.getTotalSessions(),
                    agent.getTotalRequests(),
                    agent.getApprovalStatus());
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
        return discoverAgent(name, version, protocolVersion, capabilities, null, null, null);
    }

    /**
     * Discover (or update) an agent profile with OAuth2 identity context.
     *
     * @param authClientId   OAuth2 client_id (azp claim) — verified identity from JWT, or null
     * @param tokenType      "AUTOMATED_AGENT" or "HUMAN_DELEGATED", or null
     * @param wsTenantName   tenant name (from MCP path, NOT TenantContext)
     */
    @Transactional
    public GatewayAgentEntity discoverAgent(String name, String version,
            String protocolVersion,
            JsonNode capabilities,
            String authClientId, String tokenType,
            String wsTenantName) {
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
            // Agent approval is stable: once APPROVED, stays APPROVED across reconnects.
            // Only BLOCKED persists (handled above). PENDING stays PENDING until admin approves.
            // Update mutable fields
            cached.setProtocolVersion(protocolVersion);
            cached.setCapabilities(capabilities);
            cached.setStatus("ACTIVE");
            if (authClientId != null) cached.setAuthClientId(authClientId);
            if (tokenType != null) cached.setTokenType(tokenType);
            GatewayAgentEntity updated = agentRepository.saveAndFlush(cached);
            agentCache.put(key, updated);
            agentStatusById.put(updated.getId(), updated.getApprovalStatus());
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
            // Agent approval is stable: once APPROVED, stays APPROVED across reconnects.
            entity.setProtocolVersion(protocolVersion);
            entity.setCapabilities(capabilities);
            entity.setStatus("ACTIVE");
            if (authClientId != null) entity.setAuthClientId(authClientId);
            if (tokenType != null) entity.setTokenType(tokenType);
            GatewayAgentEntity updated = agentRepository.saveAndFlush(entity);
            agentCache.put(key, updated);
            agentStatusById.put(updated.getId(), updated.getApprovalStatus());
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
                .wsTenantName(wsTenantName)
                .build();

        GatewayAgentEntity saved = agentRepository.saveAndFlush(newAgent);
        agentCache.put(key, saved);
        agentStatusById.put(saved.getId(), "PENDING");
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
        return registerSession(agentId, sessionId, authMethod, authIdentity, null, null, null);
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
        return registerSession(agentId, sessionId, authMethod, authIdentity, tokenType, humanUserId, null, null, null);
    }

    /**
     * Register a new agent session with full identity context (human or NHI) — without IP.
     */
    @Transactional
    public GatewayAgentSessionEntity registerSession(UUID agentId, String sessionId,
            String authMethod, String authIdentity,
            String tokenType, UUID humanUserId, UUID nhiId) {
        return registerSession(agentId, sessionId, authMethod, authIdentity, tokenType, humanUserId, nhiId, null, null);
    }

    /**
     * Register a new agent session with full identity context + client IP address.
     *
     * @param tokenType      "AUTOMATED_AGENT" or "HUMAN_DELEGATED", or null
     * @param humanUserId    FK to gateway_human_users (set when HUMAN_DELEGATED), or null
     * @param nhiId          FK to gateway_nhi_registry (set when AUTOMATED_AGENT), or null
     * @param ipAddress      Client IP address (X-Forwarded-For aware), or null
     * @param wsTenantName   tenant name (from MCP path), or null
     */
    @Transactional
    public GatewayAgentSessionEntity registerSession(UUID agentId, String sessionId,
            String authMethod, String authIdentity,
            String tokenType, UUID humanUserId, UUID nhiId, String ipAddress,
            String wsTenantName) {
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
                .nhiId(nhiId)
                .ipAddress(ipAddress)
                .requestCount(0)
                .status("CONNECTED")
                .wsTenantName(wsTenantName)
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

        // Map session → NHI for fast NHI request counting (AUTOMATED_AGENT only)
        if (saved.getNhiId() != null) {
            sessionToNhiId.put(sessionId, saved.getNhiId());
        }

        // Map session → authIdentity (JWT subject) — ALWAYS populated for fallback block checks
        if (authIdentity != null) {
            sessionToAuthIdentity.put(sessionId, authIdentity);
        }

        log.info("📡 Agent session registered: {} → agent {} v{} (session={}, humanUser={}, authIdentity={})",
                sessionId, agent.getAgentName(), agent.getAgentVersion(), saved.getId(), humanUserId, authIdentity);
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
        sessionToNhiId.remove(sessionId);
        sessionToAuthIdentity.remove(sessionId);
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
    /**
     * @deprecated Use {@link #disconnectExistingSessionsForIdentity} for identity-scoped disconnect.
     */
    @Transactional
    public int disconnectExistingSessionsForAgent(UUID agentId, String excludeSessionId) {
        List<GatewayAgentSessionEntity> staleSessions = sessionRepository.findActiveSessionsForAgentExcluding(agentId,
                excludeSessionId);
        for (GatewayAgentSessionEntity stale : staleSessions) {
            String staleSessionId = stale.getSessionId();
            String agentName = stale.getAgent().getAgentName();
            disconnectSession(staleSessionId);
            auditService.auditServerSessionDisconnectedSync(staleSessionId, agentName);
        }
        return staleSessions.size();
    }

    /**
     * Identity-scoped session disconnect: only disconnects sessions for the SAME identity
     * (human or NHI) + same agent. Other identities' sessions with the same agent are untouched.
     *
     * @param agentId          the agent's UUID
     * @param humanUserId      the human's UUID (null if NHI or unknown)
     * @param nhiId            the NHI's UUID (null if human or unknown)
     * @param authIdentity     JWT subject (fallback when humanUserId/nhiId are null)
     * @param excludeSessionId the new session to keep
     * @return count of stale sessions disconnected
     */
    @Transactional
    public int disconnectExistingSessionsForIdentity(UUID agentId, UUID humanUserId, UUID nhiId,
                                                      String authIdentity, String excludeSessionId) {
        List<GatewayAgentSessionEntity> staleSessions;
        if (humanUserId != null) {
            staleSessions = sessionRepository.findActiveSessionsForAgentAndHumanUser(
                    agentId, humanUserId, excludeSessionId);
        } else if (nhiId != null) {
            staleSessions = sessionRepository.findActiveSessionsForAgentAndNhi(
                    agentId, nhiId, excludeSessionId);
        } else if (authIdentity != null) {
            staleSessions = sessionRepository.findActiveSessionsForAgentAndAuthIdentity(
                    agentId, authIdentity, excludeSessionId);
        } else {
            // No identity context — fall back to agent-wide (shouldn't happen in practice)
            staleSessions = sessionRepository.findActiveSessionsForAgentExcluding(agentId, excludeSessionId);
        }

        for (GatewayAgentSessionEntity stale : staleSessions) {
            String staleSessionId = stale.getSessionId();
            disconnectSession(staleSessionId);
            auditService.auditServerSessionDisconnectedSync(staleSessionId,
                    stale.getAgent() != null ? stale.getAgent().getAgentName() : "unknown");
        }
        if (!staleSessions.isEmpty()) {
            log.info("🔄 Disconnected {} stale session(s) for identity (human={}, nhi={}, auth={}) + agent={}",
                    staleSessions.size(), humanUserId, nhiId, authIdentity, agentId);
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

            // Increment NHI request count (AUTOMATED_AGENT sessions only)
            UUID nhiId = sessionToNhiId.get(sessionId);
            if (nhiId != null) {
                nhiRepository.incrementRequestCount(nhiId);
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
     * Check if the agent behind a session is blocked — single JOIN query (session → agent → status).
     * Independent of in-memory maps (survives disconnectSession() cleanup).
     * Called by HttpMcpAuditFilter on every request as the authoritative agent block check.
     */
    @Transactional(readOnly = true)
    public boolean isAgentBlockedForSession(String sessionId) {
        if (sessionId == null) return false;
        return sessionRepository.findAgentApprovalStatusBySessionId(sessionId)
                .map("BLOCKED"::equals)
                .orElse(false);
    }

    // ════════════════════════════════════════════════════════════════════
    // CACHE-AWARE STATUS LOOKUPS — O(1) per-request, DB fallback on miss
    // ════════════════════════════════════════════════════════════════════

    /**
     * Get human user status from cache. Falls back to DB on cache miss.
     * Returns null if no human found for this subject (not a human identity).
     */
    public String getHumanStatus(String jwtSubject) {
        if (jwtSubject == null) return null;
        String cached = humanStatusBySubject.get(jwtSubject);
        if (cached != null) return cached;
        // Safety-net DB fallback (cold cache or missed population)
        return humanUserRepository.findByIdpSubject(jwtSubject)
                .map(h -> {
                    humanStatusBySubject.put(jwtSubject, h.getStatus());
                    return h.getStatus();
                })
                .orElse(null);
    }

    /**
     * Get NHI status from cache. Falls back to DB on cache miss.
     * Returns null if no NHI found for this subject (not an NHI identity).
     */
    public String getNhiStatus(String jwtSubject) {
        if (jwtSubject == null) return null;
        String cached = nhiStatusBySubject.get(jwtSubject);
        if (cached != null) return cached;
        // Safety-net DB fallback
        return nhiRepository.findByIdpSubject(jwtSubject)
                .map(n -> {
                    nhiStatusBySubject.put(jwtSubject, n.getStatus());
                    return n.getStatus();
                })
                .orElse(null);
    }

    /**
     * Get agent approval status for a session. O(1) from cache, DB JOIN fallback on miss.
     * Returns null if session/agent not found.
     */
    public String getAgentStatusForSession(String sessionId) {
        if (sessionId == null) return null;
        // Primary: in-memory sessionToAgentId → agentStatusById (two O(1) lookups)
        UUID agentId = sessionToAgentId.get(sessionId);
        if (agentId != null) {
            String status = agentStatusById.get(agentId);
            if (status != null) return status;
        }
        // Fallback: DB JOIN query (edge case: race during disconnect, or cold cache)
        return sessionRepository.findAgentApprovalStatusBySessionId(sessionId)
                .orElse(null);
    }

    /**
     * Get the agent name for a session (for error messages).
     */
    public String getAgentNameForSession(String sessionId) {
        if (sessionId == null) return "unknown";
        UUID agentId = sessionToAgentId.get(sessionId);
        if (agentId != null) {
            for (GatewayAgentEntity agent : agentCache.values()) {
                if (agentId.equals(agent.getId())) {
                    return agent.getAgentName();
                }
            }
        }
        return "unknown";
    }

    // ── Cache update methods (called by HumanUserService / NhiService after admin mutations) ──

    /** Update human status cache after admin approve/block/unblock. */
    public void updateHumanStatusCache(String idpSubject, String newStatus) {
        if (idpSubject != null) {
            humanStatusBySubject.put(idpSubject, newStatus);
        }
    }

    /** Update NHI status cache after admin approve/block/unblock. */
    public void updateNhiStatusCache(String idpSubject, String newStatus) {
        if (idpSubject != null) {
            nhiStatusBySubject.put(idpSubject, newStatus);
        }
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

    /** Return all discovered agents (all statuses) for the current tenant. */
    public List<GatewayAgentEntity> getAllAgents() {
        return agentRepository.findAllByWsTenantName(TenantContext.get());
    }

    /** Return a specific agent by ID (tenant-verified). */
    public Optional<GatewayAgentEntity> getAgent(UUID id) {
        return agentRepository.findById(id)
                .filter(entity -> entity.getWsTenantName() != null
                        && entity.getWsTenantName().equals(TenantContext.get()));
    }

    /** Return agents matching the given name (may have multiple versions). */
    public List<GatewayAgentEntity> findAgentsByName(String agentName) {
        return agentRepository.findByAgentNameAndWsTenantName(agentName, TenantContext.get());
    }

    /** Return session history for a specific agent, newest first. */
    public List<GatewayAgentSessionEntity> getAgentSessions(UUID agentId) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return sessionRepository.findByAgentIdAndWsTenantNameOrderByConnectedAtDesc(agentId, tenant);
        }
        return sessionRepository.findByAgentIdOrderByConnectedAtDesc(agentId);
    }

    /** Return all currently connected sessions across all agents for the current tenant. */
    public List<GatewayAgentSessionEntity> getConnectedSessions() {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return sessionRepository.findByStatusAndWsTenantName("CONNECTED", tenant);
        }
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
        String tenant = TenantContext.get();
        List<Object[]> raw = tenant != null
                ? sessionRepository.countSessionsByAgentByTenant(tenant)
                : sessionRepository.countSessionsByAgent();
        Map<UUID, Long> result = new HashMap<>();
        for (Object[] row : raw) {
            result.put((UUID) row[0], (Long) row[1]);
        }
        return result;
    }

    /** Total sessions for a specific agent. */
    public long countSessionsForAgent(UUID agentId) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return sessionRepository.countByAgentIdAndWsTenantName(agentId, tenant);
        }
        return sessionRepository.countByAgentId(agentId);
    }

    /** Find a session by its MCP session ID (tenant-verified). */
    public Optional<GatewayAgentSessionEntity> findSessionBySessionId(String sessionId) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return sessionRepository.findBySessionIdAndWsTenantName(sessionId, tenant);
        }
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
        // Update caches
        String key = cacheKey(agent.getAgentName(), agent.getAgentVersion());
        agentCache.put(key, updated);
        agentStatusById.put(agentId, "APPROVED");
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
     * Block an agent — sets approval_status to BLOCKED, terminates all active sessions,
     * publishes {@link BlockedSessionEvent} for each so {@code HttpMcpAuditFilter}
     * immediately rejects further requests on those sessions.
     *
     * @return a result holder with the entity and the count of terminated sessions
     */
    @Transactional
    public AgentBlockResult blockAgent(UUID agentId, String adminActor, String adminIp) {
        GatewayAgentEntity agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        String previousApprovalStatus = agent.getApprovalStatus();
        agent.setApprovalStatus("BLOCKED");
        GatewayAgentEntity updated = agentRepository.saveAndFlush(agent);
        // Update caches
        String key = cacheKey(agent.getAgentName(), agent.getAgentVersion());
        agentCache.put(key, updated);
        agentStatusById.put(agentId, "BLOCKED");

        // ── Proactive session termination ──
        List<GatewayAgentSessionEntity> activeSessions =
                sessionRepository.findConnectedByAgentId(agentId);

        Set<UUID> affectedHumanIds = new LinkedHashSet<>();
        Set<UUID> affectedNhiIds = new LinkedHashSet<>();

        for (GatewayAgentSessionEntity session : activeSessions) {
            String sessionId = session.getSessionId();
            if (session.getHumanUserId() != null) affectedHumanIds.add(session.getHumanUserId());
            if (session.getNhiId() != null) affectedNhiIds.add(session.getNhiId());
            disconnectSession(sessionId);
            eventPublisher.publishEvent(new BlockedSessionEvent(sessionId, "AGENT", agent.getAgentName()));
            auditService.auditBlockedSessionTerminated(sessionId, agent.getAgentName(),
                    "AGENT", agent.getAgentName(),
                    "Admin blocked agent");
        }

        // Resolve affected identity names for admin response
        List<Map<String, Object>> affectedHumans = affectedHumanIds.stream()
                .map(hid -> humanUserRepository.findById(hid).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(h -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", h.getId());
                    m.put("preferredUsername", h.getPreferredUsername());
                    m.put("email", h.getEmail());
                    return m;
                }).toList();

        List<Map<String, Object>> affectedNhis = affectedNhiIds.stream()
                .map(nid -> nhiRepository.findById(nid).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(n -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", n.getId());
                    m.put("serviceName", n.getServiceName());
                    m.put("clientId", n.getClientId());
                    return m;
                }).toList();

        // ── Audit: admin action ──
        auditService.auditAgentBlocked(
                updated.getId(),
                updated.getAgentName(),
                updated.getAgentVersion(),
                previousApprovalStatus,
                adminActor,
                adminIp,
                activeSessions.size(),
                affectedHumans,
                affectedNhis);
        log.info("🚫 Agent BLOCKED: {} v{} (id={}, sessions terminated: {}, humans affected: {}, NHIs affected: {})",
                agent.getAgentName(), agent.getAgentVersion(), agentId,
                activeSessions.size(), affectedHumans.size(), affectedNhis.size());
        return new AgentBlockResult(updated, activeSessions.size(), affectedHumans, affectedNhis);
    }

    /** Result holder for agent block operations — includes impacted identities. */
    public record AgentBlockResult(
            GatewayAgentEntity agent,
            int sessionsTerminated,
            List<Map<String, Object>> affectedHumanUsers,
            List<Map<String, Object>> affectedNhis
    ) {}

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
            java.util.Map<String, Object> rawJwtClaims,
            String ipAddress, String wsTenantName) {

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
            user.setLastIpAddress(ipAddress);
            GatewayHumanUserEntity updated = humanUserRepository.saveAndFlush(user);
            log.info("👤 Human user updated: {} (sub={}, email={}, ip={})",
                    preferredUsername, idpSubject, email, ipAddress);
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
                .status("PENDING")
                .lastJwtClaims(rawJwtClaims)
                .lastIpAddress(ipAddress)
                .wsTenantName(wsTenantName)
                .build();

        GatewayHumanUserEntity saved = humanUserRepository.saveAndFlush(newUser);
        humanStatusBySubject.put(idpSubject, "PENDING");
        log.info("👤 NEW human user discovered (PENDING approval): {} (sub={}, email={}, ip={})",
                preferredUsername, idpSubject, email, ipAddress);
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
     * Returns the blocked reason for a human user behind this session, or empty if not blocked.
     * Two-path lookup: (1) direct humanUserId map, (2) fallback via authIdentity/JWT subject.
     */
    public Optional<String> getHumanBlockReason(String sessionId) {
        if (sessionId == null) return Optional.empty();

        // Path 1: Direct lookup via sessionToHumanUserId (HUMAN_DELEGATED tokens)
        UUID humanUserId = sessionToHumanUserId.get(sessionId);
        if (humanUserId != null) {
            return humanUserRepository.findById(humanUserId)
                    .filter(h -> "BLOCKED".equals(h.getStatus()))
                    .map(h -> h.getBlockedReason() != null ? h.getBlockedReason() : "Blocked by admin");
        }

        // Path 2: Fallback via authIdentity (JWT subject) — catches token misclassification
        String authIdentity = sessionToAuthIdentity.get(sessionId);
        if (authIdentity != null) {
            Optional<String> reason = humanUserRepository.findByIdpSubject(authIdentity)
                    .filter(h -> "BLOCKED".equals(h.getStatus()))
                    .map(h -> h.getBlockedReason() != null ? h.getBlockedReason() : "Blocked by admin");
            if (reason.isPresent()) {
                log.warn("🔍 Human block reason found via authIdentity fallback: session={}, sub={}", sessionId, authIdentity);
            }
            return reason;
        }

        return Optional.empty();
    }

    /** Returns the human user's preferred username for this session (for error messages). */
    public String getHumanUsername(String sessionId) {
        if (sessionId == null) return null;

        // Path 1: Direct lookup
        UUID humanUserId = sessionToHumanUserId.get(sessionId);
        if (humanUserId != null) {
            return humanUserRepository.findById(humanUserId)
                    .map(GatewayHumanUserEntity::getPreferredUsername)
                    .orElse(null);
        }

        // Path 2: Fallback via authIdentity
        String authIdentity = sessionToAuthIdentity.get(sessionId);
        if (authIdentity != null) {
            return humanUserRepository.findByIdpSubject(authIdentity)
                    .map(GatewayHumanUserEntity::getPreferredUsername)
                    .orElse(null);
        }

        return null;
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

    /** Returns blocked reason for a human by JWT subject, or "Blocked by admin" if no reason stored. */
    public String getHumanBlockReasonBySubject(String idpSubject) {
        if (idpSubject == null) return "Blocked by admin";
        return humanUserRepository.findByIdpSubject(idpSubject)
                .filter(h -> "BLOCKED".equals(h.getStatus()))
                .map(h -> h.getBlockedReason() != null ? h.getBlockedReason() : "Blocked by admin")
                .orElse("Blocked by admin");
    }

    /** Returns blocked reason for an NHI by JWT subject, or "Blocked by admin" if no reason stored. */
    public String getNhiBlockReasonBySubject(String idpSubject) {
        if (idpSubject == null) return "Blocked by admin";
        return nhiRepository.findByIdpSubject(idpSubject)
                .filter(n -> "BLOCKED".equals(n.getStatus()))
                .map(n -> n.getBlockedReason() != null ? n.getBlockedReason() : "Blocked by admin")
                .orElse("Blocked by admin");
    }

    // ════════════════════════════════════════════════════════════════════
    // NHI (NON-HUMAN IDENTITY) MANAGEMENT
    // ════════════════════════════════════════════════════════════════════

    /**
     * Discover (or update) a Non-Human Identity. Called when an AUTOMATED_AGENT token is seen.
     * Upserts by IdP subject (JWT "sub" — stable service account UUID).
     *
     * @param idpSubject   JWT "sub" — stable identity from IdP
     * @param clientId     JWT "azp" — OAuth2 client_id
     * @param idpIssuer    JWT "iss"
     * @param realmRoles   realm_access.roles from JWT
     * @param clientRoles  resource_access.<client>.roles from JWT
     * @param customClaims ws_gateway_* prefixed claims
     * @param rawJwtClaims Full JWT claims snapshot
     * @return the NHI entity
     */
    @Transactional
    public GatewayNhiEntity discoverNhi(
            String idpSubject, String clientId, String idpIssuer,
            java.util.List<String> realmRoles, java.util.List<String> clientRoles,
            java.util.Map<String, Object> customClaims,
            java.util.Map<String, Object> rawJwtClaims,
            String ipAddress, String wsTenantName) {

        Optional<GatewayNhiEntity> existing = nhiRepository.findByIdpSubject(idpSubject);
        LocalDateTime now = LocalDateTime.now();

        if (existing.isPresent()) {
            GatewayNhiEntity nhi = existing.get();
            // Update mutable fields with latest JWT data
            if (clientId != null) nhi.setClientId(clientId);
            nhi.setServiceName(clientId != null ? clientId : idpSubject);
            nhi.setIdpIssuer(idpIssuer);
            nhi.setRealmRoles(realmRoles);
            nhi.setClientRoles(clientRoles);
            nhi.setCustomClaims(customClaims);
            nhi.setLastSeenAt(now);
            nhi.setLastJwtClaims(rawJwtClaims);
            nhi.setLastIpAddress(ipAddress);
            GatewayNhiEntity updated = nhiRepository.saveAndFlush(nhi);
            log.info("NHI updated: {} (sub={}, clientId={}, ip={})",
                    updated.getServiceName(), idpSubject, clientId, ipAddress);
            return updated;
        }

        // New NHI — first time ever seen
        GatewayNhiEntity newNhi = GatewayNhiEntity.builder()
                .idpSubject(idpSubject)
                .serviceName(clientId != null ? clientId : idpSubject)
                .clientId(clientId)
                .idpIssuer(idpIssuer)
                .realmRoles(realmRoles)
                .clientRoles(clientRoles)
                .customClaims(customClaims)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .totalSessions(0)
                .totalRequests(0L)
                .status("PENDING")
                .lastJwtClaims(rawJwtClaims)
                .lastIpAddress(ipAddress)
                .wsTenantName(wsTenantName)
                .build();

        GatewayNhiEntity saved = nhiRepository.saveAndFlush(newNhi);
        nhiStatusBySubject.put(idpSubject, "PENDING");
        log.info("🔧 NEW NHI discovered (PENDING approval): {} (sub={}, clientId={}, ip={})",
                saved.getServiceName(), idpSubject, clientId, ipAddress);
        return saved;
    }

    /**
     * Increment session count for an NHI.
     */
    @Transactional
    public void incrementNhiSessionCount(UUID nhiId) {
        nhiRepository.findById(nhiId).ifPresent(nhi -> {
            nhi.setTotalSessions(nhi.getTotalSessions() + 1);
            nhiRepository.saveAndFlush(nhi);
        });
    }

    /**
     * Get the NHI UUID for a session, if any.
     * Returns null for human-delegated sessions.
     */
    public UUID getNhiIdForSession(String sessionId) {
        if (sessionId == null) return null;
        return sessionToNhiId.get(sessionId);
    }

    /**
     * Returns the blocked reason for an NHI behind this session, or empty if not blocked.
     * Two-path lookup: (1) direct nhiId map, (2) fallback via authIdentity/JWT subject.
     */
    public Optional<String> getNhiBlockReason(String sessionId) {
        if (sessionId == null) return Optional.empty();

        // Path 1: Direct lookup via sessionToNhiId
        UUID nhiId = sessionToNhiId.get(sessionId);
        if (nhiId != null) {
            return nhiRepository.findById(nhiId)
                    .filter(n -> "BLOCKED".equals(n.getStatus()))
                    .map(n -> n.getBlockedReason() != null ? n.getBlockedReason() : "Blocked by admin");
        }

        // Path 2: Fallback via authIdentity (JWT subject)
        String authIdentity = sessionToAuthIdentity.get(sessionId);
        if (authIdentity != null) {
            Optional<String> reason = nhiRepository.findByIdpSubject(authIdentity)
                    .filter(n -> "BLOCKED".equals(n.getStatus()))
                    .map(n -> n.getBlockedReason() != null ? n.getBlockedReason() : "Blocked by admin");
            if (reason.isPresent()) {
                log.warn("🔍 NHI block reason found via authIdentity fallback: session={}, sub={}", sessionId, authIdentity);
            }
            return reason;
        }

        return Optional.empty();
    }

    /** Returns the NHI service name for this session (for error messages). */
    public String getNhiServiceName(String sessionId) {
        if (sessionId == null) return null;

        // Path 1: Direct lookup
        UUID nhiId = sessionToNhiId.get(sessionId);
        if (nhiId != null) {
            return nhiRepository.findById(nhiId)
                    .map(GatewayNhiEntity::getServiceName)
                    .orElse(null);
        }

        // Path 2: Fallback via authIdentity
        String authIdentity = sessionToAuthIdentity.get(sessionId);
        if (authIdentity != null) {
            return nhiRepository.findByIdpSubject(authIdentity)
                    .map(GatewayNhiEntity::getServiceName)
                    .orElse(null);
        }

        return null;
    }

    /**
     * Check if an NHI is blocked by their IdP subject (JWT sub).
     * Used during initialize to reject connections from blocked NHIs.
     *
     * @param idpSubject the JWT "sub" claim
     * @return {@code true} if the NHI is BLOCKED
     */
    public boolean isNhiBlockedBySubject(String idpSubject) {
        if (idpSubject == null) return false;
        return nhiRepository.findByIdpSubject(idpSubject)
                .map(n -> "BLOCKED".equals(n.getStatus()))
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
