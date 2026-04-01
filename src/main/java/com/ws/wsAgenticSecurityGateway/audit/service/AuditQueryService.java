package com.ws.wsAgenticSecurityGateway.audit.service;

import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.entity.McpAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.entity.PdpAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.repository.McpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.audit.repository.McpAuditLogSpecification;
import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service layer for audit log READ operations.
 *
 * <p>Owns {@link McpAuditLogRepository} and {@link PdpAuditLogRepository}
 * for read-only queries. Write operations remain in {@link McpAuditService}.
 *
 * <p>Consumers: AuditController, AgentController, DashboardController, HumanUserService.
 */
@Service
@Slf4j
public class AuditQueryService {

    private final McpAuditLogRepository auditRepo;
    private final PdpAuditLogRepository pdpAuditRepo;

    public AuditQueryService(McpAuditLogRepository auditRepo,
                             PdpAuditLogRepository pdpAuditRepo) {
        this.auditRepo = auditRepo;
        this.pdpAuditRepo = pdpAuditRepo;
    }

    // ════════════════════════════════════════════════════════════════════
    //  PAGINATED FILTERED QUERY
    // ════════════════════════════════════════════════════════════════════

    public Page<McpAuditLog> queryLogs(AuditModule module, AuditEventType eventType,
                                        AuditStatus status, AuditSeverity severity,
                                        String serverName, String capabilityName,
                                        String correlationId, String sessionId,
                                        String agentName, String tokenType,
                                        String userIdentity, String sourceIp,
                                        String search,
                                        LocalDateTime fromDate, LocalDateTime toDate,
                                        PageRequest pageRequest) {
        var spec = McpAuditLogSpecification.build(
                module, eventType, status, severity,
                serverName, capabilityName, correlationId, sessionId,
                agentName, tokenType, userIdentity, sourceIp,
                search, fromDate, toDate, TenantContext.get());
        return auditRepo.findAll(spec, pageRequest);
    }

    // ════════════════════════════════════════════════════════════════════
    //  SINGLE RECORD + CORRELATION CHAIN
    // ════════════════════════════════════════════════════════════════════

    public Optional<McpAuditLog> findById(UUID id) {
        return auditRepo.findById(id)
                .filter(entry -> {
                    String tenant = TenantContext.get();
                    return tenant == null || tenant.equals(entry.getWsTenantName());
                });
    }

