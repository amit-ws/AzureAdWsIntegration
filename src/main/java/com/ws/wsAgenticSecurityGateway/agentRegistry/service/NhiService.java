package com.ws.wsAgenticSecurityGateway.agentRegistry.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayNhiEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.event.BlockedSessionEvent;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayNhiRepository;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.entity.McpAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.repository.McpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service layer for Non-Human Identity (NHI) admin operations.
 *
 * <p>Owns {@link GatewayNhiRepository} and NHI-scoped queries on
 * {@link GatewayAgentSessionRepository}. Provides CRUD, summary stats,
 * session lineage, risk assessment, and usage analytics.
 *
 * <p><strong>Separation from AgentRegistryService:</strong>
 * AgentRegistryService handles hot-path writes (discoverNhi, incrementRequestCount,
 * session lifecycle). This service handles admin/dashboard READ queries and NHI CRUD.
 */
@Service
@Slf4j
public class NhiService {

    private final GatewayNhiRepository nhiRepository;
    private final GatewayAgentSessionRepository sessionRepository;
    private final McpAuditLogRepository auditLogRepository;
    private final AgentRegistryService agentRegistryService;
    private final McpAuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public NhiService(GatewayNhiRepository nhiRepository,
                      GatewayAgentSessionRepository sessionRepository,
                      McpAuditLogRepository auditLogRepository,
                      AgentRegistryService agentRegistryService,
                      McpAuditService auditService,
                      ApplicationEventPublisher eventPublisher) {
        this.nhiRepository = nhiRepository;
        this.sessionRepository = sessionRepository;
        this.auditLogRepository = auditLogRepository;
        this.agentRegistryService = agentRegistryService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    // ════════════════════════════════════════════════════════════════════
    //  CRUD
    // ════════════════════════════════════════════════════════════════════

    public List<GatewayNhiEntity> findAll() {
        return nhiRepository.findAllByWsTenantName(TenantContext.get());
    }

    public Optional<GatewayNhiEntity> findById(UUID id) {
        return nhiRepository.findById(id)
                .filter(entity -> entity.getWsTenantName() != null
                        && entity.getWsTenantName().equals(TenantContext.get()));
    }

    public boolean existsById(UUID id) {
        return findById(id).isPresent();
    }

    public long count() {
        return nhiRepository.countByWsTenantName(TenantContext.get());
    }

    public List<GatewayNhiEntity> search(String query) {
        return nhiRepository.searchByTenant(query, TenantContext.get());
    }

    /**
     * Block an NHI — sets status to BLOCKED, terminates all active sessions,
     * publishes {@link BlockedSessionEvent} for each so {@code HttpMcpAuditFilter}
     * immediately rejects further requests on those sessions.
     *
     * @return a result holder with the entity and the count of terminated sessions
     */
    @Transactional
    public BlockResult blockNhi(UUID id, String reason, String adminActor, String adminIp) {
        GatewayNhiEntity nhi = nhiRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("NHI not found: " + id));
        String previousStatus = nhi.getStatus();
        nhi.setStatus("BLOCKED");
        nhi.setBlockedReason(reason);
        nhi.setBlockedAt(LocalDateTime.now());
        nhiRepository.save(nhi);
        agentRegistryService.updateNhiStatusCache(nhi.getIdpSubject(), "BLOCKED");

        // ── Proactive session termination ──
        // Path 1: Find sessions linked by nhiId FK (AUTOMATED_AGENT tokens)
        List<GatewayAgentSessionEntity> activeSessions =
                sessionRepository.findConnectedByNhiId(id);
        // Path 2: Fallback — find sessions by authIdentity (JWT subject) when nhiId is null
        if (activeSessions.isEmpty() && nhi.getIdpSubject() != null) {
            activeSessions = sessionRepository.findConnectedByAuthIdentity(nhi.getIdpSubject());
            if (!activeSessions.isEmpty()) {
                log.warn("🔍 Found {} active session(s) via authIdentity fallback for NHI '{}' (idpSubject={})",
                        activeSessions.size(), nhi.getServiceName(), nhi.getIdpSubject());
            }
        }
        Map<String, String> affectedAgentMap = new LinkedHashMap<>(); // agentId -> agentName
        for (GatewayAgentSessionEntity session : activeSessions) {
            String sessionId = session.getSessionId();
            String agentName = session.getAgent() != null ? session.getAgent().getAgentName() : "unknown";
            if (session.getAgent() != null) {
                affectedAgentMap.put(session.getAgent().getId().toString(), agentName);
            }
            agentRegistryService.disconnectSession(sessionId);
            eventPublisher.publishEvent(new BlockedSessionEvent(sessionId, "NHI", nhi.getServiceName()));
            auditService.auditBlockedSessionTerminated(sessionId, agentName,
                    "NHI", nhi.getServiceName(),
                    "Admin blocked NHI" + (reason != null ? ": " + reason : ""));
        }

        List<Map<String, Object>> affectedAgents = affectedAgentMap.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("agentId", e.getKey());
                    m.put("agentName", e.getValue());
                    return m;
                }).toList();

        // ── Audit: admin action ──
        auditService.auditNhiBlocked(
                nhi.getId(), nhi.getServiceName(), nhi.getClientId(), nhi.getIdpSubject(),
                previousStatus, reason, adminActor, adminIp,
                activeSessions.size(), affectedAgents);

        log.info("🚫 NHI blocked: {} (reason: {}, sessions terminated: {}, agents affected: {})",
                nhi.getServiceName(), reason, activeSessions.size(), affectedAgents.size());
        return new BlockResult(nhi, activeSessions.size(), affectedAgents);
    }

    @Transactional
    public GatewayNhiEntity unblockNhi(UUID id, String adminActor, String adminIp) {
        GatewayNhiEntity nhi = nhiRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("NHI not found: " + id));
        nhi.setStatus("ACTIVE");
        nhi.setBlockedReason(null);
        nhi.setBlockedAt(null);
        nhiRepository.save(nhi);
        agentRegistryService.updateNhiStatusCache(nhi.getIdpSubject(), "ACTIVE");

        auditService.auditNhiUnblocked(
                nhi.getId(), nhi.getServiceName(), nhi.getClientId(), nhi.getIdpSubject(),
                adminActor, adminIp);

        log.info("✅ NHI unblocked: {}", nhi.getServiceName());
        return nhi;
    }

    /**
     * Approve an NHI — sets status from PENDING → ACTIVE.
     */
    @Transactional
    public GatewayNhiEntity approveNhi(UUID id, String adminActor, String adminIp) {
        GatewayNhiEntity nhi = nhiRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("NHI not found: " + id));
        String previousStatus = nhi.getStatus();
        nhi.setStatus("ACTIVE");
        nhiRepository.save(nhi);
        agentRegistryService.updateNhiStatusCache(nhi.getIdpSubject(), "ACTIVE");

        auditService.auditNhiApproved(
                nhi.getId(), nhi.getServiceName(), nhi.getClientId(), nhi.getIdpSubject(),
                previousStatus, adminActor, adminIp);

        log.info("✅ NHI APPROVED: {} (id={}, previousStatus={})",
                nhi.getServiceName(), id, previousStatus);
        return nhi;
    }

    /** Result holder for block operations — includes entity + session termination count. */
    public record BlockResult(GatewayNhiEntity nhi, int sessionsTerminated,
                               List<Map<String, Object>> affectedAgents) {}

    // ════════════════════════════════════════════════════════════════════
    //  SUMMARY STATS
    // ════════════════════════════════════════════════════════════════════

    public long countActiveSince(LocalDateTime since) {
        return nhiRepository.countActiveSinceByTenant(since, TenantContext.get());
    }

    public long countBlocked() {
        return nhiRepository.countBlockedByTenant(TenantContext.get());
    }

    public Map<String, Object> getSummary() {
        String tenant = TenantContext.get();
        long total = nhiRepository.countByWsTenantName(tenant);
        long blocked = nhiRepository.countBlockedByTenant(tenant);
        long activeToday = nhiRepository.countActiveSinceByTenant(
                LocalDateTime.now().minusHours(24), tenant);

        List<GatewayAgentSessionEntity> connected = sessionRepository.findByStatusAndWsTenantName("CONNECTED", tenant);
        long activeNow = connected.stream()
                .map(GatewayAgentSessionEntity::getNhiId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalNhis", total);
        summary.put("activeToday", activeToday);
        summary.put("blocked", blocked);
        summary.put("activeNow", activeNow);
        return summary;
    }

    // ════════════════════════════════════════════════════════════════════
    //  SESSIONS
    // ════════════════════════════════════════════════════════════════════

    public List<GatewayAgentSessionEntity> getNhiSessions(UUID nhiId) {
        return sessionRepository.findByNhiIdOrderByConnectedAtDesc(nhiId);
    }

    public List<GatewayAgentSessionEntity> getNhiSessionsWithAgent(UUID nhiId) {
        return sessionRepository.findByNhiIdWithAgent(nhiId);
    }

    public List<Map<String, Object>> getNhiAgents(UUID nhiId) {
        List<GatewayAgentSessionEntity> sessions =
                sessionRepository.findByNhiIdOrderByConnectedAtDesc(nhiId);

        Map<UUID, List<GatewayAgentSessionEntity>> byAgent = sessions.stream()
                .filter(s -> s.getAgent() != null)
                .collect(Collectors.groupingBy(s -> s.getAgent().getId()));

        return byAgent.entrySet().stream().map(entry -> {
            List<GatewayAgentSessionEntity> agentSessions = entry.getValue();
            GatewayAgentSessionEntity first = agentSessions.get(0);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("agentId", entry.getKey());
            map.put("agentName", first.getAgent().getAgentName());
            map.put("agentVersion", first.getAgent().getAgentVersion());
            map.put("authClientId", first.getAgent().getAuthClientId());
            map.put("sessionsCount", agentSessions.size());
            map.put("totalRequests", agentSessions.stream()
                    .mapToInt(s -> s.getRequestCount() != null ? s.getRequestCount() : 0).sum());
            map.put("lastUsedAt", agentSessions.stream()
                    .map(GatewayAgentSessionEntity::getConnectedAt)
                    .max(Comparator.naturalOrder()).orElse(null));
            return map;
        }).collect(Collectors.toList());
    }

    // ════════════════════════════════════════════════════════════════════
    //  ACTIVE NOW (SOC)
    // ════════════════════════════════════════════════════════════════════

    public Map<String, Object> getActiveNow() {
        List<GatewayAgentSessionEntity> connected = sessionRepository.findByStatusAndWsTenantName("CONNECTED", TenantContext.get());

        Map<UUID, List<GatewayAgentSessionEntity>> byNhi = connected.stream()
                .filter(s -> s.getNhiId() != null)
                .collect(Collectors.groupingBy(GatewayAgentSessionEntity::getNhiId));

        long nonNhiCount = connected.stream()
                .filter(s -> s.getNhiId() == null)
                .count();

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> activeNhis = byNhi.entrySet().stream().map(entry -> {
            UUID nhiId = entry.getKey();
            List<GatewayAgentSessionEntity> nhiSessions = entry.getValue();

            GatewayNhiEntity nhi = nhiRepository.findById(nhiId).orElse(null);
            if (nhi == null) return null;

            List<Map<String, Object>> activeSessions = nhiSessions.stream().map(s -> {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("sessionId", s.getSessionId());
                sm.put("agentName", s.getAgent() != null ? s.getAgent().getAgentName() : null);
                sm.put("connectedSince", s.getConnectedAt());
                long idleMs = s.getLastRequestAt() != null
                        ? Duration.between(s.getLastRequestAt(), now).toMillis()
                        : Duration.between(s.getConnectedAt(), now).toMillis();
                sm.put("idleDurationMs", Math.max(0, idleMs));
                sm.put("requestCount", s.getRequestCount());
                sm.put("lastRequestAt", s.getLastRequestAt());
                return sm;
            }).collect(Collectors.toList());

            int totalReqs = nhiSessions.stream()
                    .mapToInt(s -> s.getRequestCount() != null ? s.getRequestCount() : 0).sum();

            Map<String, Object> nm = new LinkedHashMap<>();
            nm.put("id", nhi.getId());
            nm.put("serviceName", nhi.getServiceName());
            nm.put("clientId", nhi.getClientId());
            nm.put("idpIssuer", nhi.getIdpIssuer());
            nm.put("realmRoles", nhi.getRealmRoles());
            nm.put("status", nhi.getStatus());
            nm.put("activeSessions", activeSessions);
            nm.put("totalActiveAgents", nhiSessions.stream()
                    .map(s -> s.getAgent() != null ? s.getAgent().getId() : null)
                    .filter(Objects::nonNull).distinct().count());
            nm.put("totalRequestsThisSession", totalReqs);
            return nm;
        }).filter(Objects::nonNull).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeNhis", activeNhis);
        result.put("totalActiveNhis", activeNhis.size());
        result.put("totalActiveSessions", connected.stream()
                .filter(s -> s.getNhiId() != null).count());
        result.put("nonNhiSessionsCount", nonNhiCount);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    //  SESSION LINEAGE
    // ════════════════════════════════════════════════════════════════════

    public Map<String, Object> getSessionLineage(GatewayNhiEntity nhi) {
        List<GatewayAgentSessionEntity> sessions =
                sessionRepository.findByNhiIdOrderByConnectedAtDesc(nhi.getId());

        if (sessions.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalSessions", 0);
            result.put("totalToolCalls", 0);
            result.put("agentsUsed", List.of());
            result.put("recentActions", List.of());
            return result;
        }

        List<String> sessionIds = sessions.stream()
                .map(GatewayAgentSessionEntity::getSessionId)
                .collect(Collectors.toList());

        List<McpAuditLog> toolCallLogs = auditLogRepository.findAll((root, query, cb) -> {
            query.orderBy(cb.desc(root.get("timestamp")));
            return cb.and(
                    root.get("sessionId").in(sessionIds),
                    root.get("eventType").in(AuditEventType.ORCHESTRATION_RESPONSE_RETURNED)
            );
        });

        Map<String, List<GatewayAgentSessionEntity>> sessionsByAgent = sessions.stream()
                .filter(s -> s.getAgent() != null)
                .collect(Collectors.groupingBy(s -> s.getAgent().getAgentName()));

        Map<String, Set<String>> sessionIdsByAgent = new HashMap<>();
        sessionsByAgent.forEach((agentName, agentSessions) ->
                sessionIdsByAgent.put(agentName, agentSessions.stream()
                        .map(GatewayAgentSessionEntity::getSessionId)
                        .collect(Collectors.toSet())));

        List<Map<String, Object>> agentsUsed = sessionsByAgent.entrySet().stream().map(e -> {
            String agentName = e.getKey();
            List<GatewayAgentSessionEntity> agentSessions = e.getValue();
            Set<String> agentSessionIds = sessionIdsByAgent.get(agentName);

            List<McpAuditLog> agentLogs = toolCallLogs.stream()
                    .filter(l -> agentSessionIds.contains(l.getSessionId()))
                    .collect(Collectors.toList());

            Set<String> toolsUsed = agentLogs.stream()
                    .map(McpAuditLog::getCapabilityName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            Map<String, Object> agentMap = new LinkedHashMap<>();
            agentMap.put("agentName", agentName);
            agentMap.put("authClientId", agentSessions.get(0).getAgent().getAuthClientId());
            agentMap.put("sessionsCount", agentSessions.size());
            agentMap.put("toolCallsCount", agentLogs.size());
            agentMap.put("lastUsedAt", agentSessions.stream()
                    .map(GatewayAgentSessionEntity::getConnectedAt)
                    .max(Comparator.naturalOrder()).orElse(null));
            agentMap.put("toolsUsed", new ArrayList<>(toolsUsed));
            return agentMap;
        }).collect(Collectors.toList());

        List<Map<String, Object>> recentActions = toolCallLogs.stream()
                .limit(50)
                .map(l -> {
                    Map<String, Object> action = new LinkedHashMap<>();
                    action.put("timestamp", l.getTimestamp());
                    action.put("sessionId", l.getSessionId());
                    action.put("agentName", l.getAgentName());
                    action.put("action", l.getEventType() != null ? l.getEventType().name() : null);
                    action.put("toolName", l.getCapabilityName());
                    action.put("serverName", l.getServerName());
                    action.put("status", l.getStatus() != null ? l.getStatus().name() : null);
                    action.put("pdpDecision", l.getPdpDecision());
                    action.put("durationMs", l.getDurationMs());
                    return action;
                }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalSessions", sessions.size());
        result.put("totalToolCalls", toolCallLogs.size());
        result.put("agentsUsed", agentsUsed);
        result.put("recentActions", recentActions);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    //  RISK ASSESSMENT
    // ════════════════════════════════════════════════════════════════════

    public Map<String, Object> getRiskAssessment(GatewayNhiEntity nhi, int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);

        List<GatewayAgentSessionEntity> sessions =
                sessionRepository.findByNhiIdOrderByConnectedAtDesc(nhi.getId());
        List<GatewayAgentSessionEntity> windowSessions = sessions.stream()
                .filter(s -> s.getConnectedAt() != null && s.getConnectedAt().isAfter(since))
                .collect(Collectors.toList());

        List<String> windowSessionIds = windowSessions.stream()
                .map(GatewayAgentSessionEntity::getSessionId)
                .collect(Collectors.toList());

        List<McpAuditLog> windowLogs = windowSessionIds.isEmpty() ? List.of()
                : auditLogRepository.findAll((root, query, cb) ->
                cb.and(
                        root.get("sessionId").in(windowSessionIds),
                        cb.greaterThan(root.get("timestamp"), since)
                ));

        List<McpAuditLog> toolCallLogs = windowLogs.stream()
                .filter(l -> l.getEventType() == AuditEventType.ORCHESTRATION_RESPONSE_RETURNED)
                .collect(Collectors.toList());

        long totalDays = Math.max(1, Duration.between(
                nhi.getFirstSeenAt(), LocalDateTime.now()).toDays());
        double avgSessionsPerDay = (double) sessions.size() / totalDays;

        List<Map<String, Object>> signals = new ArrayList<>();
        int totalScore = 0;

        // Signal 1: SESSION_FREQUENCY
        int windowSessionCount = windowSessions.size();
        double baselineSessions = avgSessionsPerDay * ((double) hours / 24.0);
        int sessionScore = baselineSessions > 0 && windowSessionCount > baselineSessions * 3 ? 15
                : baselineSessions > 0 && windowSessionCount > baselineSessions * 2 ? 8 : 0;
        totalScore += sessionScore;
        signals.add(buildSignal("SESSION_FREQUENCY", windowSessionCount,
                Math.round(baselineSessions * 10.0) / 10.0, sessionScore,
                windowSessionCount + " sessions in last " + hours + "h (baseline: "
                        + String.format("%.1f", baselineSessions) + ")"));

        // Signal 2: UNIQUE_TOOLS_ACCESSED
        Set<String> uniqueTools = toolCallLogs.stream()
                .map(McpAuditLog::getCapabilityName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<String> allSessionIds = sessions.stream()
                .map(GatewayAgentSessionEntity::getSessionId)
                .collect(Collectors.toList());
        Set<String> allTimeTools = allSessionIds.isEmpty() ? Set.of()
                : auditLogRepository.findAll((root, query, cb) -> cb.and(
                        root.get("sessionId").in(allSessionIds),
                        root.get("eventType").in(AuditEventType.ORCHESTRATION_RESPONSE_RETURNED),
                        cb.isNotNull(root.get("capabilityName"))
                )).stream().map(McpAuditLog::getCapabilityName).collect(Collectors.toSet());

        int toolBreadthScore = uniqueTools.size() > 10 ? 12 : uniqueTools.size() > 6 ? 5 : 0;
        totalScore += toolBreadthScore;
        signals.add(buildSignal("UNIQUE_TOOLS_ACCESSED", uniqueTools.size(),
                allTimeTools.size(), toolBreadthScore,
                uniqueTools.size() + " distinct tools in window (all-time: " + allTimeTools.size() + ")"));

        // Signal 3: ERROR_RATE
        long totalCalls = toolCallLogs.size();
        long errorCalls = toolCallLogs.stream()
                .filter(l -> l.getStatus() == AuditStatus.ERROR || l.getStatus() == AuditStatus.FAILURE)
                .count();
        double errorRate = totalCalls > 0 ? (double) errorCalls / totalCalls : 0.0;
        int errorScore = errorRate > 0.3 ? 18 : errorRate > 0.15 ? 10 : errorRate > 0.05 ? 3 : 0;
        totalScore += errorScore;
        signals.add(buildSignal("ERROR_RATE", Math.round(errorRate * 1000.0) / 1000.0,
                0.05, errorScore,
                String.format("%.1f%% error rate (%d/%d calls)", errorRate * 100, errorCalls, totalCalls)));

        // Signal 4: OFF_HOURS_USAGE
        long offHoursCount = windowLogs.stream()
                .filter(l -> l.getTimestamp() != null)
                .filter(l -> {
                    LocalTime t = l.getTimestamp().toLocalTime();
                    return t.isBefore(LocalTime.of(8, 0)) || t.isAfter(LocalTime.of(20, 0));
                }).count();
        boolean hasOffHours = offHoursCount > 0;
        int offHoursScore = offHoursCount > 20 ? 15 : offHoursCount > 5 ? 8 : hasOffHours ? 3 : 0;
        totalScore += offHoursScore;
        signals.add(buildSignal("OFF_HOURS_USAGE", hasOffHours, null, offHoursScore,
                offHoursCount + " requests outside business hours (8am-8pm)"));

        // Signal 5: PDP_DENIAL_RATE
        long pdpDenials = windowLogs.stream()
                .filter(l -> "DENIED".equals(l.getPdpDecision()))
                .count();
        long totalPdpChecks = windowLogs.stream()
                .filter(l -> l.getPdpDecision() != null)
                .count();
        double denialRate = totalPdpChecks > 0 ? (double) pdpDenials / totalPdpChecks : 0.0;
        int denialScore = denialRate > 0.2 ? 20 : denialRate > 0.1 ? 12 : denialRate > 0.03 ? 5 : 0;
        totalScore += denialScore;
        signals.add(buildSignal("PDP_DENIAL_RATE", Math.round(denialRate * 1000.0) / 1000.0,
                0.01, denialScore,
                String.format("%.1f%% policy denials (%d/%d checks)", denialRate * 100, pdpDenials, totalPdpChecks)));

        // Signal 6: NEW_TOOLS_ACCESSED
        Set<String> historicalTools = allSessionIds.isEmpty() ? Set.of()
                : auditLogRepository.findAll((root, query, cb) -> cb.and(
                        root.get("sessionId").in(allSessionIds),
                        root.get("eventType").in(AuditEventType.ORCHESTRATION_RESPONSE_RETURNED),
                        cb.isNotNull(root.get("capabilityName")),
                        cb.lessThan(root.get("timestamp"), since)
                )).stream().map(McpAuditLog::getCapabilityName).collect(Collectors.toSet());

        Set<String> newTools = uniqueTools.stream()
                .filter(t -> !historicalTools.contains(t))
                .collect(Collectors.toSet());
        int newToolScore = newTools.size() > 5 ? 15 : newTools.size() > 2 ? 8 : newTools.size() > 0 ? 2 : 0;
        totalScore += newToolScore;
        signals.add(buildSignal("NEW_TOOLS_ACCESSED", newTools.size(), null, newToolScore,
                newTools.size() + " new tools accessed in window"
                        + (newTools.isEmpty() ? "" : ": " + String.join(", ", newTools))));

        totalScore = Math.min(100, totalScore);
        String riskLevel = totalScore >= 76 ? "CRITICAL"
                : totalScore >= 51 ? "HIGH"
                : totalScore >= 26 ? "MEDIUM" : "LOW";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("riskScore", totalScore);
        result.put("riskLevel", riskLevel);
        result.put("signals", signals);
        result.put("assessedAt", LocalDateTime.now());
        result.put("windowHours", hours);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    //  USAGE ANALYTICS
    // ════════════════════════════════════════════════════════════════════

    public Map<String, Object> getUsageAnalytics(GatewayNhiEntity nhi, int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);

        List<GatewayAgentSessionEntity> sessions =
                sessionRepository.findByNhiIdOrderByConnectedAtDesc(nhi.getId());
        List<GatewayAgentSessionEntity> windowSessions = sessions.stream()
                .filter(s -> s.getConnectedAt() != null && s.getConnectedAt().isAfter(since))
                .collect(Collectors.toList());

        List<String> sessionIds = windowSessions.stream()
                .map(GatewayAgentSessionEntity::getSessionId)
                .collect(Collectors.toList());

        if (sessionIds.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("window", Map.of("hours", hours, "from", since, "to", LocalDateTime.now()));
            result.put("summary", Map.of("totalSessions", 0, "totalRequests", 0,
                    "totalToolCalls", 0, "totalErrors", 0, "errorRate", 0.0));
            result.put("byAgent", List.of());
            result.put("byServer", List.of());
            result.put("byTool", List.of());
            result.put("timeline", List.of());
            return result;
        }

        long totalRequests = auditLogRepository.countBySessionIdsAndTimestampAfter(sessionIds, since);
        List<Object[]> statusBreakdown = auditLogRepository.countByStatusGroupedForSessions(sessionIds, since);
        List<Object[]> toolUsage = auditLogRepository.toolUsageForSessions(sessionIds, since);
        List<Object[]> serverUsage = auditLogRepository.serverUsageForSessions(sessionIds, since);
        List<Object[]> hourlyBuckets = auditLogRepository.countByHourForSessions(sessionIds, since);

        long totalErrors = statusBreakdown.stream()
                .filter(r -> r[0] == AuditStatus.ERROR || r[0] == AuditStatus.FAILURE)
                .mapToLong(r -> (Long) r[1]).sum();
        long totalToolCalls = toolUsage.stream().mapToLong(r -> (Long) r[2]).sum();
        double errorRate = totalRequests > 0 ? (double) totalErrors / totalRequests : 0.0;
        double avgReqPerSession = windowSessions.isEmpty() ? 0 : (double) totalRequests / windowSessions.size();

        double avgSessionDurationMs = windowSessions.stream()
                .filter(s -> s.getDisconnectedAt() != null)
                .mapToLong(s -> Duration.between(s.getConnectedAt(), s.getDisconnectedAt()).toMillis())
                .average().orElse(0.0);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalSessions", windowSessions.size());
        summary.put("totalRequests", totalRequests);
        summary.put("totalToolCalls", totalToolCalls);
        summary.put("totalErrors", totalErrors);
        summary.put("errorRate", Math.round(errorRate * 1000.0) / 1000.0);
        summary.put("avgRequestsPerSession", Math.round(avgReqPerSession * 10.0) / 10.0);
        summary.put("avgSessionDurationMs", Math.round(avgSessionDurationMs));

        // By agent
        Map<String, List<GatewayAgentSessionEntity>> byAgentName = windowSessions.stream()
                .filter(s -> s.getAgent() != null)
                .collect(Collectors.groupingBy(s -> s.getAgent().getAgentName()));
        List<Map<String, Object>> byAgent = byAgentName.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentName", e.getKey());
            m.put("sessions", e.getValue().size());
            long agentToolCalls = toolUsage.stream().mapToLong(r -> (Long) r[2]).sum();
            m.put("toolCalls", agentToolCalls);
            m.put("totalRequests", e.getValue().stream()
                    .mapToInt(s -> s.getRequestCount() != null ? s.getRequestCount() : 0).sum());
            return m;
        }).collect(Collectors.toList());

        // By server
        List<Map<String, Object>> byServer = serverUsage.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("serverName", r[0]);
            m.put("requests", r[1]);
            m.put("errors", r[2]);
            return m;
        }).collect(Collectors.toList());

        // By tool
        List<Map<String, Object>> byTool = toolUsage.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("toolName", r[0]);
            m.put("serverName", r[1]);
            m.put("calls", r[2]);
            m.put("avgDurationMs", r[3] != null ? Math.round((Double) r[3]) : null);
            return m;
        }).collect(Collectors.toList());

        // Timeline
        List<Map<String, Object>> timeline = hourlyBuckets.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hour", r[0]);
            m.put("requests", r[1]);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window", Map.of("hours", hours, "from", since, "to", LocalDateTime.now()));
        result.put("summary", summary);
        result.put("byAgent", byAgent);
        result.put("byServer", byServer);
        result.put("byTool", byTool);
        result.put("timeline", timeline);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ════════════════════════════════════════════════════════════════════

    private Map<String, Object> buildSignal(String name, Object value, Object baseline,
                                             int score, String description) {
        String severity = score >= 15 ? "HIGH" : score >= 8 ? "ELEVATED" : score > 0 ? "LOW" : "NORMAL";
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("signal", name);
        signal.put("value", value);
        if (baseline != null) signal.put("baseline", baseline);
        signal.put("score", score);
        signal.put("severity", severity);
        signal.put("description", description);
        return signal;
    }
}
