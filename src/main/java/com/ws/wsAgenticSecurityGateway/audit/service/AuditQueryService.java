package com.ws.wsAgenticSecurityGateway.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditSeverity;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.dto.AgentActivity;
import com.ws.wsAgenticSecurityGateway.audit.dto.AgentActivitySummary;
import com.ws.wsAgenticSecurityGateway.audit.dto.IdentityGraph;
import com.ws.wsAgenticSecurityGateway.audit.entity.GatewayAuditLog;
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

    public Page<GatewayAuditLog> queryLogs(AuditModule module, AuditEventType eventType,
                                        AuditStatus status, AuditSeverity severity,
                                        String serverName, String capabilityName,
                                        String correlationId, String traceId, String sessionId,
                                        String agentName, String tokenType,
                                        String userIdentity, String sourceIp,
                                        String search,
                                        LocalDateTime fromDate, LocalDateTime toDate,
                                        String protocol, String capabilityType,
                                        PageRequest pageRequest) {
        var spec = GatewayAuditLogSpecification.build(
                module, eventType, status, severity,
                serverName, capabilityName, correlationId, traceId, sessionId,
                agentName, tokenType, userIdentity, sourceIp,
                search, fromDate, toDate, TenantContext.get());
        // protocol + capabilityType columns exist on the row (A2A vs MCP, SKILL vs TOOL) — filter
        // case-insensitively so ?protocol=a2a and ?protocol=A2A both work.
        if (protocol != null && !protocol.isBlank()) {
            String p = protocol.trim().toUpperCase();
            spec = spec.and((root, q, cb) -> cb.equal(cb.upper(root.get("protocol")), p));
        }
        if (capabilityType != null && !capabilityType.isBlank()) {
            String ct = capabilityType.trim().toUpperCase();
            spec = spec.and((root, q, cb) -> cb.equal(cb.upper(root.get("capabilityType")), ct));
        }
        Page<GatewayAuditLog> page = auditRepo.findAll(spec, pageRequest);
        flagOboReceipts(page.getContent());
        return page;
    }

    /**
     * Mark every row whose leg ({@code correlationId}) minted an OBO token, so the dashboard shows the "OBO
     * Receipt" button on all events of that leg — not only the mint row. One batched query over the page's
     * correlation ids (already tenant-scoped by the page they came from).
     */
    private void flagOboReceipts(List<GatewayAuditLog> rows) {
        List<String> correlationIds = rows.stream()
                .map(GatewayAuditLog::getCorrelationId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (correlationIds.isEmpty()) {
            return;
        }
        Set<String> mintedLegs = new HashSet<>(
                auditRepo.findCorrelationIdsWithEventType(AuditEventType.STS_TOKEN_MINTED, correlationIds));
        for (GatewayAuditLog row : rows) {
            if (row.getCorrelationId() != null && mintedLegs.contains(row.getCorrelationId())) {
                row.setHasOboReceipt(true);
            }
        }
    }

    public Optional<GatewayAuditLog> findById(UUID id) {
        return auditRepo.findById(id)
                .filter(entry -> {
                    String tenant = TenantContext.get();
                    return tenant == null || tenant.equals(entry.getWsTenantName());
                });
    }

    public List<GatewayAuditLog> getCorrelationChain(String correlationId) {
        List<GatewayAuditLog> records = new ArrayList<>(auditRepo.findByCorrelationId(correlationId));

        Set<AuditEventType> existingPdpTypes = records.stream()
                .filter(r -> r.getModule() == AuditModule.PDP
                        && (r.getEventType() == AuditEventType.PDP_EVALUATION_REQUESTED
                            || r.getEventType() == AuditEventType.PDP_DECISION_RENDERED))
                .map(GatewayAuditLog::getEventType)
                .collect(Collectors.toSet());

        List<PdpAuditLog> pdpRecords = pdpAuditRepo.findByCorrelationId(correlationId);
        Map<AuditEventType, PdpAuditLog> pdpByType = new HashMap<>();
        for (PdpAuditLog pdp : pdpRecords) {
            pdpByType.putIfAbsent(pdp.getEventType(), pdp);
            if (!existingPdpTypes.contains(pdp.getEventType())) {
                records.add(toChainEntry(pdp));
            }
        }
        // Merge the authoritative deciding-policy + reason (from the pdp_audit_log ledger) onto the gateway PDP
        // timeline markers, which keep their eventSequence for ordering but don't persist that detail.
        enrichPdpRows(records, r -> pdpByType.get(r.getEventType()));

        records.sort(Comparator
                .comparing(GatewayAuditLog::getEventSequence,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(GatewayAuditLog::getTimestamp));
        return records;
    }

    /** Copy the ledger's {@code pdpPolicyId}/{@code pdpReason} onto each PDP chain row (display-only; the rows are
     *  detached query results, so this never writes back). {@code lookup} finds the matching ledger row. */
    private void enrichPdpRows(List<GatewayAuditLog> records,
                              java.util.function.Function<GatewayAuditLog, PdpAuditLog> lookup) {
        for (GatewayAuditLog r : records) {
            if (r.getModule() != AuditModule.PDP) {
                continue;
            }
            PdpAuditLog pdp = lookup.apply(r);
            if (pdp != null) {
                if (r.getPdpPolicyId() == null) r.setPdpPolicyId(pdp.getPdpPolicyId());
                if (r.getPdpReason() == null) r.setPdpReason(pdp.getPdpReason());
                if (r.getPdpDecision() == null) r.setPdpDecision(pdp.getPdpDecision());
            }
        }
    }

    /**
     * The full OBO (on-behalf-of) token receipt for a leg — the response payload of the
     * {@code STS_TOKEN_MINTED} event for the given {@code correlationId} (jti, aud, scope, ttl, act_chain,
     * issued/expiry, …). Empty when no OBO token was minted for that leg (e.g. an agent-provided or
     * config-based token was used instead).
     */
    public Optional<JsonNode> getOboReceipt(String correlationId) {
        return auditRepo.findByCorrelationId(correlationId).stream()
                .filter(r -> r.getEventType() == AuditEventType.STS_TOKEN_MINTED)
                .map(GatewayAuditLog::getResponsePayload)
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * The full lifecycle of a request by {@code trace_id} — the umbrella that spans every leg (and every
     * {@code correlationId}) of the request, single- or multi-hop. Mirrors {@link #getCorrelationChain} but
     * trace-scoped, bridging to PDP-only records (which carry no trace_id) via the correlation ids seen.
     */
    public List<GatewayAuditLog> getTraceChain(String traceId) {
        List<GatewayAuditLog> records = new ArrayList<>(auditRepo.findByTraceId(traceId));

        Set<String> correlationIds = records.stream()
                .map(GatewayAuditLog::getCorrelationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> existingPdpKeys = records.stream()
                .filter(r -> r.getModule() == AuditModule.PDP
                        && (r.getEventType() == AuditEventType.PDP_EVALUATION_REQUESTED
                            || r.getEventType() == AuditEventType.PDP_DECISION_RENDERED))
                .map(r -> r.getCorrelationId() + "|" + r.getEventType())
                .collect(Collectors.toSet());
        Map<String, PdpAuditLog> pdpByKey = new HashMap<>();
        for (String corrId : correlationIds) {
            for (PdpAuditLog pdp : pdpAuditRepo.findByCorrelationId(corrId)) {
                pdpByKey.putIfAbsent(corrId + "|" + pdp.getEventType(), pdp);
                if (!existingPdpKeys.contains(corrId + "|" + pdp.getEventType())) {
                    records.add(toChainEntry(pdp));
                }
            }
        }
        // Merge the authoritative deciding-policy + reason from the ledger onto the gateway PDP markers.
        enrichPdpRows(records, r -> r.getCorrelationId() == null
                ? null : pdpByKey.get(r.getCorrelationId() + "|" + r.getEventType()));

        records.sort(Comparator
                .comparing(GatewayAuditLog::getEventSequence,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(GatewayAuditLog::getTimestamp));
        return records;
    }

    /**
     * Roll up an agent's audit events into per-request "activities" (one per {@code traceId}), newest first.
     * This is the transport-agnostic replacement for the session list: it works for stateless requests (which
     * have no session row) and surfaces the human each request acted for. Paginated over distinct traces.
     */
    /**
     * The 360° activity summary for one agent — outbound calls it MADE + inbound calls it RECEIVED — rolled
     * up from PDP decision rows. Answers "what did this agent do": which peers (agents over A2A, servers over
     * MCP), which capabilities, how many calls, allowed vs denied, last seen.
     */
    public AgentActivitySummary getAgentActivitySummary(String agentKey) {
        List<AgentActivitySummary.Edge> outbound =
                mapActivityEdges(auditRepo.aggregateAgentOutbound(AuditEventType.PDP_DECISION_RENDERED, agentKey));
        List<AgentActivitySummary.Edge> inbound =
                mapActivityEdges(auditRepo.aggregateAgentInbound(AuditEventType.PDP_DECISION_RENDERED, agentKey));
        long calls = outbound.stream().mapToLong(AgentActivitySummary.Edge::calls).sum();
        long allowed = outbound.stream().mapToLong(AgentActivitySummary.Edge::allowed).sum();
        long denied = outbound.stream().mapToLong(AgentActivitySummary.Edge::denied).sum();
        int peers = (int) outbound.stream().map(AgentActivitySummary.Edge::peer).distinct().count();
        return new AgentActivitySummary(agentKey, outbound, inbound,
                new AgentActivitySummary.Totals(calls, allowed, denied, peers));
    }

    /** Merge raw [protocolMethod, peer, capability, decision, calls, lastAt] rows into rolled-up Edges,
     *  summing allowed/denied per (protocol, peer, capability). */
    private List<AgentActivitySummary.Edge> mapActivityEdges(List<Object[]> rows) {
        Map<String, long[]> counts = new LinkedHashMap<>();   // key -> [calls, allowed, denied]
        Map<String, Object[]> meta = new HashMap<>();         // key -> [protocol, peer, capability, lastAt]
        for (Object[] r : rows) {
            String protocol = protocolLabel(asStr(r[0]));
            String peer = asStr(r[1]);
            String capability = asStr(r[2]);
            String decision = asStr(r[3]);
            long count = r[4] == null ? 0L : ((Number) r[4]).longValue();
            LocalDateTime lastAt = (r[5] instanceof LocalDateTime lt) ? lt : null;
            boolean allowed = decision == null
                    || "ALLOW".equalsIgnoreCase(decision) || "SUCCESS".equalsIgnoreCase(decision);
            String key = protocol + " " + peer + " " + capability;
            long[] c = counts.computeIfAbsent(key, k -> new long[3]);
            c[0] += count;
            if (allowed) c[1] += count; else c[2] += count;
            Object[] m = meta.get(key);
            if (m == null || (lastAt != null && (m[3] == null || lastAt.isAfter((LocalDateTime) m[3])))) {
                meta.put(key, new Object[]{protocol, peer, capability, lastAt});
            }
        }
        List<AgentActivitySummary.Edge> edges = new ArrayList<>();
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            Object[] m = meta.get(e.getKey());
            long[] c = e.getValue();
            edges.add(new AgentActivitySummary.Edge(
                    (String) m[0], (String) m[1], (String) m[2], c[0], c[1], c[2], (LocalDateTime) m[3]));
        }
        edges.sort((a, b) -> Long.compare(b.calls(), a.calls()));
        return edges;
    }

    private static String protocolLabel(String method) {
        if (method == null) return "-";
        if ("skillInvocation".equalsIgnoreCase(method)) return "A2A";
        if ("toolCall".equalsIgnoreCase(method)) return "MCP";
        return method;
    }

    private static String asStr(Object o) {
        return o == null ? null : o.toString();
    }

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
        Map<String, List<GatewayAuditLog>> byTrace = new LinkedHashMap<>();
        traceIds.forEach(tid -> byTrace.put(tid, new ArrayList<>()));
        for (GatewayAuditLog row : auditRepo.findByTraceIdInOrderByTimestampAsc(traceIds)) {
            List<GatewayAuditLog> bucket = byTrace.get(row.getTraceId());
            if (bucket != null) {
                bucket.add(row);
            }
        }

        List<AgentActivity> activities = new ArrayList<>(byTrace.size());
        for (Map.Entry<String, List<GatewayAuditLog>> e : byTrace.entrySet()) {
            if (!e.getValue().isEmpty()) {
                activities.add(summarizeActivity(e.getKey(), e.getValue()));
            }
        }
        return activities;
    }

    /** Collapse one trace's events (sorted oldest-first) into a single activity summary. */
    private AgentActivity summarizeActivity(String traceId, List<GatewayAuditLog> events) {
        String sessionId = firstNonNull(events, GatewayAuditLog::getSessionId);
        String transport = (sessionId != null && sessionId.startsWith("stateless-")) ? "STATELESS" : "SESSION";
        LocalDateTime startedAt = events.get(0).getTimestamp();
        LocalDateTime endedAt = events.get(events.size() - 1).getTimestamp();

        // Split capabilities by their authoritative type (the gateway knows this — the FE must not
        // reverse-engineer it from name shapes): A2A skills arrive via skillInvocation, MCP tools via
        // toolCall/tools-call. Fall back to the name shape only when the protocol method is absent.
        Set<String> skillCaps = new LinkedHashSet<>();
        Set<String> mcpCaps = new LinkedHashSet<>();
        boolean denied = false, errored = false, allowed = false;
        for (GatewayAuditLog ev : events) {
            String cap = ev.getCapabilityName();
            if (cap != null && !cap.isBlank()) {
                (isSkillCapability(ev, cap) ? skillCaps : mcpCaps).add(cap);
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
                firstNonNull(events, GatewayAuditLog::getAgentName),
                firstNonNull(events, GatewayAuditLog::getAgentClientId),
                firstNonNull(events, GatewayAuditLog::getHumanUserId),
                firstNonNull(events, GatewayAuditLog::getUserIdentity),
                firstNonNull(events, GatewayAuditLog::getTokenType),
                firstNonNull(events, GatewayAuditLog::getWsTenantName),
                dedupeAliases(skillCaps),
                dedupeAliases(mcpCaps),
                firstNonNull(events, GatewayAuditLog::getServerName),
                outcome,
                events.size());
    }

    /**
     * Is this event's capability an A2A skill (vs. an MCP tool)? Prefer the authoritative protocol method
     * ({@code skillInvocation} ⇒ skill, {@code toolCall}/{@code tools/call} ⇒ tool); fall back to the name
     * shape (A2A skills are dot-named like {@code market-data.quote}; MCP tools are namespaced like
     * {@code alphavantage_GLOBAL_QUOTE}) when the method is absent.
     */
    private static boolean isSkillCapability(GatewayAuditLog ev, String cap) {
        String pm = ev.getProtocolMethod();
        if (pm != null) {
            String m = pm.toLowerCase();
            if (m.contains("skill")) return true;
            if (m.contains("tool")) return false;
        }
        return cap.contains(".");
    }

    /**
     * De-duplicate capability alias noise: the same call is logged under several name forms across a trace's
     * events (e.g. {@code quote} ⊂ {@code GLOBAL_QUOTE} ⊂ {@code alphavantage_GLOBAL_QUOTE}). Keep the most
     * qualified form by dropping any name that is a case-insensitive suffix of a longer kept name, then sort.
     */
    private static List<String> dedupeAliases(Collection<String> caps) {
        List<String> byLenDesc = caps.stream()
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        List<String> kept = new ArrayList<>();
        for (String c : byLenDesc) {
            String lc = c.toLowerCase();
            boolean aliasOfKept = kept.stream().anyMatch(k -> {
                String lk = k.toLowerCase();
                return !lk.equals(lc) && lk.endsWith(lc);
            });
            if (!aliasOfKept) {
                kept.add(c);
            }
        }
        kept.sort(String.CASE_INSENSITIVE_ORDER);
        return kept;
    }

    private static <T> T firstNonNull(List<GatewayAuditLog> events, java.util.function.Function<GatewayAuditLog, T> getter) {
        for (GatewayAuditLog ev : events) {
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

    public Page<GatewayAuditLog> getSessionTimeline(String sessionId, PageRequest pageRequest) {
        String tenant = TenantContext.get();
        if (tenant != null) {
            return auditRepo.findBySessionIdAndWsTenantNameOrderByTimestampDesc(sessionId, tenant, pageRequest);
        }
        return auditRepo.findBySessionIdOrderByTimestampDesc(sessionId, pageRequest);
    }

    /** Project a {@code pdp_audit_log} ledger row into a chain entry — the fallback used when no gateway PDP
     *  marker exists for a leg. Carries the decision + authoritative policy/reason for display. */
    GatewayAuditLog toChainEntry(PdpAuditLog pdp) {
        return GatewayAuditLog.builder()
                .id(pdp.getId())
                .eventType(pdp.getEventType())
                .module(AuditModule.PDP)
                .status(pdp.getStatus())
                .severity(pdp.getSeverity())
                .correlationId(pdp.getCorrelationId())
                .agentName(pdp.getPdpSubject())
                .capabilityName(pdp.getPdpResource())
                .protocolMethod(pdp.getPdpAction())
                .pdpDecision(pdp.getPdpDecision())
                .pdpReason(pdp.getPdpReason())
                .pdpPolicyId(pdp.getPdpPolicyId())
                .durationMs(pdp.getDurationMs())
                .requestPayload(pdp.getPdpContext())
                .timestamp(pdp.getTimestamp())
                .build();
    }
}
