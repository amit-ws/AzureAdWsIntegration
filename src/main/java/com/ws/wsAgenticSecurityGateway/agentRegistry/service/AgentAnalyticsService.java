package com.ws.wsAgenticSecurityGateway.agentRegistry.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import com.ws.wsAgenticSecurityGateway.audit.repository.GatewayAuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AgentAnalyticsService {

    private final AgentRegistryService agentRegistryService;
    private final GatewayAgentSessionRepository sessionRepository;
    private final GatewayAuditLogRepository auditLogRepository;

    public AgentAnalyticsService(AgentRegistryService agentRegistryService,
                                  GatewayAgentSessionRepository sessionRepository,
                                  GatewayAuditLogRepository auditLogRepository) {
        this.agentRegistryService = agentRegistryService;
        this.sessionRepository = sessionRepository;
        this.auditLogRepository = auditLogRepository;
    }

    public Map<String, Object> getAgentAnalytics(UUID agentId, int hours) {
        Optional<GatewayAgentEntity> agentOpt = agentRegistryService.getAgent(agentId);
        if (agentOpt.isEmpty()) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }
        GatewayAgentEntity agent = agentOpt.get();

        List<GatewayAgentSessionEntity> sessions =
                sessionRepository.findByAgentIdOrderByConnectedAtDesc(agentId);
        List<String> sessionIds = sessions.stream()
                .map(GatewayAgentSessionEntity::getSessionId)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId);
        result.put("agentName", agent.getAgentName());
        result.put("agentVersion", agent.getAgentVersion());
        result.put("period", hours + "h");

        if (sessionIds.isEmpty()) {
            result.put("totalRequests", 0);
            result.put("successCount", 0);
            result.put("errorCount", 0);
            result.put("errorRate", 0.0);
            result.put("avgLatencyMs", null);
            result.put("p50LatencyMs", null);
            result.put("p95LatencyMs", null);
            result.put("p99LatencyMs", null);
            result.put("requestsByHour", List.of());
            result.put("toolUsage", List.of());
            result.put("serverUsage", List.of());
            result.put("errorBreakdown", List.of());
            return result;
        }

        LocalDateTime since = LocalDateTime.now().minusHours(hours);

        long totalRequests = auditLogRepository.countBySessionIdsAndTimestampAfter(sessionIds, since);
        result.put("totalRequests", totalRequests);

        List<Object[]> statusGroups = auditLogRepository.countByStatusGroupedForSessions(sessionIds, since);
        long successCount = 0;
        long errorCount = 0;
        for (Object[] row : statusGroups) {
            String status = row[0] != null ? row[0].toString() : "";
            long count = ((Number) row[1]).longValue();
            if ("SUCCESS".equals(status)) {
                successCount = count;
            } else if ("ERROR".equals(status) || "FAILURE".equals(status)) {
                errorCount += count;
            }
        }
        result.put("successCount", successCount);
        result.put("errorCount", errorCount);
        result.put("errorRate", totalRequests > 0 ? (errorCount * 100.0 / totalRequests) : 0.0);

        try {
            List<Object[]> percentiles = auditLogRepository.latencyPercentilesForSessions(sessionIds, since);
            if (!percentiles.isEmpty() && percentiles.get(0) != null) {
                Object[] p = percentiles.get(0);
                result.put("p50LatencyMs", p[0] != null ? ((Number) p[0]).doubleValue() : null);
                result.put("p95LatencyMs", p[1] != null ? ((Number) p[1]).doubleValue() : null);
                result.put("p99LatencyMs", p[2] != null ? ((Number) p[2]).doubleValue() : null);
                Double avg = (p[0] != null && p[2] != null)
                        ? (((Number) p[0]).doubleValue() + ((Number) p[2]).doubleValue()) / 2.0
                        : null;
                result.put("avgLatencyMs", avg);
            } else {
                result.put("p50LatencyMs", null);
                result.put("p95LatencyMs", null);
                result.put("p99LatencyMs", null);
                result.put("avgLatencyMs", null);
            }
        } catch (Exception e) {
            log.debug("Failed to compute latency percentiles for agent {}: {}", agentId, e.getMessage());
            result.put("p50LatencyMs", null);
            result.put("p95LatencyMs", null);
            result.put("p99LatencyMs", null);
            result.put("avgLatencyMs", null);
        }

        try {
            List<Object[]> hourlyData = auditLogRepository.countByHourForSessions(sessionIds, since);
            List<Map<String, Object>> hourlyList = new ArrayList<>();
            for (Object[] row : hourlyData) {
                Map<String, Object> hourMap = new LinkedHashMap<>();
                hourMap.put("hour", row[0] != null ? row[0].toString() : null);
                hourMap.put("count", ((Number) row[1]).longValue());
                hourlyList.add(hourMap);
            }
            result.put("requestsByHour", hourlyList);
        } catch (Exception e) {
            log.debug("Failed to compute hourly breakdown for agent {}: {}", agentId, e.getMessage());
            result.put("requestsByHour", List.of());
        }

        try {
            List<Object[]> toolData = auditLogRepository.toolUsageForSessions(sessionIds, since);
            List<Map<String, Object>> toolList = new ArrayList<>();
            for (Object[] row : toolData) {
                Map<String, Object> toolMap = new LinkedHashMap<>();
                toolMap.put("toolName", row[0]);
                toolMap.put("serverName", row[1]);
                toolMap.put("count", ((Number) row[2]).longValue());
                toolMap.put("avgLatencyMs", row[3] != null ? ((Number) row[3]).doubleValue() : null);
                toolList.add(toolMap);
            }
            result.put("toolUsage", toolList);
        } catch (Exception e) {
            log.debug("Failed to compute tool usage for agent {}: {}", agentId, e.getMessage());
            result.put("toolUsage", List.of());
        }

        try {
            List<Object[]> serverData = auditLogRepository.serverUsageForSessions(sessionIds, since);
            List<Map<String, Object>> serverList = new ArrayList<>();
            for (Object[] row : serverData) {
                Map<String, Object> serverMap = new LinkedHashMap<>();
                serverMap.put("serverName", row[0]);
                serverMap.put("requestCount", ((Number) row[1]).longValue());
                serverMap.put("errorCount", ((Number) row[2]).longValue());
                serverList.add(serverMap);
            }
            result.put("serverUsage", serverList);
        } catch (Exception e) {
            log.debug("Failed to compute server usage for agent {}: {}", agentId, e.getMessage());
            result.put("serverUsage", List.of());
        }

        try {
            List<Object[]> errorData = auditLogRepository.errorBreakdownForSessions(sessionIds, since);
            List<Map<String, Object>> errorList = new ArrayList<>();
            for (Object[] row : errorData) {
                Map<String, Object> errorMap = new LinkedHashMap<>();
                errorMap.put("errorCode", row[0]);
                errorMap.put("errorMessage", row[1]);
                errorMap.put("count", ((Number) row[2]).longValue());
                errorList.add(errorMap);
            }
            result.put("errorBreakdown", errorList);
        } catch (Exception e) {
            log.debug("Failed to compute error breakdown for agent {}: {}", agentId, e.getMessage());
            result.put("errorBreakdown", List.of());
        }

        return result;
    }
}
