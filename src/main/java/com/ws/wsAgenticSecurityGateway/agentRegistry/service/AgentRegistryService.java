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

@Service
@Slf4j
public class AgentRegistryService {

    private final GatewayAgentRepository agentRepository;
    private final GatewayAgentSessionRepository sessionRepository;
    private final GatewayHumanUserRepository humanUserRepository;
    private final GatewayNhiRepository nhiRepository;
    private final McpAuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    private final ConcurrentHashMap<String, GatewayAgentEntity> agentCache = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, UUID> sessionToAgentId = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, UUID> sessionToHumanUserId = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, UUID> sessionToNhiId = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, String> sessionToAuthIdentity = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, String> humanStatusBySubject = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, String> nhiStatusBySubject = new ConcurrentHashMap<>();

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

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void loadFromDatabase() {
 log.info("AGENT REGISTRY — Loading from database");

        sessionRepository.markAllDisconnected();
        log.info("Cleaned up orphaned sessions from previous run");

        List<GatewayAgentEntity> agents = agentRepository.findAll();
        for (GatewayAgentEntity agent : agents) {
            String key = cacheKey(agent.getAgentName(), agent.getAgentVersion());
            agentCache.put(key, agent);
            agentStatusById.put(agent.getId(), agent.getApprovalStatus());
        }

        List<GatewayHumanUserEntity> humans = humanUserRepository.findAll();
        for (GatewayHumanUserEntity h : humans) {
            if (h.getIdpSubject() != null) {
                humanStatusBySubject.put(h.getIdpSubject(), h.getStatus());
            }
        }

        List<GatewayNhiEntity> nhis = nhiRepository.findAll();
        for (GatewayNhiEntity n : nhis) {
            if (n.getIdpSubject() != null) {
                nhiStatusBySubject.put(n.getIdpSubject(), n.getStatus());
            }
        }

        log.info("Loaded {} agent profiles into cache", agents.size());
        log.info("Warmed status caches: {} humans, {} NHIs, {} agents",
                humanStatusBySubject.size(), nhiStatusBySubject.size(), agentStatusById.size());
        for (GatewayAgentEntity agent : agents) {
            log.info("- {} v{} ({}sessions, {}requests, approval={})",
                    agent.getAgentName(),
                    agent.getAgentVersion() != null ? agent.getAgentVersion() : "?",
                    agent.getTotalSessions(),
                    agent.getTotalRequests(),
                    agent.getApprovalStatus());
        }

    }

    @Transactional
    public GatewayAgentEntity discoverAgent(String name, String version,
            String protocolVersion,
            JsonNode capabilities) {
        return discoverAgent(name, version, protocolVersion, capabilities, null, null, null);
    }

    @Transactional
    public GatewayAgentEntity discoverAgent(String name, String version,
            String protocolVersion,
            JsonNode capabilities,
            String authClientId, String tokenType,
            String wsTenantName) {
        String key = cacheKey(name, version);

        GatewayAgentEntity cached = agentCache.get(key);
        if (cached != null) {
            if ("BLOCKED".equals(cached.getApprovalStatus())) {
 log.warn("BLOCKED agent attempted connection: {} v{} (id={})",
                        name, version, cached.getId());
                throw new AgentBlockedException(
                        "Agent '" + name + "' v" + version
                                + " is blocked by admin. Contact your gateway administrator.");
            }
            cached.setProtocolVersion(protocolVersion);
            cached.setCapabilities(capabilities);
            cached.setStatus("ACTIVE");
            if (authClientId != null) cached.setAuthClientId(authClientId);
            if (tokenType != null) cached.setTokenType(tokenType);
            if (wsTenantName != null) cached.setWsTenantName(wsTenantName);
            GatewayAgentEntity updated = agentRepository.saveAndFlush(cached);
            agentCache.put(key, updated);
            agentStatusById.put(updated.getId(), updated.getApprovalStatus());
 log.info("Agent re-discovered: {} v{} (id={}, approval={}, authClientId={})",
                    name, version, updated.getId(), updated.getApprovalStatus(), authClientId);
            return updated;
        }

        Optional<GatewayAgentEntity> existing = agentRepository.findByAgentNameAndAgentVersion(name, version);

        if (existing.isPresent()) {
            GatewayAgentEntity entity = existing.get();
            if ("BLOCKED".equals(entity.getApprovalStatus())) {
                agentCache.put(key, entity);
 log.warn("BLOCKED agent attempted connection: {} v{} (id={})",
                        name, version, entity.getId());
                throw new AgentBlockedException(
                        "Agent '" + name + "' v" + version
                                + " is blocked by admin. Contact your gateway administrator.");
            }
            entity.setProtocolVersion(protocolVersion);
            entity.setCapabilities(capabilities);
            entity.setStatus("ACTIVE");
            if (authClientId != null) entity.setAuthClientId(authClientId);
            if (tokenType != null) entity.setTokenType(tokenType);
            if (wsTenantName != null) entity.setWsTenantName(wsTenantName);
            GatewayAgentEntity updated = agentRepository.saveAndFlush(entity);
            agentCache.put(key, updated);
            agentStatusById.put(updated.getId(), updated.getApprovalStatus());
 log.info("Agent re-discovered (from DB): {} v{} (id={}, approval={}, authClientId={})",
                    name, version, updated.getId(), updated.getApprovalStatus(), authClientId);
            return updated;
        }

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
 log.info("NEW agent discovered (PENDING approval): {} v{} (id={})",
                name, version, saved.getId());
        return saved;
    }

