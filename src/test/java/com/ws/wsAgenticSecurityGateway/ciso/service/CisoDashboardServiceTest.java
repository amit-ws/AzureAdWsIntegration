package com.ws.wsAgenticSecurityGateway.ciso.service;

import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AccountabilityReport;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AccountabilityReport.PolicyOwnership;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AccountabilityReport.Summary;
import com.ws.wsAgenticSecurityGateway.ciso.dto.DashboardOverview;
import com.ws.wsAgenticSecurityGateway.ciso.dto.DashboardOverview.Kpi;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PostureReport;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PostureReport.PostureCheck;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.GatewayResponseClassificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The CISO dashboard "overview" is pure assembly of real aggregates — this test pins the math (KPI values + deltas,
 * posture mapping, sensitivity-mix ordering + percentages, and enforcement-gap coverage) against mocked sources.
 */
class CisoDashboardServiceTest {

    private final PostureService postureService = mock(PostureService.class);
    private final AccountabilityService accountabilityService = mock(AccountabilityService.class);
    private final GatewayResponseClassificationRepository classRepo =
            mock(GatewayResponseClassificationRepository.class);
    private final PdpAuditLogRepository pdpRepo = mock(PdpAuditLogRepository.class);

    private final CisoDashboardService service =
            new CisoDashboardService(postureService, accountabilityService, classRepo, pdpRepo);

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");

        // PDP: every ALLOW-window count → 90, every DENY-window count → 10.
        when(pdpRepo.countByPdpDecisionAndTimestampBetweenAndWsTenantName(eq("ALLOW"), any(), any(), anyString()))
                .thenReturn(90L);
        when(pdpRepo.countByPdpDecisionAndTimestampBetweenAndWsTenantName(eq("DENY"), any(), any(), anyString()))
                .thenReturn(10L);
        // Decision coverage: 100 total, 60 attributed → 60%.
        when(pdpRepo.policyDecisionCoverage(anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{ 100L, 90L, 10L, 60L, 40L }));

        // Sensitive events: 4 this window, 2 the prior window (consecutive calls).
        when(classRepo.countByWsTenantNameAndSensitivityInAndClassifiedAtBetween(anyString(), anyCollection(), any(), any()))
                .thenReturn(4L, 2L);
        when(classRepo.countByWsTenantName(anyString())).thenReturn(12L);
        // Coverage row: [sensitiveCaps, coveredSensitive, totalCaps, coveredCaps, servers, coveredServers].
        when(classRepo.enforcementCoverage(anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{ 3L, 1L, 5L, 2L, 2L, 1L }));
        // Sensitivity breakdown (unordered on purpose — the service must sort it).
        when(classRepo.sensitivityBreakdown(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{ "RESTRICTED", 3L }, new Object[]{ "PUBLIC", 6L }, new Object[]{ "CONFIDENTIAL", 3L }));
        // Risky principals: one human peaks at RESTRICTED; no NHIs.
        when(classRepo.humanRootSensitivity(anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{ "amit", "RESTRICTED", 5L }));
        when(classRepo.nhiRootSensitivity(anyString())).thenReturn(List.of());

        when(postureService.getReport()).thenReturn(new PostureReport(
                "acme", LocalDateTime.now(), 82, "B", "1 critical, 2 warnings — some gaps to tighten",
                1, 2, 1,
                List.of(
                        new PostureCheck("a", "Broad enabled permits", "WARN", "config", "2 broad", "tighten", 40, 20),
                        new PostureCheck("b", "Key rotation", "GOOD", "config", "fresh", "ok", 25, 25),
                        new PostureCheck("c", "Enforcement observed", "INFO", "observed", "10 denials", "fyi", 0, 0)),
                List.of("note")));

