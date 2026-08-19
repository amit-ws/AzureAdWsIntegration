package com.ws.wsAgenticSecurityGateway.ciso.service;

import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AccountabilityReport;
import com.ws.wsAgenticSecurityGateway.ciso.dto.DashboardOverview;
import com.ws.wsAgenticSecurityGateway.ciso.dto.DashboardOverview.Coverage;
import com.ws.wsAgenticSecurityGateway.ciso.dto.DashboardOverview.Kpi;
import com.ws.wsAgenticSecurityGateway.ciso.dto.DashboardOverview.Posture;
import com.ws.wsAgenticSecurityGateway.ciso.dto.DashboardOverview.PostureLine;
import com.ws.wsAgenticSecurityGateway.ciso.dto.DashboardOverview.SensitivitySlice;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PostureReport;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PriorityAction;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PriorityAction.ActionContext;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.Sensitivity;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.GatewayResponseClassificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Assembles the CISO → Dashboard executive views from real governed-traffic data. This service is orchestration:
 * it reuses the existing posture, accountability, and classification aggregates rather than re-deriving them, and
 * exposes them as the dashboard's widget payloads. Every value is a real count or a transparent ratio.
 *
 * <p>Stage 1 = the {@link #overview(String)} summary (KPI strip, posture, sensitivity mix, coverage).
 */
@Service
@Slf4j
public class CisoDashboardService {

    private static final List<String> SENSITIVE = List.of("CONFIDENTIAL", "RESTRICTED");

    /** How many distinct servers an agent must fan out across before it becomes a priority action. */
    private static final int MIN_FANOUT_SERVERS = 3;

    /**
     * Remediation owner by finding TYPE (not per-agent) — the finding type genuinely determines the responsible
     * team, and it needs no data the gateway doesn't have. Admin-overridable later; unmapped → "Unassigned".
     */
    private static final Map<String, String> OWNER_BY_FINDING = Map.of(
            "SENSITIVE_EXPOSURE", "Data Protection",
            "BROAD_FANOUT", "AI Platform",
            "UNROOTED_CHAIN", "Security Operations");

    private final PostureService postureService;
    private final AccountabilityService accountabilityService;
    private final GatewayResponseClassificationRepository classificationRepo;
    private final PdpAuditLogRepository pdpRepo;

    public CisoDashboardService(PostureService postureService,
                                AccountabilityService accountabilityService,
                                GatewayResponseClassificationRepository classificationRepo,
                                PdpAuditLogRepository pdpRepo) {
        this.postureService = postureService;
        this.accountabilityService = accountabilityService;
        this.classificationRepo = classificationRepo;
        this.pdpRepo = pdpRepo;
    }

    /** The dashboard overview for {@code window} (e.g. "24h", "7d", "30d"; default 24h). */
    public DashboardOverview overview(String windowRaw) {
        String tenant = TenantContext.get();
        String window = normalizeWindow(windowRaw);
        long hours = windowHours(window);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.minusHours(hours);
        LocalDateTime prevFrom = from.minusHours(hours);

        // ── KPI raw counts (windowed, with a prior-window baseline for the delta) ──
        long reqNow = decisions(tenant, from, now);
        long reqPrev = decisions(tenant, prevFrom, from);
        long sensNow = classificationRepo
                .countByWsTenantNameAndSensitivityInAndClassifiedAtBetween(tenant, SENSITIVE, from, now);
        long sensPrev = classificationRepo
                .countByWsTenantNameAndSensitivityInAndClassifiedAtBetween(tenant, SENSITIVE, prevFrom, from);
        long deniedNow = pdpRepo.countByPdpDecisionAndTimestampBetweenAndWsTenantName("DENY", from, now, tenant);
        long deniedPrev = pdpRepo.countByPdpDecisionAndTimestampBetweenAndWsTenantName("DENY", prevFrom, from, tenant);

        // ── Enforcement coverage (egress policy over classified capabilities/servers) ──
        long[] cov = firstRow(classificationRepo.enforcementCoverage(tenant), 6);
        int sensitiveCaps = (int) cov[0];
        int coveredSensitiveCaps = (int) cov[1];
        int servers = (int) cov[4];
        int coveredServers = (int) cov[5];
        int gaps = Math.max(0, sensitiveCaps - coveredSensitiveCaps);

        // ── PDP decision coverage (share decided by an explicit policy) ──
        long[] dc = firstRow(pdpRepo.policyDecisionCoverage(tenant), 5);
        long dTotal = dc[0];
        long dAttributed = dc[3];
        int attributedPct = dTotal > 0 ? (int) Math.round(100.0 * dAttributed / dTotal) : 0;

        AccountabilityReport acct = accountabilityService.getReport();
        int activeAgents = acct.summary().actingAgents();
        int riskyPrincipals = riskyPrincipalCount(tenant);

        List<Kpi> kpis = List.of(
                new Kpi("requests", "Governed requests", reqNow, reqPrev,
                        "PDP decisions (allow + deny) this window"),
                new Kpi("sensitive", "Sensitive events", sensNow, sensPrev,
                        "Responses classified Confidential or Restricted"),
                new Kpi("gaps", "Policy gaps", gaps, null,
                        "Sensitive capabilities with no egress enforcement"),
                new Kpi("denied", "Blocked / denied", deniedNow, deniedPrev,
                        "Requests denied by the policy decision point"),
                new Kpi("agents", "Active agents", activeAgents, null,
                        "Agents observed acting through the gateway"),
                new Kpi("risky", "Risky principals", riskyPrincipals, null,
                        "Humans / NHIs whose data footprint peaks at Restricted"));

        // ── Posture (reuse the real graded scorecard; scored checks become the narrative) ──
        PostureReport pr = postureService.getReport();
        List<PostureLine> lines = pr.checks().stream()
                .filter(c -> c.weight() > 0)
                .map(c -> new PostureLine(c.title(), c.status(), c.detail(), c.why()))
                .toList();
        Posture posture = new Posture(pr.score(), pr.grade(), pr.headline(),
                pr.criticalCount(), pr.warningCount(), pr.goodCount(), attributedPct, lines);

        // ── Sensitivity mix (cumulative classification posture) ──
        long classifiedTotal = classificationRepo.countByWsTenantName(tenant);
        List<SensitivitySlice> mix = sensitivityMix(tenant, classifiedTotal);

        Coverage coverage = new Coverage(attributedPct, sensitiveCaps, coveredSensitiveCaps,
                servers, coveredServers, gaps);

        return new DashboardOverview(tenant, now, window, kpis, posture, mix, classifiedTotal, coverage);
    }

    /**
     * The priority-actions queue (#69) — detected governance conditions ranked by blast radius + sensitivity, each
     * carrying a click-through context that seeds the policy assistant. Capped at the top 8. All detections real.
     */
    public List<PriorityAction> priorityActions() {
        String tenant = TenantContext.get();
        List<PriorityAction> out = new ArrayList<>();
        int seq = 0;

        // A) Sensitive data reached a capability with NO enforcing egress policy — the marquee finding.
        for (Object[] r : classificationRepo.sensitiveExposures(tenant)) {
            String capType = str(r[0]);
            String capName = str(r[1]);
            String server = str(r[2]);
            String serverId = str(r[3]);
            int peak = (int) asLong(r[4]);
            long exposures = asLong(r[5]);
            long roots = asLong(r[6]);
            long agents = asLong(r[7]);
            LocalDateTime last = asDateTime(r[8]);
            boolean critical = peak >= Sensitivity.RESTRICTED;
            String sensEnum = Sensitivity.labelOf(peak);
            String sensFriendly = friendly(sensEnum);
            String cap = capLabel(capType, capName);
            String seed = sensFriendly + " data reached " + cap + " on " + nz(server, "the server")
                    + " with no enforcing egress policy. Draft an access policy that denies or redacts this "
                    + "capability for the agents that call it.";
            out.add(new PriorityAction(
                    "pa-exp-" + (++seq), critical ? "CRITICAL" : "HIGH", "SENSITIVE_EXPOSURE",
                    sensFriendly + " data reached " + (capName == null ? "a capability" : capName)
                            + " with no enforcing policy",
                    cap + " on " + nz(server, "an MCP server") + " returned " + sensFriendly + " data "
                            + exposures + " time(s), but no egress policy denies or redacts it.",
                    ownerFor("SENSITIVE_EXPOSURE"),
                    entities(roots + " principals", agents + " agents", server == null ? null : "1 server"),
                    "Create policy", "CREATE_POLICY",
                    new ActionContext(capName, capType, server, serverId, null, sensEnum, null, seed),
                    last, (critical ? 90 : 70) + (int) Math.min(exposures, 9)));
            if (out.size() >= 12) {
                break;
            }
        }

        // B) An agent fanning out across many servers.
        for (Object[] r : classificationRepo.agentServerFanout(tenant, MIN_FANOUT_SERVERS)) {
            String consumer = str(r[0]);
            long servers = asLong(r[2]);
            long calls = asLong(r[3]);
            LocalDateTime last = asDateTime(r[4]);
            out.add(new PriorityAction(
                    "pa-fan-" + (++seq), "HIGH", "BROAD_FANOUT",
                    "One agent is reaching " + servers + " servers",
                    nz(consumer, "An agent") + " received data from " + servers + " distinct servers across "
                            + calls + " calls — an unusually broad tool graph.",
                    ownerFor("BROAD_FANOUT"),
                    entities(servers + " servers", null, null),
                    "Review chain", "REVIEW_CHAIN",
                    new ActionContext(null, null, null, null, consumer, null, null,
                            "Review " + nz(consumer, "the agent") + "'s reach across " + servers
                                    + " servers and scope its permits."),
                    last, 60 + (int) Math.min(servers, 9)));
        }

        // C) Governed actions with no verified human root (accountability).
        for (AccountabilityReport.AgentRow a : accountabilityService.getReport().agents()) {
            if (a.unrootedRequests() <= 0) {
                continue;
            }
            out.add(new PriorityAction(
                    "pa-unr-" + (++seq), "MEDIUM", "UNROOTED_CHAIN",
                    "Actions without a verified human root",
                    nz(a.agentName(), "An agent") + " performed " + a.unrootedRequests()
                            + " governed action(s) with no verified human at the root of the delegation chain.",
                    ownerFor("UNROOTED_CHAIN"),
                    entities(a.unrootedRequests() + " unrooted actions", null, null),
                    "Investigate", "INVESTIGATE",
                    new ActionContext(null, null, null, null, a.agentName(), null, null,
                            "Investigate " + nz(a.agentName(), "the agent")
                                    + "'s unrooted actions and confirm the human accountability chain."),
                    null, 40 + (int) Math.min(a.unrootedRequests(), 9)));
        }

        out.sort(Comparator.comparingInt(PriorityAction::rank).reversed());
        return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
    }

    // ── internals ─────────────────────────────────────────────────────────────────

    private static String ownerFor(String findingType) {
        return OWNER_BY_FINDING.getOrDefault(findingType, "Unassigned");
    }

    private static List<String> entities(String a, String b, String c) {
        List<String> out = new ArrayList<>();
        if (a != null) {
            out.add(a);
        }
        if (b != null) {
            out.add(b);
        }
        if (c != null) {
            out.add(c);
        }
        return out;
    }

    /** "RESTRICTED" → "Restricted" for user-facing action titles. */
    private static String friendly(String enumLabel) {
        if (enumLabel == null || enumLabel.isBlank()) {
            return "Sensitive";
        }
        return enumLabel.substring(0, 1).toUpperCase(Locale.ROOT) + enumLabel.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String capLabel(String type, String name) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return type != null && !type.isBlank() ? type.toLowerCase(Locale.ROOT) : "a capability";
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private long decisions(String tenant, LocalDateTime from, LocalDateTime to) {
        return pdpRepo.countByPdpDecisionAndTimestampBetweenAndWsTenantName("ALLOW", from, to, tenant)
                + pdpRepo.countByPdpDecisionAndTimestampBetweenAndWsTenantName("DENY", from, to, tenant);
    }

    /** Distinct principals (humans + NHIs) whose classified-data footprint peaks at Restricted. */
    private int riskyPrincipalCount(String tenant) {
        return peakRestricted(classificationRepo.humanRootSensitivity(tenant))
                + peakRestricted(classificationRepo.nhiRootSensitivity(tenant));
    }

    private int peakRestricted(List<Object[]> rows) {
        Map<String, Integer> peak = new HashMap<>();
        for (Object[] r : rows) {
            String key = str(r[0]);
            if (key == null) {
                continue;
            }
            peak.merge(key, Sensitivity.rankOf(str(r[1])), Math::max);
        }
        return (int) peak.values().stream().filter(v -> v >= Sensitivity.RESTRICTED).count();
    }

    private List<SensitivitySlice> sensitivityMix(String tenant, long total) {
        List<SensitivitySlice> out = new ArrayList<>();
        for (Object[] r : classificationRepo.sensitivityBreakdown(tenant)) {
            String s = str(r[0]) == null ? "PUBLIC" : str(r[0]);
            long c = asLong(r[1]);
            int pct = total > 0 ? (int) Math.round(100.0 * c / total) : 0;
            out.add(new SensitivitySlice(s, c, pct));
        }
        out.sort(Comparator.comparingInt(x -> Sensitivity.rankOf(x.sensitivity())));
        return out;
    }

    // ── window parsing ──────────────────────────────────────────────────────────────

    private static String normalizeWindow(String raw) {
        if (raw == null || raw.isBlank()) {
            return "24h";
        }
        String w = raw.trim().toLowerCase(Locale.ROOT);
        return switch (w) {
            case "1h", "24h", "7d", "30d" -> w;
            default -> "24h";
        };
    }

    private static long windowHours(String window) {
        return switch (window) {
            case "1h" -> 1;
            case "7d" -> 24L * 7;
            case "30d" -> 24L * 30;
            default -> 24;
        };
    }

    // ── small helpers ─────────────────────────────────────────────────────────────

    private static long[] firstRow(List<Object[]> rows, int n) {
        long[] out = new long[n];
        if (rows == null || rows.isEmpty()) {
            return out;
        }
        Object[] r = rows.get(0);
        for (int i = 0; i < n && i < r.length; i++) {
            out[i] = asLong(r[i]);
        }
        return out;
    }

    private static long asLong(Object o) {
        return (o instanceof Number x) ? x.longValue() : 0L;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static LocalDateTime asDateTime(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }
}
