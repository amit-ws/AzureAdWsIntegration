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
        assertThat(o.coverage().coveredServers()).isEqualTo(1);
        assertThat(o.coverage().decisionsAttributedPct()).isEqualTo(60);
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
}