    /**
     * Build full invocation chain by merging MCP + PDP audit logs.
     */
    public List<McpAuditLog> getCorrelationChain(String correlationId) {
        List<McpAuditLog> records = new ArrayList<>(auditRepo.findByCorrelationId(correlationId));

        Set<AuditEventType> existingPdpTypes = records.stream()
                .filter(r -> r.getModule() == AuditModule.PDP
                        && (r.getEventType() == AuditEventType.PDP_EVALUATION_REQUESTED
                            || r.getEventType() == AuditEventType.PDP_DECISION_RENDERED))
                .map(McpAuditLog::getEventType)
                .collect(Collectors.toSet());

        List<PdpAuditLog> pdpRecords = pdpAuditRepo.findByCorrelationId(correlationId);
        for (PdpAuditLog pdp : pdpRecords) {
            if (!existingPdpTypes.contains(pdp.getEventType())) {
                records.add(toChainEntry(pdp));
            }
        }

        records.sort(Comparator
                .comparing(McpAuditLog::getEventSequence,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(McpAuditLog::getTimestamp));
        return records;
    }

    // ════════════════════════════════════════════════════════════════════
    //  STATS
    // ════════════════════════════════════════════════════════════════════

    public Map<String, Object> getStats(LocalDateTime since) {
        String tenant = TenantContext.get();
        Map<String, Object> stats = new LinkedHashMap<>();

        long errorCount;
        Double avgDuration;
        Map<String, Long> byModule = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> bySeverity = new LinkedHashMap<>();

        if (tenant != null) {
            // Use countByTimestampAfterAndWsTenantName with a very old date as proxy for "count all"
            // There's no countByWsTenantName, so we use the spec-based query approach
            long totalEvents = auditRepo.countByTimestampAfterAndWsTenantName(
                    LocalDateTime.of(2000, 1, 1, 0, 0), tenant);
            errorCount = auditRepo.countByStatusAndWsTenantName(AuditStatus.ERROR, tenant)
                    + auditRepo.countByStatusAndWsTenantName(AuditStatus.FAILURE, tenant);
            avgDuration = since != null
                    ? auditRepo.findAverageDurationMsSinceByTenant(since, tenant)
                    : auditRepo.findAverageDurationMsByTenant(tenant);
            stats.put("totalEvents", totalEvents);
            stats.put("errorCount", errorCount);
            stats.put("errorRate", totalEvents > 0
                    ? Math.round((double) errorCount / totalEvents * 10000.0) / 100.0
                    : 0.0);

            for (AuditModule m : AuditModule.values()) {
                long count = auditRepo.countByModuleAndWsTenantName(m, tenant);
                if (count > 0) byModule.put(m.name(), count);
            }
            for (AuditStatus s : AuditStatus.values()) {
                long count = auditRepo.countByStatusAndWsTenantName(s, tenant);
                if (count > 0) byStatus.put(s.name(), count);
            }
            for (AuditSeverity s : AuditSeverity.values()) {
                long count = auditRepo.countBySeverityAndWsTenantName(s, tenant);
                if (count > 0) bySeverity.put(s.name(), count);
            }
        } else {
            long totalEvents = auditRepo.count();
            errorCount = auditRepo.countByStatus(AuditStatus.ERROR)
                    + auditRepo.countByStatus(AuditStatus.FAILURE);
            avgDuration = since != null
                    ? auditRepo.findAverageDurationMsSince(since)
                    : auditRepo.findAverageDurationMs();
            stats.put("totalEvents", totalEvents);
            stats.put("errorCount", errorCount);
            stats.put("errorRate", totalEvents > 0
                    ? Math.round((double) errorCount / totalEvents * 10000.0) / 100.0
                    : 0.0);

            for (AuditModule m : AuditModule.values()) {
                long count = auditRepo.countByModule(m);
                if (count > 0) byModule.put(m.name(), count);
            }
            for (AuditStatus s : AuditStatus.values()) {
                long count = auditRepo.countByStatus(s);
                if (count > 0) byStatus.put(s.name(), count);
            }
            for (AuditSeverity s : AuditSeverity.values()) {
                long count = auditRepo.countBySeverity(s);
                if (count > 0) bySeverity.put(s.name(), count);
            }
        }

        stats.put("avgDurationMs", avgDuration != null ? Math.round(avgDuration * 10.0) / 10.0 : 0.0);
        stats.put("byModule", byModule);
        stats.put("byStatus", byStatus);
        stats.put("bySeverity", bySeverity);

        return stats;
    }

    public List<Map<String, Object>> getTimeline(int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        String tenant = TenantContext.get();
        List<Object[]> raw = tenant != null
                ? auditRepo.countByHourSinceByTenant(since, tenant)
                : auditRepo.countByHourSince(since);

        return raw.stream().map(row -> {
            Map<String, Object> point = new LinkedHashMap<>();
            if (row[0] != null) {
                point.put("timestamp", row[0].toString());
            }
            point.put("count", ((Number) row[1]).longValue());
            return point;
        }).collect(Collectors.toList());
    }

    public Map<String, Object> getFilterValues() {
        String tenant = TenantContext.get();
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("modules", Arrays.stream(AuditModule.values())
                .map(Enum::name).collect(Collectors.toList()));
        filters.put("eventTypes", Arrays.stream(AuditEventType.values())
                .map(Enum::name).collect(Collectors.toList()));
        filters.put("statuses", Arrays.stream(AuditStatus.values())
                .map(Enum::name).collect(Collectors.toList()));
        filters.put("severities", Arrays.stream(AuditSeverity.values())
                .map(Enum::name).collect(Collectors.toList()));
        if (tenant != null) {
            filters.put("serverNames", auditRepo.findDistinctServerNamesByTenant(tenant));
            filters.put("capabilityNames", auditRepo.findDistinctCapabilityNamesByTenant(tenant));
            filters.put("agentNames", auditRepo.findDistinctAgentNamesByTenant(tenant));
            filters.put("userIdentities", auditRepo.findDistinctUserIdentitiesByTenant(tenant));
        } else {
            filters.put("serverNames", auditRepo.findDistinctServerNames());
            filters.put("capabilityNames", auditRepo.findDistinctCapabilityNames());
            filters.put("agentNames", auditRepo.findDistinctAgentNames());
            filters.put("userIdentities", auditRepo.findDistinctUserIdentities());
        }
        filters.put("tokenTypes", List.of("HUMAN_DELEGATED", "AUTOMATED_AGENT"));
        return filters;
    }

    // ════════════════════════════════════════════════════════════════════
    //  DASHBOARD QUERIES
    // ════════════════════════════════════════════════════════════════════

    public long countRecentEvents(LocalDateTime since) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return auditRepo.countByTimestampAfterAndWsTenantName(since, tenant);
        }
        return auditRepo.countByTimestampAfter(since);
    }

    public long countRecentErrors(LocalDateTime since) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return auditRepo.countByStatusAndTimestampAfterAndWsTenantName(AuditStatus.ERROR, since, tenant)
                    + auditRepo.countByStatusAndTimestampAfterAndWsTenantName(AuditStatus.FAILURE, since, tenant);
        }
        return auditRepo.countByStatusAndTimestampAfter(AuditStatus.ERROR, since)
                + auditRepo.countByStatusAndTimestampAfter(AuditStatus.FAILURE, since);
    }

    public Double findAverageDurationSince(LocalDateTime since) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return auditRepo.findAverageDurationMsSinceByTenant(since, tenant);
        }
        return auditRepo.findAverageDurationMsSince(since);
    }

    public List<Object[]> findLatencyPercentilesSince(LocalDateTime since) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return auditRepo.findLatencyPercentilesSinceByTenant(since, tenant);
        }
        return auditRepo.findLatencyPercentilesSince(since);
    }

    public List<Object[]> findLastActivityPerServer() {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return auditRepo.findLastActivityPerServerByTenant(tenant);
        }
        return auditRepo.findLastActivityPerServer();
    }

    public long countByModuleAndTimestampAfter(AuditModule module, LocalDateTime since) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return auditRepo.countByModuleAndTimestampAfterAndWsTenantName(module, since, tenant);
        }
        return auditRepo.countByModuleAndTimestampAfter(module, since);
    }

    // ════════════════════════════════════════════════════════════════════
    //  SESSION TIMELINE (for AgentController)
    // ════════════════════════════════════════════════════════════════════

    public Page<McpAuditLog> getSessionTimeline(String sessionId, PageRequest pageRequest) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return auditRepo.findBySessionIdAndWsTenantNameOrderByTimestampDesc(sessionId, tenant, pageRequest);
        }
        return auditRepo.findBySessionIdOrderByTimestampDesc(sessionId, pageRequest);
    }

    // ════════════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ════════════════════════════════════════════════════════════════════

    McpAuditLog toChainEntry(PdpAuditLog pdp) {
        return McpAuditLog.builder()
                .id(pdp.getId())
                .eventType(pdp.getEventType())
                .module(AuditModule.PDP)
                .status(pdp.getStatus())
                .severity(pdp.getSeverity())
                .correlationId(pdp.getCorrelationId())
                .agentName(pdp.getPdpSubject())
                .capabilityName(pdp.getPdpResource())
                .mcpMethod(pdp.getPdpAction())
                .capabilityType(pdp.getPdpDecision())
                .durationMs(pdp.getDurationMs())
                .requestPayload(pdp.getPdpContext())
                .timestamp(pdp.getTimestamp())
                .build();
    }
}