    @Transactional
    public GatewayAgentSessionEntity registerSession(UUID agentId, String sessionId,
            String authMethod,
            String authIdentity) {
        return registerSession(agentId, sessionId, authMethod, authIdentity, null, null, null);
    }

    @Transactional
    public GatewayAgentSessionEntity registerSession(UUID agentId, String sessionId,
            String authMethod, String authIdentity,
            String tokenType, UUID humanUserId) {
        return registerSession(agentId, sessionId, authMethod, authIdentity, tokenType, humanUserId, null, null, null);
    }

    @Transactional
    public GatewayAgentSessionEntity registerSession(UUID agentId, String sessionId,
            String authMethod, String authIdentity,
            String tokenType, UUID humanUserId, UUID nhiId) {
        return registerSession(agentId, sessionId, authMethod, authIdentity, tokenType, humanUserId, nhiId, null, null);
    }

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

        agentRepository.incrementSessionCount(agentId);

        GatewayAgentEntity refreshed = agentRepository.findById(agentId).orElse(null);
        if (refreshed != null) {
            agentCache.put(cacheKey(refreshed.getAgentName(), refreshed.getAgentVersion()), refreshed);
        }

        sessionToAgentId.put(sessionId, agentId);

        if (humanUserId != null) {
            sessionToHumanUserId.put(sessionId, humanUserId);
        }

        if (saved.getNhiId() != null) {
            sessionToNhiId.put(sessionId, saved.getNhiId());
        }

        if (authIdentity != null) {
            sessionToAuthIdentity.put(sessionId, authIdentity);
        }

