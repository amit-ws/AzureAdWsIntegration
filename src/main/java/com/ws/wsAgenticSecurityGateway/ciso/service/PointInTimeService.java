package com.ws.wsAgenticSecurityGateway.ciso.service;

import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.DecisionEvent;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PointInTimeEvents;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PointInTimeSnapshot;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PointInTimeSnapshot.AgentSnapshot;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PointInTimeSnapshot.PolicyInForce;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PointInTimeSnapshot.Summary;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.repository.GatewayPolicyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reconstructs governance as it was <em>observed</em> over a past window, replayed from the timestamped decision
 * ledger. {@link #getSnapshot} is the overview (counts + a small evidence sample); {@link #getEvents} is the
 * paginated forensic drill-down. Observed, not declared — the gateway doesn't version policies. Read-only.
 */
@Service
@Slf4j
public class PointInTimeService {

    private static final int SAMPLE_SIZE = 10;        // events embedded in the overview for immediate context
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 500;

    private final PdpAuditLogRepository pdpRepo;
    private final GatewayPolicyRepository policyRepo;

    public PointInTimeService(PdpAuditLogRepository pdpRepo, GatewayPolicyRepository policyRepo) {
        this.pdpRepo = pdpRepo;
        this.policyRepo = policyRepo;
    }

    // ── Overview ─────────────────────────────────────────────────────────────────

    /**
     * @param fromReq window start (inclusive); defaults to the ledger's first day.
     * @param toReq   window end (inclusive); defaults to today. For "as of date T" pass {@code toReq = T}.
     */
    public PointInTimeSnapshot getSnapshot(LocalDate fromReq, LocalDate toReq) {
        String tenant = TenantContext.get();
        Window w = resolveWindow(tenant, fromReq, toReq);

        // Enforcement outcomes.
        long decisions = 0, allowed = 0, denied = 0, defaultDeny = 0, forbidDeny = 0;
        for (Object[] r : pdpRepo.windowEnforcement(tenant, w.fromTs, w.toTs)) {
            String decision = (String) r[0];
            String basis = r[1] == null ? "" : (String) r[1];
            long c = num(r[2]);
            decisions += c;
            if ("ALLOW".equalsIgnoreCase(decision)) {
                allowed += c;
            } else if ("DENY".equalsIgnoreCase(decision)) {
                denied += c;
                if ("DEFAULT_DENY".equals(basis)) defaultDeny += c;
                else if ("POLICY_MATCH".equals(basis)) forbidDeny += c;
            }
        }

        // Per-agent footprint.
        List<AgentSnapshot> agents = pdpRepo.windowAgentActivity(tenant, w.fromTs, w.toTs).stream()
                .map(r -> new AgentSnapshot((String) r[0], num(r[1]), num(r[2]), num(r[3]), (int) num(r[4])))
                .toList();

        // Effective policies (observed deciding), compared to their current state.
        Map<String, GatewayPolicyEntity> byCedarId = new LinkedHashMap<>();
        for (GatewayPolicyEntity p : policyRepo.findAllByWsTenantName(tenant)) {
            if (p.getCedarPolicyId() != null) byCedarId.putIfAbsent(p.getCedarPolicyId(), p);
        }
        List<PolicyInForce> effectivePolicies = pdpRepo.windowPolicyActivity(tenant, w.fromTs, w.toTs).stream()
                .map(r -> {
                    String pid = (String) r[0];
                    GatewayPolicyEntity p = byCedarId.get(pid);
                    return new PolicyInForce(pid, num(r[1]), p != null, p != null && Boolean.TRUE.equals(p.getEnabled()));
                })
                .toList();

        // Accountability roll-up.
        long govEvals = 0, accountable = 0;
        int distinctHumans = 0;
        List<Object[]> acc = pdpRepo.windowAccountability(tenant, w.fromTs, w.toTs);
        if (!acc.isEmpty() && acc.get(0)[0] != null) {
            Object[] a = acc.get(0);
            govEvals = num(a[0]);
            accountable = num(a[1]);
            distinctHumans = (int) num(a[2]);
        }
        long unrooted = govEvals - accountable;

        // A small evidence sample so the overview alone tells a story (full drill-down via getEvents).
        List<DecisionEvent> recentEvents = pdpRepo.windowEvents(tenant, w.fromTs, w.toTs, "", "", SAMPLE_SIZE, 0)
                .stream().map(PointInTimeService::toEvent).toList();

        Summary summary = new Summary(decisions, allowed, denied, defaultDeny, forbidDeny, agents.size(),
                govEvals, distinctHumans, accountable, unrooted, effectivePolicies.size());

        return new PointInTimeSnapshot(tenant, LocalDateTime.now(), w.from, w.to, w.ledgerStart, w.ledgerEnd,
                summary, effectivePolicies, agents, recentEvents, snapshotNotes(w, decisions));
    }

    // ── Evidence drill-down ──────────────────────────────────────────────────────

    /** The individual decisions in the window, newest first, paginated and optionally filtered by agent + outcome. */
    public PointInTimeEvents getEvents(LocalDate fromReq, LocalDate toReq, String agentId, String decision,
                                       int page, int size) {
        String tenant = TenantContext.get();
        Window w = resolveWindow(tenant, fromReq, toReq);
        String agent = agentId == null ? "" : agentId.trim();
        String dec = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        int pageNum = Math.max(0, page);
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int offset = (int) Math.min((long) pageNum * pageSize, Integer.MAX_VALUE);   // guard int overflow on deep pages

        long total = pdpRepo.windowEventsCount(tenant, w.fromTs, w.toTs, agent, dec);
        List<DecisionEvent> events = pdpRepo.windowEvents(tenant, w.fromTs, w.toTs, agent, dec, pageSize, offset)
                .stream().map(PointInTimeService::toEvent).toList();
        boolean hasNext = ((long) pageNum + 1) * pageSize < total;

        List<String> notes = new ArrayList<>();
        notes.add("Each row is one recorded authorization decision; the human is the verified person behind the "
                + "request (blank for a system or anonymous caller).");
        if (w.ledgerStart != null && fromReq != null && fromReq.isBefore(w.ledgerStart)) {
            notes.add("The selected window begins before the earliest available audit history (" + w.ledgerStart + ").");
        }
        return new PointInTimeEvents(tenant, LocalDateTime.now(), w.from, w.to,
                agent.isEmpty() ? null : agent, dec.isEmpty() ? null : dec,
                pageNum, pageSize, total, events.size(), hasNext, events, notes);
    }

    // ── Window resolution + helpers ──────────────────────────────────────────────

    private record Window(LocalDate from, LocalDate to, LocalDate ledgerStart, LocalDate ledgerEnd,
                          LocalDateTime fromTs, LocalDateTime toTs) {}

    private Window resolveWindow(String tenant, LocalDate fromReq, LocalDate toReq) {
        LocalDate ledgerStart = null, ledgerEnd = null;
        List<Object[]> span = pdpRepo.ledgerSpan(tenant);
        if (!span.isEmpty() && span.get(0)[0] != null) {
            ledgerStart = toLocalDate(span.get(0)[0]);
            ledgerEnd = toLocalDate(span.get(0)[1]);
        }
        LocalDate today = LocalDate.now();
        LocalDate to = toReq != null ? toReq : today;
        if (to.isAfter(today)) to = today;   // can't reconstruct the future (also guards to.plusDays overflow)
        // Default 'from' so the window never inverts: the ledger start, unless that is already after 'to'.
        LocalDate from = fromReq != null ? fromReq
                : (ledgerStart != null && !ledgerStart.isAfter(to) ? ledgerStart : to);
        if (from.isAfter(to)) from = to;   // defensive clamp — an explicit reversed window is rejected at the controller
        return new Window(from, to, ledgerStart, ledgerEnd, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    private static List<String> snapshotNotes(Window w, long decisions) {
        List<String> notes = new ArrayList<>();
        notes.add("Reconstructed from recorded authorization decisions — this reflects what was actually enforced "
                + "during the selected window, not policy configuration as it was written. Policy change history is "
                + "not yet retained, so a policy that existed but was never exercised does not appear here.");
        if (w.ledgerStart != null) {
            notes.add("Audit history is available from " + w.ledgerStart + " to " + w.ledgerEnd + ".");
            if (w.from.isBefore(w.ledgerStart)) {
                notes.add("The selected window begins before the earliest available audit history (" + w.ledgerStart
                        + "); no data exists before that date.");
            }
        }
        if (decisions == 0) {
            notes.add("No authorization decisions were recorded during the selected window.");
        }
        return notes;
    }

    private static DecisionEvent toEvent(Object[] r) {
        return new DecisionEvent(toDateTime(r[0]), (String) r[1], (String) r[2], (String) r[3],
                (String) r[4], (String) r[5], (String) r[6], (String) r[7]);
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (o instanceof LocalDateTime ldt) return ldt.toLocalDate();
        if (o instanceof LocalDate d) return d;
        return null;
    }

    private static LocalDateTime toDateTime(Object o) {
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (o instanceof LocalDateTime ldt) return ldt;
        return null;
    }
}
