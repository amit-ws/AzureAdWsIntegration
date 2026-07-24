package com.ws.wsAgenticSecurityGateway.audit.service;

import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.dto.AgentActivity;
import com.ws.wsAgenticSecurityGateway.audit.dto.IdentityGraph;
import com.ws.wsAgenticSecurityGateway.audit.entity.McpAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.entity.PdpAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.repository.GatewayAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.audit.repository.GatewayAuditLogSpecification;
import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AuditQueryService {

    private final GatewayAuditLogRepository auditRepo;
    private final PdpAuditLogRepository pdpAuditRepo;

    public AuditQueryService(GatewayAuditLogRepository auditRepo,
                             PdpAuditLogRepository pdpAuditRepo) {
        this.auditRepo = auditRepo;
        this.pdpAuditRepo = pdpAuditRepo;
    }

    public Page<McpAuditLog> queryLogs(AuditModule module, AuditEventType eventType,
                                        AuditStatus status, AuditSeverity severity,
                                        String serverName, String capabilityName,
                                        String correlationId, String traceId, String sessionId,
                                        String agentName, String tokenType,
                                        String userIdentity, String sourceIp,
                                        String search,
                                        LocalDateTime fromDate, LocalDateTime toDate,
                                        PageRequest pageRequest) {
        var spec = GatewayAuditLogSpecification.build(
                module, eventType, status, severity,
                serverName, capabilityName, correlationId, traceId, sessionId,
                agentName, tokenType, userIdentity, sourceIp,
                search, fromDate, toDate, TenantContext.get());
        return auditRepo.findAll(spec, pageRequest);
    }

    public Optional<McpAuditLog> findById(UUID id) {
        return auditRepo.findById(id)
                .filter(entry -> {
                    String tenant = TenantContext.get();
                    return tenant == null || tenant.equals(entry.getWsTenantName());
                });
    }

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

    /**
     * The full lifecycle of a request by {@code trace_id} — the umbrella that spans every leg (and every
     * {@code correlationId}) of the request, single- or multi-hop. Mirrors {@link #getCorrelationChain} but
     * trace-scoped, bridging to PDP-only records (which carry no trace_id) via the correlation ids seen.
     */
    public List<McpAuditLog> getTraceChain(String traceId) {
        List<McpAuditLog> records = new ArrayList<>(auditRepo.findByTraceId(traceId));

        Set<String> correlationIds = records.stream()
                .map(McpAuditLog::getCorrelationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> existingPdpKeys = records.stream()
                .filter(r -> r.getModule() == AuditModule.PDP
                        && (r.getEventType() == AuditEventType.PDP_EVALUATION_REQUESTED
                            || r.getEventType() == AuditEventType.PDP_DECISION_RENDERED))
                .map(r -> r.getCorrelationId() + "|" + r.getEventType())
                .collect(Collectors.toSet());
        for (String corrId : correlationIds) {
            for (PdpAuditLog pdp : pdpAuditRepo.findByCorrelationId(corrId)) {
                if (!existingPdpKeys.contains(corrId + "|" + pdp.getEventType())) {
                    records.add(toChainEntry(pdp));
                }
            }
        }

        records.sort(Comparator
                .comparing(McpAuditLog::getEventSequence,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(McpAuditLog::getTimestamp));
        return records;
    }

    /**
     * Roll up an agent's audit events into per-request "activities" (one per {@code traceId}), newest first.
     * This is the transport-agnostic replacement for the session list: it works for stateless requests (which
     * have no session row) and surfaces the human each request acted for. Paginated over distinct traces.
     */
    public List<AgentActivity> getAgentActivities(String agentKey, int page, int size) {
        if (agentKey == null || agentKey.isBlank()) {
            return List.of();
        }
        List<String> traceIds = auditRepo.findDistinctTraceIdsByAgentKey(
                agentKey, PageRequest.of(Math.max(0, page), Math.max(1, size)));
        if (traceIds.isEmpty()) {
            return List.of();
        }

        // Group all events of the paged traces, preserving the (newest-first) trace order.
        Map<String, List<McpAuditLog>> byTrace = new LinkedHashMap<>();
        traceIds.forEach(tid -> byTrace.put(tid, new ArrayList<>()));
        for (McpAuditLog row : auditRepo.findByTraceIdInOrderByTimestampAsc(traceIds)) {
            List<McpAuditLog> bucket = byTrace.get(row.getTraceId());
            if (bucket != null) {
                bucket.add(row);
            }
        }

        List<AgentActivity> activities = new ArrayList<>(byTrace.size());
        for (Map.Entry<String, List<McpAuditLog>> e : byTrace.entrySet()) {
            if (!e.getValue().isEmpty()) {
                activities.add(summarizeActivity(e.getKey(), e.getValue()));
            }
        }
        return activities;
    }

    /** Collapse one trace's events (sorted oldest-first) into a single activity summary. */
    private AgentActivity summarizeActivity(String traceId, List<McpAuditLog> events) {
        String sessionId = firstNonNull(events, McpAuditLog::getSessionId);
        String transport = (sessionId != null && sessionId.startsWith("stateless-")) ? "STATELESS" : "SESSION";
        LocalDateTime startedAt = events.get(0).getTimestamp();
        LocalDateTime endedAt = events.get(events.size() - 1).getTimestamp();

        Set<String> tools = new LinkedHashSet<>();
        boolean denied = false, errored = false, allowed = false;
        for (McpAuditLog ev : events) {
            if (ev.getCapabilityName() != null) {
                tools.add(ev.getCapabilityName());
            }
            AuditEventType et = ev.getEventType();
            if (et == AuditEventType.PDP_DECISION_RENDERED) {
                if (ev.getStatus() == AuditStatus.DENIED) {
                    denied = true;
                } else if (ev.getStatus() == AuditStatus.SUCCESS) {
                    allowed = true;
                }
            } else if (et == AuditEventType.CAPABILITY_ACCESS_DENIED) {
                denied = true;
            } else if (et == AuditEventType.ORCHESTRATION_ERROR || et == AuditEventType.SYSTEM_ERROR) {
                errored = true;
            } else if (et == AuditEventType.STS_TOKEN_MINTED) {
                allowed = true; // token minted ⇒ policy was passed
            }
        }
        String outcome = denied ? "DENIED" : errored ? "ERROR" : allowed ? "ALLOWED" : "UNKNOWN";

        return new AgentActivity(
                traceId,
                sessionId,
                transport,
                startedAt,
                endedAt,
                firstNonNull(events, McpAuditLog::getAgentName),
                firstNonNull(events, McpAuditLog::getAgentClientId),
                firstNonNull(events, McpAuditLog::getHumanUserId),
                firstNonNull(events, McpAuditLog::getUserIdentity),
                firstNonNull(events, McpAuditLog::getTokenType),
                firstNonNull(events, McpAuditLog::getWsTenantName),
                new ArrayList<>(tools),
                firstNonNull(events, McpAuditLog::getServerName),
                outcome,
                events.size());
    }

    private static <T> T firstNonNull(List<McpAuditLog> events, java.util.function.Function<McpAuditLog, T> getter) {
        for (McpAuditLog ev : events) {
            T v = getter.apply(ev);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    // ── Identity graph ────────────────────────────────────────────────────────────────────────
    // Who acts through whom, and what they invoke — a read-model built entirely from audit rows.

    /**
     * Build the identity graph (human → agent → tool) from the audit trail, tenant-scoped, over the last
     * {@code hours} (null ⇒ effectively all-time). Nodes carry a request weight for sizing; the agent→tool
     * links carry allow/deny counts. Node/link counts are DISTINCT-trace based, so they read as "requests".
     */
    public IdentityGraph getIdentityGraph(Integer hours) {
        String tenant = TenantContext.get();
        int windowHours = (hours != null && hours > 0) ? hours : 24 * 365; // ~1y default ≈ all-time
        LocalDateTime since = LocalDateTime.now().minusHours(windowHours);

        Map<String, String[]> nodeMeta = new LinkedHashMap<>();   // id -> [type, label, sublabel]
        Map<List<String>, long[]> linkAcc = new LinkedHashMap<>(); // [source, type, target] -> [count, allowed, denied]

        // human → agent
        for (Object[] r : auditRepo.aggregateHumanAgentEdges(tenant, since)) {
            String userIdentity = (String) r[0];
            String humanUserId = (String) r[1];
            String agentName = (String) r[2];
            String agentClientId = (String) r[3];
            long count = ((Number) r[4]).longValue();

            String humanLabel = userIdentity != null ? userIdentity : humanUserId;
            String humanId = "human:" + humanLabel;
            String agentKey = agentClientId != null ? agentClientId : agentName;
            String agentId = "agent:" + agentKey;

            nodeMeta.putIfAbsent(humanId, new String[]{"HUMAN", humanLabel, "human"});
            nodeMeta.putIfAbsent(agentId, new String[]{"AGENT", agentName != null ? agentName : agentKey, agentClientId});
            accumulateLink(linkAcc, humanId, "acts-through", agentId, count, 0, 0);
        }

        // agent → tool
        for (Object[] r : auditRepo.aggregateAgentToolEdges(AuditEventType.PDP_DECISION_RENDERED, tenant, since)) {
            String agentName = (String) r[0];
            String agentClientId = (String) r[1];
            String capability = (String) r[2];
            String serverName = (String) r[3];
            AuditStatus status = (AuditStatus) r[4];
            long count = ((Number) r[5]).longValue();

            String agentKey = agentClientId != null ? agentClientId : agentName;
            String agentId = "agent:" + agentKey;
            String toolId = "tool:" + capability;

            nodeMeta.putIfAbsent(agentId, new String[]{"AGENT", agentName != null ? agentName : agentKey, agentClientId});
            nodeMeta.putIfAbsent(toolId, new String[]{"TOOL", capability, serverName});
            long allowed = status == AuditStatus.SUCCESS ? count : 0;
            long denied = status == AuditStatus.DENIED ? count : 0;
            accumulateLink(linkAcc, agentId, "invoked", toolId, count, allowed, denied);
        }

        // Node weight = number of requests the node actually owns. Weight each node by the leg it owns,
        // NOT by both endpoints of every edge (which would double-count agents: their human→agent leg AND
        // their agent→tool leg). Humans own the acts-through leg they initiate; agents own the invoked leg
        // they make; tools own the invoked leg that lands on them.
        Map<String, Long> weight = new HashMap<>();
        List<IdentityGraph.Link> links = new ArrayList<>(linkAcc.size());
        for (Map.Entry<List<String>, long[]> e : linkAcc.entrySet()) {
            List<String> k = e.getKey();
            String source = k.get(0), type = k.get(1), target = k.get(2);
            long[] v = e.getValue();
            links.add(new IdentityGraph.Link(source, target, type, v[0], v[1], v[2]));
            if ("invoked".equals(type)) {
                weight.merge(source, v[0], Long::sum); // agent — the request it made
                weight.merge(target, v[0], Long::sum); // tool — the request that hit it
            } else {
                weight.merge(source, v[0], Long::sum); // human — the request it initiated
            }
        }

        List<IdentityGraph.Node> nodes = new ArrayList<>(nodeMeta.size());
        for (Map.Entry<String, String[]> e : nodeMeta.entrySet()) {
            String[] m = e.getValue();
            nodes.add(new IdentityGraph.Node(e.getKey(), m[0], m[1], m[2], weight.getOrDefault(e.getKey(), 0L)));
        }
        return new IdentityGraph(nodes, links);
    }

    private static void accumulateLink(Map<List<String>, long[]> acc, String source, String type, String target,
                                       long count, long allowed, long denied) {
        long[] v = acc.computeIfAbsent(List.of(source, type, target), k -> new long[3]);
        v[0] += count;
        v[1] += allowed;
        v[2] += denied;
    }

    public Map<String, Object> getStats(LocalDateTime since) {
        String tenant = TenantContext.get();
        Map<String, Object> stats = new LinkedHashMap<>();

        long errorCount;
        Double avgDuration;
        Map<String, Long> byModule = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> bySeverity = new LinkedHashMap<>();

        if (tenant != null) {
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

    public Page<McpAuditLog> getSessionTimeline(String sessionId, PageRequest pageRequest) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return auditRepo.findBySessionIdAndWsTenantNameOrderByTimestampDesc(sessionId, tenant, pageRequest);
        }
        return auditRepo.findBySessionIdOrderByTimestampDesc(sessionId, pageRequest);
    }

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
