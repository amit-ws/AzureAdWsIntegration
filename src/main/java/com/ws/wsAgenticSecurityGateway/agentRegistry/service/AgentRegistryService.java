package com.ws.wsAgenticSecurityGateway.agentRegistry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
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
 * Agent Discovery & Registry Service — auto-discovers AI agents when they connect
 * and persists their identity, session history, and request counts.
 *
 * <p>Mirrors the {@code CapabilityRegistryService} pattern:
 * <ul>
 *   <li>In-memory {@link ConcurrentHashMap} caches for O(1) lookups</li>
 *   <li>{@code @PostConstruct} to warm cache from database</li>
 *   <li>{@code @Async} for non-blocking request counting on the hot path</li>
 *   <li>Atomic SQL increments to avoid read-modify-write races</li>
 * </ul>
 */
@Service
@Slf4j
public class AgentRegistryService {

    private final GatewayAgentRepository agentRepository;
    private final GatewayAgentSessionRepository sessionRepository;
    private final McpAuditService auditService;

    /** In-memory cache: "agentName:agentVersion" → entity (for fast upsert checks). */
    private final ConcurrentHashMap<String, GatewayAgentEntity> agentCache = new ConcurrentHashMap<>();

    /** In-memory: sessionId → agentId (for O(1) request counting without DB lookup). */
    private final ConcurrentHashMap<String, UUID> sessionToAgentId = new ConcurrentHashMap<>();

    public AgentRegistryService(GatewayAgentRepository agentRepository,
                                GatewayAgentSessionRepository sessionRepository,
                                McpAuditService auditService) {
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
        this.auditService = auditService;
    }

    // ════════════════════════════════════════════════════════════════════
    //  STARTUP
    // ════════════════════════════════════════════════════════════════════

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void loadFromDatabase() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("🤖 AGENT REGISTRY — Loading from database");
        log.info("═══════════════════════════════════════════════════════════");

        // Mark any orphaned CONNECTED sessions as DISCONNECTED (handles ungraceful shutdowns)
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
    //  AGENT DISCOVERY — Called on MCP initialize
    // ════════════════════════════════════════════════════════════════════