 log.info("Agent session registered: {} → agent {} v{} (session={}, humanUser={}, authIdentity={})",
                sessionId, agent.getAgentName(), agent.getAgentVersion(), saved.getId(), humanUserId, authIdentity);
        return saved;
    }

    @Transactional
    public void disconnectSession(String sessionId) {
        sessionRepository.markDisconnected(sessionId);
        sessionToAgentId.remove(sessionId);
        sessionToHumanUserId.remove(sessionId);
        sessionToNhiId.remove(sessionId);
        sessionToAuthIdentity.remove(sessionId);
 log.info("Agent session disconnected: {}", sessionId);
    }

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
            staleSessions = sessionRepository.findActiveSessionsForAgentExcluding(agentId, excludeSessionId);
        }

        for (GatewayAgentSessionEntity stale : staleSessions) {
            String staleSessionId = stale.getSessionId();
            disconnectSession(staleSessionId);
            auditService.auditServerSessionDisconnectedSync(staleSessionId,
                    stale.getAgent() != null ? stale.getAgent().getAgentName() : "unknown");
        }
        if (!staleSessions.isEmpty()) {
 log.info("Disconnected {} stale session(s) for identity (human={}, nhi={}, auth={}) + agent={}",
                    staleSessions.size(), humanUserId, nhiId, authIdentity, agentId);
        }
        return staleSessions.size();
    }

    @Async("mcpAuditExecutor")
    @Transactional
    public void updateLastActivity(String sessionId) {
        try {
            sessionRepository.updateLastRequestAt(sessionId);
        } catch (Exception e) {
            log.debug("Failed to update activity for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Async("mcpAuditExecutor")
    @Transactional
    public void recordRequest(String sessionId) {
        try {
            sessionRepository.incrementRequestCount(sessionId);

            UUID agentId = sessionToAgentId.get(sessionId);
            if (agentId != null) {
                agentRepository.incrementRequestCount(agentId);
            }

            UUID humanUserId = sessionToHumanUserId.get(sessionId);
            if (humanUserId != null) {
                humanUserRepository.incrementRequestCount(humanUserId);
            }

            UUID nhiId = sessionToNhiId.get(sessionId);
            if (nhiId != null) {
                nhiRepository.incrementRequestCount(nhiId);
            }
        } catch (Exception e) {
            log.debug("Failed to record agent request for session {}: {}",
                    sessionId, e.getMessage());
        }
    }

    public boolean isAgentBlocked(String sessionId) {
        if (sessionId == null)
            return false;
        UUID agentId = sessionToAgentId.get(sessionId);
        if (agentId == null)
            return false;

        for (GatewayAgentEntity agent : agentCache.values()) {
            if (agentId.equals(agent.getId())) {
                return !"APPROVED".equals(agent.getApprovalStatus());
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public boolean isAgentBlockedForSession(String sessionId) {
        if (sessionId == null) return false;
        return sessionRepository.findAgentApprovalStatusBySessionId(sessionId)
                .map("BLOCKED"::equals)
                .orElse(false);
    }

    public String getHumanStatus(String jwtSubject) {
        if (jwtSubject == null) return null;
        String cached = humanStatusBySubject.get(jwtSubject);
        if (cached != null) return cached;
        return humanUserRepository.findByIdpSubject(jwtSubject)
                .map(h -> {
                    humanStatusBySubject.put(jwtSubject, h.getStatus());
                    return h.getStatus();
                })
                .orElse(null);
    }

    public String getNhiStatus(String jwtSubject) {
        if (jwtSubject == null) return null;
        String cached = nhiStatusBySubject.get(jwtSubject);
        if (cached != null) return cached;
        return nhiRepository.findByIdpSubject(jwtSubject)
                .map(n -> {
                    nhiStatusBySubject.put(jwtSubject, n.getStatus());
                    return n.getStatus();
                })
                .orElse(null);
    }

    public String getAgentStatusForSession(String sessionId) {
        if (sessionId == null) return null;
        UUID agentId = sessionToAgentId.get(sessionId);
        if (agentId != null) {
            String status = agentStatusById.get(agentId);
            if (status != null) return status;
        }
        return sessionRepository.findAgentApprovalStatusBySessionId(sessionId)
                .orElse(null);
    }

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

    public void updateHumanStatusCache(String idpSubject, String newStatus) {
        if (idpSubject != null) {
            humanStatusBySubject.put(idpSubject, newStatus);
        }
    }

    public void updateNhiStatusCache(String idpSubject, String newStatus) {
        if (idpSubject != null) {
            nhiStatusBySubject.put(idpSubject, newStatus);
        }
    }

    public UUID getAgentIdForSession(String sessionId) {
        if (sessionId == null) return null;
        return sessionToAgentId.get(sessionId);
    }

    @Transactional(readOnly = true)
    public String getAgentNameBySessionId(String sessionId) {
        if (sessionId == null)
            return "unknown";

        UUID agentId = sessionToAgentId.get(sessionId);
        if (agentId != null) {
            for (GatewayAgentEntity agent : agentCache.values()) {
                if (agentId.equals(agent.getId())) {
                    return agent.getAgentName();
                }
            }
        }

        return sessionRepository.findBySessionId(sessionId)
                .map(s -> s.getAgent().getAgentName())
                .orElse("unknown");
    }

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

    public List<GatewayAgentEntity> getAllAgents() {
        return agentRepository.findAllByWsTenantName(TenantContext.get());
    }

    public Optional<GatewayAgentEntity> getAgent(UUID id) {
        return agentRepository.findById(id)
                .filter(entity -> entity.getWsTenantName() != null
                        && entity.getWsTenantName().equals(TenantContext.get()));
    }

    public List<GatewayAgentEntity> findAgentsByName(String agentName) {
        return agentRepository.findByAgentNameAndWsTenantName(agentName, TenantContext.get());
    }

    public List<GatewayAgentSessionEntity> getAgentSessions(UUID agentId) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return sessionRepository.findByAgentIdAndWsTenantNameOrderByConnectedAtDesc(agentId, tenant);
        }
        return sessionRepository.findByAgentIdOrderByConnectedAtDesc(agentId);
    }

    public List<GatewayAgentSessionEntity> getConnectedSessions() {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return sessionRepository.findByStatusAndWsTenantName("CONNECTED", tenant);
        }
        return sessionRepository.findByStatus("CONNECTED");
    }

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
            long connectedMs = Duration.between(
                    session.getConnectedAt(), LocalDateTime.now()).toMillis();
            map.put("connectedDurationMs", connectedMs);
            map.put("requestCount", session.getRequestCount());
            map.put("lastRequestAt", session.getLastRequestAt());
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

    public long countSessionsForAgent(UUID agentId) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return sessionRepository.countByAgentIdAndWsTenantName(agentId, tenant);
        }
        return sessionRepository.countByAgentId(agentId);
    }

    public Optional<GatewayAgentSessionEntity> findSessionBySessionId(String sessionId) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return sessionRepository.findBySessionIdAndWsTenantName(sessionId, tenant);
        }
        return sessionRepository.findBySessionId(sessionId);
    }

    @Transactional
    public GatewayAgentEntity approveAgent(UUID agentId, String adminActor, String adminIp) {
        GatewayAgentEntity agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        String previousApprovalStatus = agent.getApprovalStatus();
        agent.setApprovalStatus("APPROVED");
        GatewayAgentEntity updated = agentRepository.saveAndFlush(agent);
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
 log.info("Agent APPROVED: {} v{} (id={})",
                agent.getAgentName(), agent.getAgentVersion(), agentId);
        return updated;
    }

    @Transactional
    public AgentBlockResult blockAgent(UUID agentId, String adminActor, String adminIp) {
        GatewayAgentEntity agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        String previousApprovalStatus = agent.getApprovalStatus();
        agent.setApprovalStatus("BLOCKED");
        GatewayAgentEntity updated = agentRepository.saveAndFlush(agent);
        String key = cacheKey(agent.getAgentName(), agent.getAgentVersion());
        agentCache.put(key, updated);
        agentStatusById.put(agentId, "BLOCKED");

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
 log.info("Agent BLOCKED: {} v{} (id={}, sessions terminated: {}, humans affected: {}, NHIs affected: {})",
                agent.getAgentName(), agent.getAgentVersion(), agentId,
                activeSessions.size(), affectedHumans.size(), affectedNhis.size());
        return new AgentBlockResult(updated, activeSessions.size(), affectedHumans, affectedNhis);
    }

    public record AgentBlockResult(
            GatewayAgentEntity agent,
            int sessionsTerminated,
            List<Map<String, Object>> affectedHumanUsers,
            List<Map<String, Object>> affectedNhis
    ) {}

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
 log.info("Human user updated: {} (sub={}, email={}, ip={})",
                    preferredUsername, idpSubject, email, ipAddress);
            return updated;
        }

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
 log.info("NEW human user discovered (PENDING approval): {} (sub={}, email={}, ip={})",
                preferredUsername, idpSubject, email, ipAddress);
        return saved;
    }

    @Transactional
    public void incrementHumanSessionCount(UUID humanUserId) {
        humanUserRepository.findById(humanUserId).ifPresent(user -> {
            user.setTotalSessions(user.getTotalSessions() + 1);
            humanUserRepository.saveAndFlush(user);
        });
    }

    public UUID getHumanUserIdForSession(String sessionId) {
        if (sessionId == null) return null;
        return sessionToHumanUserId.get(sessionId);
    }

    public Optional<String> getHumanBlockReason(String sessionId) {
        if (sessionId == null) return Optional.empty();

        UUID humanUserId = sessionToHumanUserId.get(sessionId);
        if (humanUserId != null) {
            return humanUserRepository.findById(humanUserId)
                    .filter(h -> "BLOCKED".equals(h.getStatus()))
                    .map(h -> h.getBlockedReason() != null ? h.getBlockedReason() : "Blocked by admin");
        }

        String authIdentity = sessionToAuthIdentity.get(sessionId);
        if (authIdentity != null) {
            Optional<String> reason = humanUserRepository.findByIdpSubject(authIdentity)
                    .filter(h -> "BLOCKED".equals(h.getStatus()))
                    .map(h -> h.getBlockedReason() != null ? h.getBlockedReason() : "Blocked by admin");
            if (reason.isPresent()) {
 log.warn("Human block reason found via authIdentity fallback: session={}, sub={}", sessionId, authIdentity);
            }
            return reason;
        }

        return Optional.empty();
    }

    public String getHumanUsername(String sessionId) {
        if (sessionId == null) return null;

        UUID humanUserId = sessionToHumanUserId.get(sessionId);
        if (humanUserId != null) {
            return humanUserRepository.findById(humanUserId)
                    .map(GatewayHumanUserEntity::getPreferredUsername)
                    .orElse(null);
        }

        String authIdentity = sessionToAuthIdentity.get(sessionId);
        if (authIdentity != null) {
            return humanUserRepository.findByIdpSubject(authIdentity)
                    .map(GatewayHumanUserEntity::getPreferredUsername)
                    .orElse(null);
        }

        return null;
    }

    public boolean isHumanBlockedBySubject(String idpSubject) {
        if (idpSubject == null) return false;
        return humanUserRepository.findByIdpSubject(idpSubject)
                .map(h -> "BLOCKED".equals(h.getStatus()))
                .orElse(false);
    }

    public String getHumanBlockReasonBySubject(String idpSubject) {
        if (idpSubject == null) return "Blocked by admin";
        return humanUserRepository.findByIdpSubject(idpSubject)
                .filter(h -> "BLOCKED".equals(h.getStatus()))
                .map(h -> h.getBlockedReason() != null ? h.getBlockedReason() : "Blocked by admin")
                .orElse("Blocked by admin");
    }

    public String getNhiBlockReasonBySubject(String idpSubject) {
        if (idpSubject == null) return "Blocked by admin";
        return nhiRepository.findByIdpSubject(idpSubject)
                .filter(n -> "BLOCKED".equals(n.getStatus()))
                .map(n -> n.getBlockedReason() != null ? n.getBlockedReason() : "Blocked by admin")
                .orElse("Blocked by admin");
    }

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
 log.info("NEW NHI discovered (PENDING approval): {} (sub={}, clientId={}, ip={})",
                saved.getServiceName(), idpSubject, clientId, ipAddress);
        return saved;
    }

    @Transactional
    public void incrementNhiSessionCount(UUID nhiId) {
        nhiRepository.findById(nhiId).ifPresent(nhi -> {
            nhi.setTotalSessions(nhi.getTotalSessions() + 1);
            nhiRepository.saveAndFlush(nhi);
        });
    }

    public UUID getNhiIdForSession(String sessionId) {
        if (sessionId == null) return null;
        return sessionToNhiId.get(sessionId);
    }

    public Optional<String> getNhiBlockReason(String sessionId) {
        if (sessionId == null) return Optional.empty();

        UUID nhiId = sessionToNhiId.get(sessionId);
        if (nhiId != null) {
            return nhiRepository.findById(nhiId)
                    .filter(n -> "BLOCKED".equals(n.getStatus()))
                    .map(n -> n.getBlockedReason() != null ? n.getBlockedReason() : "Blocked by admin");
        }

        String authIdentity = sessionToAuthIdentity.get(sessionId);
        if (authIdentity != null) {
            Optional<String> reason = nhiRepository.findByIdpSubject(authIdentity)
                    .filter(n -> "BLOCKED".equals(n.getStatus()))
                    .map(n -> n.getBlockedReason() != null ? n.getBlockedReason() : "Blocked by admin");
            if (reason.isPresent()) {
 log.warn("NHI block reason found via authIdentity fallback: session={}, sub={}", sessionId, authIdentity);
            }
            return reason;
        }

        return Optional.empty();
    }

    public String getNhiServiceName(String sessionId) {
        if (sessionId == null) return null;

        UUID nhiId = sessionToNhiId.get(sessionId);
        if (nhiId != null) {
            return nhiRepository.findById(nhiId)
                    .map(GatewayNhiEntity::getServiceName)
                    .orElse(null);
        }

        String authIdentity = sessionToAuthIdentity.get(sessionId);
        if (authIdentity != null) {
            return nhiRepository.findByIdpSubject(authIdentity)
                    .map(GatewayNhiEntity::getServiceName)
                    .orElse(null);
        }

        return null;
    }

    public boolean isNhiBlockedBySubject(String idpSubject) {
        if (idpSubject == null) return false;
        return nhiRepository.findByIdpSubject(idpSubject)
                .map(n -> "BLOCKED".equals(n.getStatus()))
                .orElse(false);
    }

    private String cacheKey(String name, String version) {
        return (name != null ? name : "unknown") + ":" + (version != null ? version : "?");
    }

    public static class AgentBlockedException extends RuntimeException {
        public AgentBlockedException(String message) {
            super(message);
        }
    }
}