        when(accountabilityService.getReport()).thenReturn(new AccountabilityReport(
                "acme", LocalDateTime.now(),
                new Summary(6, 5, 4, 1, 3, 100, 80, 20, 0),
                List.of(), List.of(), new PolicyOwnership(0, Map.of(), 0), List.of()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void overview_assemblesKpisPostureMixAndCoverageFromRealAggregates() {
        DashboardOverview o = service.overview("24h");

        assertThat(o.tenant()).isEqualTo("acme");
        assertThat(o.window()).isEqualTo("24h");
        assertThat(o.classifiedTotal()).isEqualTo(12L);

        // KPI strip
        assertThat(o.kpis()).extracting(Kpi::id)
                .containsExactly("requests", "sensitive", "gaps", "denied", "agents", "risky");
        assertThat(kpi(o, "requests").value()).isEqualTo(100L);   // 90 allow + 10 deny
        assertThat(kpi(o, "requests").previous()).isEqualTo(100L);
        assertThat(kpi(o, "sensitive").value()).isEqualTo(4L);
        assertThat(kpi(o, "sensitive").previous()).isEqualTo(2L);
        assertThat(kpi(o, "denied").value()).isEqualTo(10L);
        assertThat(kpi(o, "agents").value()).isEqualTo(5L);
        assertThat(kpi(o, "agents").previous()).isNull();
        assertThat(kpi(o, "gaps").value()).isEqualTo(2L);         // 3 sensitive − 1 covered
        assertThat(kpi(o, "risky").value()).isEqualTo(1L);

        // Posture (reused scorecard; only scored checks become narrative lines)
        assertThat(o.posture().score()).isEqualTo(82);
        assertThat(o.posture().grade()).isEqualTo("B");
        assertThat(o.posture().coveredTrafficPct()).isEqualTo(60);
        assertThat(o.posture().lines()).hasSize(2);

        // Sensitivity mix — sorted PUBLIC→RESTRICTED, percentages of 12
        assertThat(o.sensitivityMix()).extracting(DashboardOverview.SensitivitySlice::sensitivity)
                .containsExactly("PUBLIC", "CONFIDENTIAL", "RESTRICTED");
        assertThat(o.sensitivityMix().get(0).pct()).isEqualTo(50);   // 6/12
        assertThat(o.sensitivityMix().get(1).pct()).isEqualTo(25);   // 3/12

        // Coverage
        assertThat(o.coverage().enforcementGaps()).isEqualTo(2);
        assertThat(o.coverage().sensitiveCaps()).isEqualTo(3);
        assertThat(o.coverage().toolsTotal()).isEqualTo(5);
        assertThat(o.coverage().toolsWithEnforcement()).isEqualTo(2);
        assertThat(o.coverage().coveredServers()).isEqualTo(1);
        assertThat(o.coverage().decisionsAttributedPct()).isEqualTo(60);
    }

    @Test
    void topTools_rankedByVolume_withDataClassAndRisk() {
        when(classRepo.capabilityProfileRows(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{ "hr-data-search", "search_employee_records", "TOOL", "MCP", "RESTRICTED", 1248L, null },
                new Object[]{ "jira-mcp", "create_ticket", "TOOL", "MCP", "INTERNAL", 384L, null }));
        // deniedByResource returns empty by default → denials show 0 (never over-counted).

        var t = service.topTools();

        assertThat(t.tools()).extracting(r -> r.tool())
                .containsExactly("search_employee_records", "create_ticket");   // volume desc
        assertThat(t.tools().get(0).calls()).isEqualTo(1248L);
        assertThat(t.tools().get(0).dataClass()).isEqualTo("Restricted");
        assertThat(t.tools().get(0).risk()).isEqualTo("CRITICAL");
        assertThat(t.tools().get(0).denied()).isEqualTo(0L);
        assertThat(t.tools().get(1).risk()).isEqualTo("LOW");
    }

    @Test
    void chains_reconstructTraceHumanToAgentToServerToTool() {
        Timestamp newer = Timestamp.valueOf(LocalDateTime.now());
        Timestamp older = Timestamp.valueOf(LocalDateTime.now().minusMinutes(1));
        // Query is newest-first: the A2A (skill) hop, then the server hop, same trace "t1".
        when(classRepo.chainRows(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.<Object[]>of(
                new Object[]{ "t1", "amit", "HUMAN", "agent-console", "advisor", "AGENT",
                        "advisor.analyze", "SKILL", "RESTRICTED", "A2A", newer },
                new Object[]{ "t1", "amit", "HUMAN", "advisor", "alphavantage", "SERVER",
                        "alphavantage_GLOBAL_QUOTE", "TOOL", "PUBLIC", "MCP", older }));

        var c = service.chains();

        assertThat(c.chains()).hasSize(1);
        var row = c.chains().get(0);
        assertThat(row.human()).isEqualTo("amit");
        assertThat(row.humanKind()).isEqualTo("HUMAN");
        assertThat(row.agent()).isEqualTo("agent-console");
        assertThat(row.agentToAgent()).isEqualTo("advisor");
        assertThat(row.server()).isEqualTo("alphavantage");
        assertThat(row.tool()).isEqualTo("alphavantage_GLOBAL_QUOTE");
        assertThat(row.risk()).isEqualTo("CRITICAL");   // peak sensitivity on the path = Restricted
    }

    private static Kpi kpi(DashboardOverview o, String id) {
        return o.kpis().stream().filter(k -> k.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void priorityActions_detectsRestrictedExposure_withOwnerAndAssistantSeed() {
        // One RESTRICTED (peak_rank 3) capability with no enforcing policy: 5 exposures, 3 roots, 2 agents.
        when(classRepo.sensitiveExposures(anyString())).thenReturn(List.<Object[]>of(new Object[]{
                "TOOL", "search_employee_records", "hr-data-search", "srv-1",
                3, 5L, 3L, 2L, Timestamp.valueOf(LocalDateTime.now()) }));
        // (agentServerFanout returns an empty list by default; accountability agents are empty from setUp.)

        var actions = service.priorityActions();

        assertThat(actions).hasSize(1);
        var a = actions.get(0);
        assertThat(a.severity()).isEqualTo("CRITICAL");           // peak = RESTRICTED
        assertThat(a.findingType()).isEqualTo("SENSITIVE_EXPOSURE");
        assertThat(a.owner()).isEqualTo("Data Protection");        // finding-type → team map
        assertThat(a.actionKind()).isEqualTo("CREATE_POLICY");
        assertThat(a.title()).contains("Restricted").contains("search_employee_records");
        assertThat(a.entities()).contains("3 principals", "2 agents", "1 server");
        assertThat(a.context().assistantSeed()).contains("no enforcing egress policy");
        assertThat(a.context().server()).isEqualTo("hr-data-search");
    }

    @Test
    void traffic_bucketsAreZeroFilledAndCarryRealCounts() {
        LocalDateTime bucket = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);   // the current (last) bucket
        when(classRepo.trafficBuckets(anyString(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{ Timestamp.valueOf(bucket), 10L, 4L, 6L, 4L }));         // total, sensitive, mcp, a2a
        when(pdpRepo.decisionBuckets(anyString(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{ Timestamp.valueOf(bucket), 8L, 2L }));                  // allow, deny

        var series = service.traffic("24h");

        assertThat(series.bucket()).isEqualTo("hour");
        assertThat(series.points()).isNotEmpty();                                     // continuous, zero-filled line
        var p = series.points().stream().filter(x -> x.t().equals(bucket)).findFirst().orElseThrow();
        assertThat(p.requests()).isEqualTo(10L);
        assertThat(p.mcp()).isEqualTo(6L);
        assertThat(p.a2a()).isEqualTo(4L);
        assertThat(p.sensitive()).isEqualTo(4L);
        assertThat(p.allowed()).isEqualTo(8L);
        assertThat(p.denied()).isEqualTo(2L);
    }

    @Test
    void hotspots_scoreEntitiesFromRealSignals() {
        // Human "amit": peak RESTRICTED (rank 3), 5 sensitive events → 80 + min(15,5) = 85 → CRITICAL.
        when(classRepo.humanRootSensitivity(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{ "amit", "RESTRICTED", 5L }, new Object[]{ "amit", "PUBLIC", 3L }));
        when(classRepo.nhiRootSensitivity(anyString())).thenReturn(List.of());
        // Agent "advisor": peak CONFIDENTIAL (2), 3 sensitive, 4 servers → 55 + 3 + fanout(6) = 64 → HIGH.
        when(classRepo.consumerAgentRisk(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{ "advisor", "aid-1", 2, 3L, 10L, 4L, Timestamp.valueOf(LocalDateTime.now()) }));
        // Server "alphavantage": public only, no exposure → 10 → LOW.
        when(classRepo.serverRisk(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{ "srv-1", "alphavantage", 0, 0L, 10L, 0L, Timestamp.valueOf(LocalDateTime.now()) }));

        var h = service.hotspots();

        assertThat(h.humans()).hasSize(1);
        assertThat(h.humans().get(0).score()).isEqualTo(85);
        assertThat(h.humans().get(0).band()).isEqualTo("CRITICAL");
        assertThat(h.humans().get(0).reason()).isEqualTo("Handles Restricted data");
        assertThat(h.nhis()).isEmpty();
        assertThat(h.agents().get(0).band()).isEqualTo("HIGH");
        assertThat(h.agents().get(0).reason()).contains("fan-out across 4 servers");
        assertThat(h.servers().get(0).band()).isEqualTo("LOW");
    }
}
