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
import com.ws.wsAgenticSecurityGateway.ciso.dto.ChainVisibility;
import com.ws.wsAgenticSecurityGateway.ciso.dto.ChainVisibility.ChainRow;
import com.ws.wsAgenticSecurityGateway.ciso.dto.RiskHotspots;
import com.ws.wsAgenticSecurityGateway.ciso.dto.RiskHotspots.Hotspot;
import com.ws.wsAgenticSecurityGateway.ciso.dto.TopTools;
import com.ws.wsAgenticSecurityGateway.ciso.dto.TopTools.ToolRow;
import com.ws.wsAgenticSecurityGateway.ciso.dto.TrafficSeries;
import com.ws.wsAgenticSecurityGateway.ciso.dto.TrafficSeries.TrafficPoint;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.Sensitivity;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.GatewayResponseClassificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

        // ── Egress gaps (sensitive capabilities with no egress redaction/deny rule) — the KPI "gaps" + priority feed ──
        long[] cov = firstRow(classificationRepo.enforcementCoverage(tenant), 6);
        int gaps = Math.max(0, (int) cov[0] - (int) cov[1]);   // sensitiveCaps − coveredSensitiveCaps

        // ── PDP decision coverage (share decided by an explicit policy) ──
        long[] dc = firstRow(pdpRepo.policyDecisionCoverage(tenant), 5);
        long dTotal = dc[0];
        long dAttributed = dc[3];
        int attributedPct = dTotal > 0 ? (int) Math.round(100.0 * dAttributed / dTotal) : 0;

        // ── Access-policy coverage over REGISTERED servers + tools / skills / agents ──
        long[] ac = firstRow(pdpRepo.accessCoverage(tenant), 8);
        int registeredServers = (int) ac[0];
        int serversWithPolicies = (int) ac[1];
        int mcpCapabilitiesTotal = (int) ac[2];
        int mcpCapabilitiesGoverned = (int) ac[3];
        int skillsTotal = (int) ac[4];
        int skillsGoverned = (int) ac[5];
        int agentsTotal = (int) ac[6];
        int agentsGoverned = (int) ac[7];

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

        Coverage coverage = new Coverage(registeredServers, serversWithPolicies,
                mcpCapabilitiesTotal, mcpCapabilitiesGoverned, skillsTotal, skillsGoverned,
                agentsTotal, agentsGoverned, attributedPct, gaps);

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
        return out.size() > 24 ? new ArrayList<>(out.subList(0, 24)) : out;   // FE pages 6 at a time
    }

    /** Traffic time-series (#70) — governed traffic bucketed over the window, zero-filled for a continuous line. */
    public TrafficSeries traffic(String windowRaw) {
        String tenant = TenantContext.get();
        String window = normalizeWindow(windowRaw);
        String unit = bucketUnit(window);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.minusHours(windowHours(window));

        Map<LocalDateTime, long[]> cls = new HashMap<>();
        for (Object[] r : classificationRepo.trafficBuckets(tenant, unit, from)) {
            LocalDateTime b = asDateTime(r[0]);
            if (b != null) {
                cls.put(b, new long[]{ asLong(r[1]), asLong(r[2]), asLong(r[3]), asLong(r[4]) }); // total, sensitive, mcp, a2a
            }
        }
        Map<LocalDateTime, long[]> dec = new HashMap<>();
        for (Object[] r : pdpRepo.decisionBuckets(tenant, unit, from)) {
            LocalDateTime b = asDateTime(r[0]);
            if (b != null) {
                dec.put(b, new long[]{ asLong(r[1]), asLong(r[2]) }); // allow, deny
            }
        }

        List<TrafficPoint> points = new ArrayList<>();
        LocalDateTime cursor = truncate(from, unit);
        int guard = 0;
        while (!cursor.isAfter(now) && guard++ < 800) {
            long[] c = cls.getOrDefault(cursor, new long[4]);
            long[] d = dec.getOrDefault(cursor, new long[2]);
            points.add(new TrafficPoint(cursor, c[0], c[2], c[3], c[1], d[0], d[1]));
            cursor = "day".equals(unit) ? cursor.plusDays(1) : cursor.plusHours(1);
        }
        return new TrafficSeries(window, unit, points);
    }

    /** Risk hotspots (#72) — scored principals (humans / NHIs), agents, and servers. Score inputs are all real. */
    public RiskHotspots hotspots() {
        String tenant = TenantContext.get();
        return new RiskHotspots(
                principalsFrom(classificationRepo.humanRootSensitivity(tenant)),
                principalsFrom(classificationRepo.nhiRootSensitivity(tenant)),
                agentsFrom(classificationRepo.consumerAgentRisk(tenant)),
                serversFrom(classificationRepo.serverRisk(tenant)));
    }

    /** Root principals (human or NHI) from [name/id, sensitivity, count] rows → scored hotspots. */
    private List<Hotspot> principalsFrom(List<Object[]> rows) {
        Map<String, int[]> agg = new LinkedHashMap<>();   // key → [peakRank, sensitiveCount, totalCount]
        for (Object[] r : rows) {
            String key = str(r[0]);
            if (key == null) {
                continue;
            }
            int rank = Sensitivity.rankOf(str(r[1]));
            int cnt = (int) asLong(r[2]);
            int[] a = agg.computeIfAbsent(key, k -> new int[3]);
            a[0] = Math.max(a[0], rank);
            if (rank >= Sensitivity.CONFIDENTIAL) {
                a[1] += cnt;
            }
            a[2] += cnt;
        }
        List<Hotspot> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : agg.entrySet()) {
            int peak = e.getValue()[0];
            int sensitive = e.getValue()[1];
            int total = e.getValue()[2];
            int score = clampScore(base(peak) + Math.min(15, sensitive));
            out.add(new Hotspot(e.getKey(), e.getKey(), score, band(score), reasonFor(peak),
                    factList(peakLabel(peak) + " footprint", total + " events")));
        }
        return topBy(out, 6);
    }

    /** Agents (consumers) from [consumer, id, peakRank, sensitive, total, servers, last] → scored hotspots. */
    private List<Hotspot> agentsFrom(List<Object[]> rows) {
        List<Hotspot> out = new ArrayList<>();
        for (Object[] r : rows) {
            String name = str(r[0]);
            if (name == null) {
                continue;
            }
            String id = str(r[1]) == null ? name : str(r[1]);
            int peak = (int) asLong(r[2]);
            int sensitive = (int) asLong(r[3]);
            long total = asLong(r[4]);
            long servers = asLong(r[5]);
            int fanout = servers >= MIN_FANOUT_SERVERS ? (int) Math.min(10, (servers - 2) * 3) : 0;
            int score = clampScore(base(peak) + Math.min(15, sensitive) + fanout);
            String reason = fanout > 0 ? "Broad fan-out across " + servers + " servers" : reasonFor(peak);
            out.add(new Hotspot(id, name, score, band(score), reason,
                    factList(peakLabel(peak) + " data", servers + " servers", total + " calls")));
        }
        return topBy(out, 6);
    }

    /** Servers from [serverId, producer, peakRank, sensitive, total, uncovered, last] → scored hotspots. */
    private List<Hotspot> serversFrom(List<Object[]> rows) {
        List<Hotspot> out = new ArrayList<>();
        for (Object[] r : rows) {
            String id = str(r[0]);
            String name = str(r[1]) == null ? id : str(r[1]);
            if (id == null && name == null) {
                continue;
            }
            int peak = (int) asLong(r[2]);
            int sensitive = (int) asLong(r[3]);
            long total = asLong(r[4]);
            long uncovered = asLong(r[5]);
            int gapBump = uncovered > 0 ? 10 : 0;
            int score = clampScore(base(peak) + Math.min(15, sensitive) + gapBump);
            String reason = uncovered > 0 ? "Sensitive data with no blocking policy" : reasonFor(peak);
            out.add(new Hotspot(id == null ? name : id, name, score, band(score), reason,
                    factList(peakLabel(peak) + " data",
                            uncovered > 0 ? uncovered + " uncovered" : "policy-covered", total + " responses")));
        }
        return topBy(out, 6);
    }

    /** Top MCP tools by call volume (#74) — usage × data sensitivity, with denials and a risk band. Top 10. */
    public TopTools topTools() {
        String tenant = TenantContext.get();
        Map<String, Long> denied = new HashMap<>();
        for (Object[] r : pdpRepo.deniedByResource(tenant)) {
            String res = str(r[0]);
            if (res != null) {
                denied.merge(res, asLong(r[1]), Long::sum);
            }
        }
        Map<String, ToolAgg> agg = new LinkedHashMap<>();
        for (Object[] r : classificationRepo.capabilityProfileRows(tenant)) {
            String producer = str(r[0]);
            String capName = str(r[1]);
            if (capName == null) {
                continue;
            }
            String capType = str(r[2]);
            String protocol = str(r[3]);
            int rank = Sensitivity.rankOf(str(r[4]));
            long count = asLong(r[5]);
            ToolAgg a = agg.computeIfAbsent(capType + ":" + capName + "@" + producer,
                    k -> new ToolAgg(capName, capType, producer, protocol));
            a.calls += count;
            a.peak = Math.max(a.peak, rank);
        }
        List<ToolRow> rows = new ArrayList<>();
        for (ToolAgg a : agg.values()) {
            long den = denied.getOrDefault(a.tool, 0L);
            int score = clampScore(base(a.peak) + (int) Math.min(15, den));
            rows.add(new ToolRow(a.tool, a.capType, a.producer, a.protocol, a.calls,
                    peakLabel(a.peak), den, band(score)));
        }
        rows.sort(Comparator.comparingLong(ToolRow::calls).reversed());
        return new TopTools(rows.size() > 10 ? new ArrayList<>(rows.subList(0, 10)) : rows);
    }

    /** Human → agent → MCP chain visibility (#73) — recent governed traces reconstructed from classification hops. */
    public ChainVisibility chains() {
        String tenant = TenantContext.get();
        Map<String, List<Object[]>> byTrace = new LinkedHashMap<>();   // newest trace first (query is newest-first)
        for (Object[] r : classificationRepo.chainRows(tenant, 500)) {
            String trace = str(r[0]);
            if (trace != null) {
                byTrace.computeIfAbsent(trace, k -> new ArrayList<>()).add(r);
            }
        }
        List<ChainRow> chains = new ArrayList<>();
        for (Map.Entry<String, List<Object[]>> e : byTrace.entrySet()) {
            ChainRow row = buildChain(e.getKey(), e.getValue());
            if (row != null) {
                chains.add(row);
            }
            if (chains.size() >= 20) {
                break;
            }
        }
        return new ChainVisibility(chains);
    }

    /** Reconstruct one chain row from a trace's hops. Cols: [0 trace,1 rootName,2 rootKind,3 consumer,4 producer,
     *  5 producerKind,6 capName,7 capType,8 sensitivity,9 protocol,10 classifiedAt]. */
    private ChainRow buildChain(String trace, List<Object[]> rows) {
        String human = null;
        String humanKind = null;
        int peak = 0;
        LocalDateTime seen = null;
        Object[] serverHop = null;
        Object[] a2aHop = null;
        for (Object[] r : rows) {
            if (human == null && str(r[1]) != null) {
                human = str(r[1]);
                humanKind = str(r[2]);
            }
            peak = Math.max(peak, Sensitivity.rankOf(str(r[8])));
            LocalDateTime t = asDateTime(r[10]);
            if (t != null && (seen == null || t.isAfter(seen))) {
                seen = t;
            }
            String pk = str(r[5]);
            if ("SERVER".equalsIgnoreCase(pk) && serverHop == null) {
                serverHop = r;   // newest server hop
            }
            if ("AGENT".equalsIgnoreCase(pk) && a2aHop == null) {
                a2aHop = r;      // newest A2A (skill) hop
            }
        }
        String agent;
        String agentToAgent;
        String server = null;
        String tool = null;
        String capType = null;
        String protocol = null;
        if (serverHop != null) {
            server = str(serverHop[4]);
            tool = str(serverHop[6]);
            capType = str(serverHop[7]);
            protocol = str(serverHop[9]);
        }
        if (a2aHop != null) {
            agent = str(a2aHop[3]);          // entry agent (consumer of the A2A hop)
            agentToAgent = str(a2aHop[4]);   // downstream agent
            if (server == null) {
                tool = str(a2aHop[6]);
                capType = str(a2aHop[7]);
                protocol = str(a2aHop[9]);
            }
        } else {
            agent = serverHop != null ? str(serverHop[3]) : null;
            agentToAgent = null;
        }
        if (agent == null && server == null && tool == null) {
            return null;
        }
        return new ChainRow(trace, human, humanKind, agent, agentToAgent, server, tool, capType, protocol,
                sensitivityBand(peak), seen);
    }

    /** Sensitivity-only band for a path/tool: RESTRICTED→CRITICAL … PUBLIC→LOW. */
    private static String sensitivityBand(int peak) {
        return switch (peak) {
            case 3 -> "CRITICAL";
            case 2 -> "HIGH";
            case 1 -> "MEDIUM";
            default -> "LOW";
        };
    }

    /** Mutable per-capability accumulator for the top-tools rollup. */
    private static final class ToolAgg {
        final String tool;
        final String capType;
        final String producer;
        final String protocol;
        long calls;
        int peak;

        ToolAgg(String tool, String capType, String producer, String protocol) {
            this.tool = tool;
            this.capType = capType;
            this.producer = producer;
            this.protocol = protocol;
        }
    }

    // ── scoring (transparent, tunable) ──────────────────────────────────────────────

    /** Base points by peak sensitivity rank reached (PUBLIC…RESTRICTED). */
    private static int base(int peakRank) {
        return switch (peakRank) {
            case 3 -> 80;   // RESTRICTED
            case 2 -> 55;   // CONFIDENTIAL
            case 1 -> 25;   // INTERNAL
            default -> 10;  // PUBLIC
        };
    }

    private static int clampScore(int s) {
        return Math.max(0, Math.min(100, s));
    }

    private static String band(int score) {
        return score >= 80 ? "CRITICAL" : score >= 60 ? "HIGH" : score >= 35 ? "MEDIUM" : "LOW";
    }

    private static String reasonFor(int peakRank) {
        return switch (peakRank) {
            case 3 -> "Handles Restricted data";
            case 2 -> "Handles Confidential data";
            case 1 -> "Handles Internal data";
            default -> "Public data only";
        };
    }

    private static String peakLabel(int peakRank) {
        return friendly(Sensitivity.labelOf(peakRank));
    }

    private static List<Hotspot> topBy(List<Hotspot> in, int n) {
        in.sort(Comparator.comparingInt(Hotspot::score).reversed());
        return in.size() > n ? new ArrayList<>(in.subList(0, n)) : in;
    }

    private static List<String> factList(String... xs) {
        List<String> out = new ArrayList<>();
        for (String x : xs) {
            if (x != null && !x.isBlank()) {
                out.add(x);
            }
        }
        return out;
    }

    private static String bucketUnit(String window) {
        return switch (window) {
            case "7d", "30d", "90d" -> "day";
            default -> "hour";
        };
    }

    private static LocalDateTime truncate(LocalDateTime dt, String unit) {
        return "day".equals(unit) ? dt.truncatedTo(ChronoUnit.DAYS) : dt.truncatedTo(ChronoUnit.HOURS);
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
            case "24h", "7d", "30d", "90d" -> w;
            default -> "24h";
        };
    }

    private static long windowHours(String window) {
        return switch (window) {
            case "7d" -> 24L * 7;
            case "30d" -> 24L * 30;
            case "90d" -> 24L * 90;
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