    /**
     * Discover (or update) an agent profile. Called when an agent sends
     * the MCP {@code initialize} request.
     *
     * <p>Upsert logic: if an agent with the same (name, version) exists,
     * update its protocol version, capabilities, and last_seen_at.
     * Otherwise, create a new agent record.
     *
     * @return the persistent agent entity (existing or newly created)
     */
    @Transactional
    public GatewayAgentEntity discoverAgent(String name, String version,
                                             String protocolVersion,
                                             JsonNode capabilities) {
        String key = cacheKey(name, version);

        // Check cache first
        GatewayAgentEntity cached = agentCache.get(key);
        if (cached != null) {
            // BLOCKED agents are rejected at connection time
            if ("BLOCKED".equals(cached.getApprovalStatus())) {
                log.warn("🚫 BLOCKED agent attempted connection: {} v{} (id={})",
                        name, version, cached.getId());
                throw new AgentBlockedException(
                        "Agent '" + name + "' v" + version + " is blocked by admin. Contact your gateway administrator.");
            }
            // Update mutable fields
            cached.setProtocolVersion(protocolVersion);
            cached.setCapabilities(capabilities);
            cached.setStatus("ACTIVE");
            GatewayAgentEntity updated = agentRepository.saveAndFlush(cached);
            agentCache.put(key, updated);
            log.info("🤖 Agent re-discovered: {} v{} (id={}, approval={})",
                    name, version, updated.getId(), updated.getApprovalStatus());
            return updated;
        }

        // Check database (cache miss — maybe another instance created it)
        Optional<GatewayAgentEntity> existing =
                agentRepository.findByAgentNameAndAgentVersion(name, version);

        if (existing.isPresent()) {
            GatewayAgentEntity entity = existing.get();
            // BLOCKED agents are rejected at connection time
            if ("BLOCKED".equals(entity.getApprovalStatus())) {
                agentCache.put(key, entity);
                log.warn("🚫 BLOCKED agent attempted connection: {} v{} (id={})",
                        name, version, entity.getId());
                throw new AgentBlockedException(
                        "Agent '" + name + "' v" + version + " is blocked by admin. Contact your gateway administrator.");
            }
            entity.setProtocolVersion(protocolVersion);
            entity.setCapabilities(capabilities);
            entity.setStatus("ACTIVE");
            GatewayAgentEntity updated = agentRepository.saveAndFlush(entity);
            agentCache.put(key, updated);
            log.info("🤖 Agent re-discovered (from DB): {} v{} (id={}, approval={})",
                    name, version, updated.getId(), updated.getApprovalStatus());
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
    //  SESSION MANAGEMENT
    // ════════════════════════════════════════════════════════════════════

    /**
     * Register a new agent session. Called immediately after agent discovery
     * during the MCP initialize handshake.
     */
    @Transactional
    public GatewayAgentSessionEntity registerSession(UUID agentId, String sessionId,
                                                      String authMethod,
                                                      String authIdentity) {
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

        log.info("📡 Agent session registered: {} → agent {} v{} (session={})",
                sessionId, agent.getAgentName(), agent.getAgentVersion(), saved.getId());
        return saved;
    }

    /**
     * Mark a session as disconnected. Called when the agent closes the connection.
     */
    @Transactional
    public void disconnectSession(String sessionId) {
        sessionRepository.markDisconnected(sessionId);
        sessionToAgentId.remove(sessionId);
        log.info("📡 Agent session disconnected: {}", sessionId);
    }

    /**
     * Layer 1 — Active Session Replacement: disconnect all existing CONNECTED sessions
     * for an agent except the newly created one.
     *
     * <p>Called during HTTP {@code initialize} to clean up zombie sessions caused by:
     * <ul>
     *   <li>Agent close/reopen (mcp-remote doesn't send DELETE)</li>
     *   <li>Agent delete-config/reconfig before timeout</li>
     *   <li>Network interruption followed by reconnect</li>
     * </ul>
     *
     * @param agentId          the agent's UUID
     * @param excludeSessionId the new session to keep (just registered)
     * @return count of stale sessions that were disconnected
     */
    @Transactional
    public int disconnectExistingSessionsForAgent(UUID agentId, String excludeSessionId) {
        List<GatewayAgentSessionEntity> staleSessions =
                sessionRepository.findActiveSessionsForAgentExcluding(agentId, excludeSessionId);
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
     * <p>Called at the END of each orchestration method (after the southbound call
     * completes) to keep sessions alive during long-running tool calls.
     * The existing {@link #recordRequest(String)} updates lastRequestAt at request START;
     * this method updates it at response COMPLETION — so the reaper won't kill
     * sessions with tools that take 8+ minutes.
     *
     * <p>Runs async on {@code mcpAuditExecutor} — never blocks the hot path.
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
    //  REQUEST COUNTING — Async, non-blocking
    // ════════════════════════════════════════════════════════════════════

    /**
     * Record a request from an agent session. Called from the orchestration
     * layer on every tool/prompt/resource call.
     *
     * <p>Runs on {@code mcpAuditExecutor} to avoid blocking the hot path.
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
        } catch (Exception e) {
            log.debug("Failed to record agent request for session {}: {}",
                    sessionId, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  APPROVAL CHECK — O(1) in-memory, called from orchestration hot path
    // ════════════════════════════════════════════════════════════════════

    /**
     * Check if the agent for a given session is blocked.
     *
     * <p>O(1) — two ConcurrentHashMap lookups, no DB hit.
     * Called from the orchestration layer BEFORE any tool forwarding.
     *
     * @param sessionId the agent's MCP session ID
     * @return {@code true} if the agent is NOT APPROVED (i.e., PENDING, BLOCKED, or unknown), {@code false} only if APPROVED
     */
    public boolean isAgentBlocked(String sessionId) {
        if (sessionId == null) return false;
        UUID agentId = sessionToAgentId.get(sessionId);
        if (agentId == null) return false;

        // Find agent in cache by iterating values (small map, typically < 20 agents)
        for (GatewayAgentEntity agent : agentCache.values()) {
            if (agentId.equals(agent.getId())) {
                return !"APPROVED".equals(agent.getApprovalStatus());
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════
    //  READ METHODS — For REST API and Dashboard
    // ════════════════════════════════════════════════════════════════════

    /** Return all discovered agents (all statuses). */
    public List<GatewayAgentEntity> getAllAgents() {
        return agentRepository.findAll();
    }

    /** Return a specific agent by ID. */
    public Optional<GatewayAgentEntity> getAgent(UUID id) {
        return agentRepository.findById(id);
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
    //  LIVE SESSION DETAILS — For real-time monitoring
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
            // Compute idle duration (time since last request, or since connected if no requests)
            LocalDateTime lastActivity = session.getLastRequestAt() != null
                    ? session.getLastRequestAt() : session.getConnectedAt();
            long idleMs = Duration.between(lastActivity, LocalDateTime.now()).toMillis();
            map.put("idleDurationMs", Math.max(0, idleMs));
            map.put("status", session.getStatus());
            result.add(map);
        }

        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    //  APPROVAL WORKFLOW
    // ════════════════════════════════════════════════════════════════════

    /**
     * Approve an agent — sets approval_status to APPROVED.
     * Approved agents are allowed to connect and use all tools.
     */
    @Transactional
    public GatewayAgentEntity approveAgent(UUID agentId) {
        GatewayAgentEntity agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        agent.setApprovalStatus("APPROVED");
        GatewayAgentEntity updated = agentRepository.saveAndFlush(agent);
        // Update cache
        String key = cacheKey(agent.getAgentName(), agent.getAgentVersion());
        agentCache.put(key, updated);
        log.info("✅ Agent APPROVED: {} v{} (id={})",
                agent.getAgentName(), agent.getAgentVersion(), agentId);
        return updated;
    }

    /**
     * Block an agent — sets approval_status to BLOCKED.
     * Blocked agents will be rejected on next connection attempt.
     */
    @Transactional
    public GatewayAgentEntity blockAgent(UUID agentId) {
        GatewayAgentEntity agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        agent.setApprovalStatus("BLOCKED");
        GatewayAgentEntity updated = agentRepository.saveAndFlush(agent);
        // Update cache
        String key = cacheKey(agent.getAgentName(), agent.getAgentVersion());
        agentCache.put(key, updated);
        log.info("🚫 Agent BLOCKED: {} v{} (id={})",
                agent.getAgentName(), agent.getAgentVersion(), agentId);
        return updated;
    }

    // ════════════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
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
