package com.ws.wsAgenticSecurityGateway.agentRegistry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    /** In-memory cache: "agentName:agentVersion" → entity (for fast upsert checks). */
    private final ConcurrentHashMap<String, GatewayAgentEntity> agentCache = new ConcurrentHashMap<>();

    /** In-memory: sessionId → agentId (for O(1) request counting without DB lookup). */
    private final ConcurrentHashMap<String, UUID> sessionToAgentId = new ConcurrentHashMap<>();

    public AgentRegistryService(GatewayAgentRepository agentRepository,
                                GatewayAgentSessionRepository sessionRepository) {
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
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
            // Update mutable fields
            cached.setProtocolVersion(protocolVersion);
            cached.setCapabilities(capabilities);
            cached.setStatus("ACTIVE");
            GatewayAgentEntity updated = agentRepository.saveAndFlush(cached);
            agentCache.put(key, updated);
            log.info("🤖 Agent re-discovered: {} v{} (id={})",
                    name, version, updated.getId());
            return updated;
        }

        // Check database (cache miss — maybe another instance created it)
        Optional<GatewayAgentEntity> existing =
                agentRepository.findByAgentNameAndAgentVersion(name, version);

        if (existing.isPresent()) {
            GatewayAgentEntity entity = existing.get();
            entity.setProtocolVersion(protocolVersion);
            entity.setCapabilities(capabilities);
            entity.setStatus("ACTIVE");
            GatewayAgentEntity updated = agentRepository.saveAndFlush(entity);
            agentCache.put(key, updated);
            log.info("🤖 Agent re-discovered (from DB): {} v{} (id={})",
                    name, version, updated.getId());
            return updated;
        }

        // New agent — first time ever seen
        GatewayAgentEntity newAgent = GatewayAgentEntity.builder()
                .agentName(name)
                .agentVersion(version)
                .protocolVersion(protocolVersion)
                .capabilities(capabilities)
                .status("ACTIVE")
                .totalSessions(0)
                .totalRequests(0L)
                .build();

        GatewayAgentEntity saved = agentRepository.saveAndFlush(newAgent);
        agentCache.put(key, saved);
        log.info("🤖 NEW agent discovered: {} v{} (id={})",
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
    //  INTERNAL HELPERS
    // ════════════════════════════════════════════════════════════════════

    private String cacheKey(String name, String version) {
        return (name != null ? name : "unknown") + ":" + (version != null ? version : "?");
    }
}
