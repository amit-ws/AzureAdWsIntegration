package com.ws.wsAgenticSecurityGateway.ciso.service;

import com.ws.wsAgenticSecurityGateway.audit.repository.GatewayAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentActivityTrail;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentActivityTrail.ActivityEntry;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentActivityTrail.TrailSummary;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reconstructs an agent's forensic activity trail from the PDP decision ledger. OUTBOUND = decisions where the
 * agent is the subject (what it did); INBOUND = decisions where the resource is one of the agent's own skills,
 * {@code <agent>.*} (what was asked of it). Correlation-stitched (the ledger carries no trace_id). Read-only.
 */
@Service
@Slf4j
public class AgentActivityTrailService {

    /** Timeline cap — the summary notes if this is hit. Generous so real tenants rarely truncate. */
    private static final int MAX_ENTRIES = 2000;

    private final PdpAuditLogRepository pdpRepo;
    private final GatewayAuditLogRepository auditRepo;

    public AgentActivityTrailService(PdpAuditLogRepository pdpRepo, GatewayAuditLogRepository auditRepo) {
        this.pdpRepo = pdpRepo;
        this.auditRepo = auditRepo;
    }

    public AgentActivityTrail getActivityTrail(String agentName) {
        String tenant = TenantContext.get();

        // OUTBOUND: the agent is the subject → actor is the agent itself.
        List<ActivityEntry> outbound = pdpRepo.agentOutboundActivity(tenant, agentName, MAX_ENTRIES).stream()
                .map(r -> entry(agentName, r))
                .toList();
        // INBOUND: someone invoked one of the agent's skills (<agent>.<skill>) → actor is the caller (the subject).
        List<ActivityEntry> inbound = pdpRepo.agentInboundActivity(tenant, agentName + ".%", MAX_ENTRIES).stream()
                .map(r -> entry((String) r[6], r))
                .toList();

        long humansServed = auditRepo.countDistinctHumansForAgent(tenant, agentName);

        Set<String> requests = new HashSet<>();
        outbound.forEach(e -> addIfPresent(requests, e.correlationId()));
        inbound.forEach(e -> addIfPresent(requests, e.correlationId()));

        int distinctResources = (int) outbound.stream().map(ActivityEntry::resource).distinct().count();
        int distinctCallers = (int) inbound.stream().map(ActivityEntry::actor).distinct().count();

        TrailSummary summary = new TrailSummary(
                outbound.size(), count(outbound, "ALLOW"), count(outbound, "DENY"),
                inbound.size(), count(inbound, "ALLOW"), count(inbound, "DENY"),
                distinctResources, distinctCallers, humansServed, requests.size());

        LocalDateTime[] span = span(outbound, inbound);
        return new AgentActivityTrail(
                agentName, tenant, LocalDateTime.now(),
                span[0] == null ? null : span[0].toLocalDate(),
                span[1] == null ? null : span[1].toLocalDate(),
                summary, outbound, inbound, notes(outbound.size(), inbound.size()));
    }

    /** Flatten the trail to CSV — one row per activity, both directions, for export. */
    public String toCsv(AgentActivityTrail p) {
        StringBuilder sb = new StringBuilder();
        sb.append("agent,tenant,generated_at,period_start,period_end\n");
        sb.append(row(p.agentName(), p.tenant(), str(p.generatedAt()), str(p.periodStart()), str(p.periodEnd())));
        sb.append('\n');
        sb.append("direction,at,actor,action,resource,decision,policy,correlation_id\n");
        for (ActivityEntry e : p.outbound()) appendEntry(sb, "OUTBOUND", e);
        for (ActivityEntry e : p.inbound()) appendEntry(sb, "INBOUND", e);
        return sb.toString();
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private static ActivityEntry entry(String actor, Object[] r) {
        return new ActivityEntry(asDateTime(r[0]), actor, (String) r[1], (String) r[2],
                (String) r[3], (String) r[4], (String) r[5]);
    }

    private static long count(List<ActivityEntry> entries, String decision) {
        return entries.stream().filter(e -> decision.equals(e.decision())).count();
    }

    private static void addIfPresent(Set<String> set, String v) {
        if (v != null && !v.isBlank()) set.add(v);
    }

    private static LocalDateTime[] span(List<ActivityEntry> a, List<ActivityEntry> b) {
        LocalDateTime min = null, max = null;
        for (List<ActivityEntry> list : List.of(a, b)) {
            for (ActivityEntry e : list) {
                if (e.at() == null) continue;
                if (min == null || e.at().isBefore(min)) min = e.at();
                if (max == null || e.at().isAfter(max)) max = e.at();
            }
        }
        return new LocalDateTime[]{ min, max };
    }

    private static List<String> notes(int outCount, int inCount) {
        List<String> notes = new ArrayList<>();
        notes.add("Both directions: OUTBOUND = actions this agent initiated; INBOUND = requests made to this agent's skills.");
        notes.add("Reconstructed from recorded authorization decisions and correlated by request.");
        if (outCount >= MAX_ENTRIES || inCount >= MAX_ENTRIES) {
            notes.add("Timeline truncated to the " + MAX_ENTRIES + " most recent actions per direction; "
                    + "summary counts reflect the shown entries.");
        }
        return notes;
    }

    private void appendEntry(StringBuilder sb, String direction, ActivityEntry e) {
        sb.append(row(direction, str(e.at()), e.actor(), e.action(), e.resource(),
                e.decision(), e.policy(), e.correlationId()));
    }

    private static String row(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csv(cells[i]));
        }
        return sb.append('\n').toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static LocalDateTime asDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime ldt) return ldt;
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }
}
