package com.ws.wsAgenticSecurityGateway.agentRegistry.controller;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayHumanUserEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayHumanUserRepository;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.entity.McpAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.repository.McpAuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for Human User management and enterprise analytics.
 *
 * <p>Provides:
 * <ul>
 *   <li>Core CRUD — list, detail, search, block/unblock</li>
 *   <li>Session Lineage — "Which human authorized which AI actions?"</li>
 *   <li>Who's Active Now — real-time human activity for SOC teams</li>
 *   <li>Risk Assessment — behavioral anomaly scoring</li>
 *   <li>Usage Analytics — per-human consumption breakdown</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/human-users")
@Slf4j
public class HumanUserController {

    private final GatewayHumanUserRepository humanUserRepository;
    private final GatewayAgentSessionRepository sessionRepository;
    private final McpAuditLogRepository auditLogRepository;

    public HumanUserController(GatewayHumanUserRepository humanUserRepository,
                               GatewayAgentSessionRepository sessionRepository,
                               McpAuditLogRepository auditLogRepository) {
        this.humanUserRepository = humanUserRepository;
        this.sessionRepository = sessionRepository;
        this.auditLogRepository = auditLogRepository;
    }

    // ════════════════════════════════════════════════════════════════════
    //  CORE CRUD
    // ════════════════════════════════════════════════════════════════════

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listHumanUsers() {
        log.info("GET /api/admin/human-users");
        List<GatewayHumanUserEntity> humans = humanUserRepository.findAll();
        List<Map<String, Object>> result = humans.stream()
                .map(this::toListView)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getHumanUser(@PathVariable UUID id) {
        return humanUserRepository.findById(id)
                .map(human -> ResponseEntity.ok(toDetailView(human)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/sessions")
    public ResponseEntity<List<Map<String, Object>>> getHumanSessions(@PathVariable UUID id) {
        if (!humanUserRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<GatewayAgentSessionEntity> sessions =
                sessionRepository.findByHumanUserIdOrderByConnectedAtDesc(id);

        List<Map<String, Object>> result = sessions.stream().map(s -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", s.getId());
            map.put("sessionId", s.getSessionId());
            map.put("agentName", s.getAgent() != null ? s.getAgent().getAgentName() : null);
            map.put("agentVersion", s.getAgent() != null ? s.getAgent().getAgentVersion() : null);
            map.put("authMethod", s.getAuthMethod());
            map.put("tokenType", s.getTokenType());
            map.put("connectedAt", s.getConnectedAt());
            map.put("disconnectedAt", s.getDisconnectedAt());
            map.put("requestCount", s.getRequestCount());
            map.put("lastRequestAt", s.getLastRequestAt());
            map.put("status", s.getStatus());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/agents")
    public ResponseEntity<List<Map<String, Object>>> getHumanAgents(@PathVariable UUID id) {
        if (!humanUserRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<GatewayAgentSessionEntity> sessions =
                sessionRepository.findByHumanUserIdOrderByConnectedAtDesc(id);

        // Group by agent → count sessions + find last used
        Map<UUID, List<GatewayAgentSessionEntity>> byAgent = sessions.stream()
                .filter(s -> s.getAgent() != null)
                .collect(Collectors.groupingBy(s -> s.getAgent().getId()));

        List<Map<String, Object>> result = byAgent.entrySet().stream().map(entry -> {
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

        return ResponseEntity.ok(result);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        long total = humanUserRepository.count();
        long blocked = humanUserRepository.countBlocked();
        long activeToday = humanUserRepository.countActiveHumansSince(
                LocalDateTime.now().minusHours(24));

        // Count humans with active sessions right now
        List<GatewayAgentSessionEntity> connected = sessionRepository.findByStatus("CONNECTED");
        long activeNow = connected.stream()
                .map(GatewayAgentSessionEntity::getHumanUserId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalHumans", total);
        summary.put("activeToday", activeToday);
        summary.put("blocked", blocked);
        summary.put("activeNow", activeNow);

        return ResponseEntity.ok(summary);
    }

    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> searchHumans(
            @RequestParam("q") String query) {
        List<GatewayHumanUserEntity> results = humanUserRepository
                .findByPreferredUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(query, query);
        return ResponseEntity.ok(results.stream()
                .map(this::toListView)
                .collect(Collectors.toList()));
    }

    @PostMapping("/{id}/block")
    public ResponseEntity<Map<String, Object>> blockHuman(
            @PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        return humanUserRepository.findById(id).map(human -> {
            String reason = body != null ? body.get("reason") : null;
            human.setStatus("BLOCKED");
            human.setBlockedReason(reason);
            human.setBlockedAt(LocalDateTime.now());
            humanUserRepository.save(human);
            log.info("Human user blocked: {} (reason: {})", human.getPreferredUsername(), reason);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "Human user '" + human.getPreferredUsername() + "' blocked");
            result.put("userStatus", human.getStatus());
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/unblock")
    public ResponseEntity<Map<String, Object>> unblockHuman(@PathVariable UUID id) {
        return humanUserRepository.findById(id).map(human -> {
            human.setStatus("ACTIVE");
            human.setBlockedReason(null);
            human.setBlockedAt(null);
            humanUserRepository.save(human);
            log.info("Human user unblocked: {}", human.getPreferredUsername());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "Human user '" + human.getPreferredUsername() + "' unblocked");
            result.put("userStatus", human.getStatus());
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ════════════════════════════════════════════════════════════════════
    //  C2: HUMAN-AGENT SESSION LINEAGE
    // ════════════════════════════════════════════════════════════════════

    @GetMapping("/{id}/lineage")
    public ResponseEntity<Map<String, Object>> getSessionLineage(@PathVariable UUID id) {
        Optional<GatewayHumanUserEntity> opt = humanUserRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        GatewayHumanUserEntity human = opt.get();
        List<GatewayAgentSessionEntity> sessions =
                sessionRepository.findByHumanUserIdOrderByConnectedAtDesc(id);

        if (sessions.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("humanUser", toListView(human));
            result.put("totalSessions", 0);
            result.put("totalToolCalls", 0);
            result.put("agentsUsed", List.of());
            result.put("recentActions", List.of());
            return ResponseEntity.ok(result);
        }

        List<String> sessionIds = sessions.stream()
                .map(GatewayAgentSessionEntity::getSessionId)
                .collect(Collectors.toList());

        // Get tool call audit events for these sessions
        List<McpAuditLog> toolCallLogs = auditLogRepository.findAll((root, query, cb) -> {
            query.orderBy(cb.desc(root.get("timestamp")));
            return cb.and(
                    root.get("sessionId").in(sessionIds),
                    root.get("eventType").in(
                            AuditEventType.ORCHESTRATION_RESPONSE_RETURNED
                    )
            );
        });

        // Group by agent
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

        // Recent actions (last 50)
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
        result.put("humanUser", toListView(human));
        result.put("totalSessions", sessions.size());
        result.put("totalToolCalls", toolCallLogs.size());
        result.put("agentsUsed", agentsUsed);
        result.put("recentActions", recentActions);

        return ResponseEntity.ok(result);
    }

    // ════════════════════════════════════════════════════════════════════
    //  C3: WHO'S ACTIVE NOW
    // ════════════════════════════════════════════════════════════════════

    @GetMapping("/active-now")
    public ResponseEntity<Map<String, Object>> getActiveNow() {
        List<GatewayAgentSessionEntity> connected = sessionRepository.findByStatus("CONNECTED");

        // Separate human vs automated
        Map<UUID, List<GatewayAgentSessionEntity>> byHuman = connected.stream()
                .filter(s -> s.getHumanUserId() != null)
                .collect(Collectors.groupingBy(GatewayAgentSessionEntity::getHumanUserId));

        long automatedCount = connected.stream()
                .filter(s -> s.getHumanUserId() == null)
                .count();

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> activeHumans = byHuman.entrySet().stream().map(entry -> {
            UUID humanId = entry.getKey();
            List<GatewayAgentSessionEntity> humanSessions = entry.getValue();

            GatewayHumanUserEntity human = humanUserRepository.findById(humanId).orElse(null);
            if (human == null) return null;

            List<Map<String, Object>> activeSessions = humanSessions.stream().map(s -> {
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

            int totalReqs = humanSessions.stream()
                    .mapToInt(s -> s.getRequestCount() != null ? s.getRequestCount() : 0).sum();

            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("id", human.getId());
            hm.put("preferredUsername", human.getPreferredUsername());
            hm.put("fullName", human.getFullName());
            hm.put("email", human.getEmail());
            hm.put("realmRoles", human.getRealmRoles());
            hm.put("status", human.getStatus());
            hm.put("activeSessions", activeSessions);
            hm.put("totalActiveAgents", humanSessions.stream()
                    .map(s -> s.getAgent() != null ? s.getAgent().getId() : null)
                    .filter(Objects::nonNull).distinct().count());
            hm.put("totalRequestsThisSession", totalReqs);
            return hm;
        }).filter(Objects::nonNull).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeHumans", activeHumans);
        result.put("totalActiveHumans", activeHumans.size());
        result.put("totalActiveSessions", connected.stream()
                .filter(s -> s.getHumanUserId() != null).count());
        result.put("automatedSessionsCount", automatedCount);

        return ResponseEntity.ok(result);
    }

    // ════════════════════════════════════════════════════════════════════
    //  C4: HUMAN RISK ASSESSMENT
    // ════════════════════════════════════════════════════════════════════

    @GetMapping("/{id}/risk-assessment")
    public ResponseEntity<Map<String, Object>> getRiskAssessment(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "24") int hours) {

        Optional<GatewayHumanUserEntity> opt = humanUserRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        GatewayHumanUserEntity human = opt.get();
        LocalDateTime since = LocalDateTime.now().minusHours(hours);

        List<GatewayAgentSessionEntity> sessions =
                sessionRepository.findByHumanUserIdOrderByConnectedAtDesc(id);
        List<GatewayAgentSessionEntity> windowSessions = sessions.stream()
                .filter(s -> s.getConnectedAt() != null && s.getConnectedAt().isAfter(since))
                .collect(Collectors.toList());

        List<String> windowSessionIds = windowSessions.stream()
                .map(GatewayAgentSessionEntity::getSessionId)
                .collect(Collectors.toList());

        // Get audit logs for this window
        List<McpAuditLog> windowLogs = windowSessionIds.isEmpty() ? List.of()
                : auditLogRepository.findAll((root, query, cb) ->
                cb.and(
                        root.get("sessionId").in(windowSessionIds),
                        cb.greaterThan(root.get("timestamp"), since)
                ));

        List<McpAuditLog> toolCallLogs = windowLogs.stream()
                .filter(l -> l.getEventType() == AuditEventType.ORCHESTRATION_RESPONSE_RETURNED)
                .collect(Collectors.toList());

        // Historical averages (all-time sessions per day)
        long totalDays = Math.max(1, Duration.between(
                human.getFirstSeenAt(), LocalDateTime.now()).toDays());
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
        // Historical unique tools
        List<String> allSessionIds = sessions.stream()
                .map(GatewayAgentSessionEntity::getSessionId)
                .collect(Collectors.toList());
        Set<String> allTimeTools = allSessionIds.isEmpty() ? Set.of()
                : auditLogRepository.findAll((root, query, cb) -> cb.and(
                        root.get("sessionId").in(allSessionIds),
                        root.get("eventType").in(AuditEventType.ORCHESTRATION_RESPONSE_RETURNED),
                        cb.isNotNull(root.get("capabilityName"))
                )).stream().map(McpAuditLog::getCapabilityName).collect(Collectors.toSet());

        int newToolCount = (int) uniqueTools.stream()
                .filter(t -> !allTimeTools.isEmpty() && allTimeTools.contains(t))
                .count();
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
        result.put("humanUser", toListView(human));
        result.put("riskScore", totalScore);
        result.put("riskLevel", riskLevel);
        result.put("signals", signals);
        result.put("assessedAt", LocalDateTime.now());
        result.put("windowHours", hours);

        return ResponseEntity.ok(result);
    }

    // ════════════════════════════════════════════════════════════════════
    //  C5: USAGE ANALYTICS PER HUMAN
    // ════════════════════════════════════════════════════════════════════

    @GetMapping("/{id}/analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "24") int hours) {

        Optional<GatewayHumanUserEntity> opt = humanUserRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        GatewayHumanUserEntity human = opt.get();
        LocalDateTime since = LocalDateTime.now().minusHours(hours);

        List<GatewayAgentSessionEntity> sessions =
                sessionRepository.findByHumanUserIdOrderByConnectedAtDesc(id);
        List<GatewayAgentSessionEntity> windowSessions = sessions.stream()
                .filter(s -> s.getConnectedAt() != null && s.getConnectedAt().isAfter(since))
                .collect(Collectors.toList());

        List<String> sessionIds = windowSessions.stream()
                .map(GatewayAgentSessionEntity::getSessionId)
                .collect(Collectors.toList());

        if (sessionIds.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("humanUser", toListView(human));
            result.put("window", Map.of("hours", hours, "from", since, "to", LocalDateTime.now()));
            result.put("summary", Map.of("totalSessions", 0, "totalRequests", 0,
                    "totalToolCalls", 0, "totalErrors", 0, "errorRate", 0.0));
            result.put("byAgent", List.of());
            result.put("byServer", List.of());
            result.put("byTool", List.of());
            result.put("timeline", List.of());
            return ResponseEntity.ok(result);
        }

        // Reuse existing repo queries
        long totalRequests = auditLogRepository.countBySessionIdsAndTimestampAfter(sessionIds, since);
        List<Object[]> statusBreakdown = auditLogRepository.countByStatusGroupedForSessions(sessionIds, since);
        List<Object[]> toolUsage = auditLogRepository.toolUsageForSessions(sessionIds, since);
        List<Object[]> serverUsage = auditLogRepository.serverUsageForSessions(sessionIds, since);
        List<Object[]> hourlyBuckets = auditLogRepository.countByHourForSessions(sessionIds, since);

        // Compute summary
        long totalErrors = statusBreakdown.stream()
                .filter(r -> r[0] == AuditStatus.ERROR || r[0] == AuditStatus.FAILURE)
                .mapToLong(r -> (Long) r[1]).sum();
        long totalToolCalls = toolUsage.stream().mapToLong(r -> (Long) r[2]).sum();
        double errorRate = totalRequests > 0 ? (double) totalErrors / totalRequests : 0.0;
        double avgReqPerSession = windowSessions.isEmpty() ? 0 : (double) totalRequests / windowSessions.size();

        // Avg session duration
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
            List<String> agentSessionIds = e.getValue().stream()
                    .map(GatewayAgentSessionEntity::getSessionId).collect(Collectors.toList());
            long agentToolCalls = toolUsage.stream()
                    .mapToLong(r -> (Long) r[2]).sum();
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
        result.put("humanUser", toListView(human));
        result.put("window", Map.of("hours", hours, "from", since, "to", LocalDateTime.now()));
        result.put("summary", summary);
        result.put("byAgent", byAgent);
        result.put("byServer", byServer);
        result.put("byTool", byTool);
        result.put("timeline", timeline);

        return ResponseEntity.ok(result);
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    private Map<String, Object> toListView(GatewayHumanUserEntity h) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", h.getId());
        map.put("preferredUsername", h.getPreferredUsername());
        map.put("email", h.getEmail());
        map.put("fullName", h.getFullName());
        map.put("idpIssuer", h.getIdpIssuer());
        map.put("emailVerified", h.getEmailVerified());
        map.put("realmRoles", h.getRealmRoles());
        map.put("clientRoles", h.getClientRoles());
        map.put("totalSessions", h.getTotalSessions());
        map.put("totalRequests", h.getTotalRequests());
        map.put("firstSeenAt", h.getFirstSeenAt());
        map.put("lastSeenAt", h.getLastSeenAt());
        map.put("status", h.getStatus());
        return map;
    }

    private Map<String, Object> toDetailView(GatewayHumanUserEntity h) {
        Map<String, Object> map = toListView(h);
        map.put("idpSubject", h.getIdpSubject());
        map.put("givenName", h.getGivenName());
        map.put("familyName", h.getFamilyName());
        map.put("customClaims", h.getCustomClaims());
        map.put("lastJwtClaims", h.getLastJwtClaims());
        map.put("blockedReason", h.getBlockedReason());
        map.put("blockedAt", h.getBlockedAt());
        return map;
    }

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
