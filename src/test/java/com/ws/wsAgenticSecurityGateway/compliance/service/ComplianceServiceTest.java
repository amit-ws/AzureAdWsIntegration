package com.ws.wsAgenticSecurityGateway.compliance.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.compliance.dto.ComplianceReport;
import com.ws.wsAgenticSecurityGateway.compliance.dto.ComplianceReport.ControlEvidence;
import com.ws.wsAgenticSecurityGateway.compliance.dto.ComplianceReport.EvidenceItem;
import com.ws.wsAgenticSecurityGateway.compliance.dto.ComplianceTemplate;
import com.ws.wsAgenticSecurityGateway.compliance.repository.ComplianceAuditStatsRepository;
import com.ws.wsAgenticSecurityGateway.compliance.repository.ComplianceClassificationRepository;
import com.ws.wsAgenticSecurityGateway.compliance.repository.ComplianceDecisionRepository;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Config-driven compliance assembly for the standalone {@code compliance} module: the shipped SOC 2 template filled
 * from the live metric menu, plus the upload path — parsing (JSON/YAML), unknown-metric validation, custom-template
 * rendering, and CSV export. Every value must come from the mocked real sources; the structure comes from the template.
 */
class ComplianceServiceTest {

    private final ComplianceDecisionRepository decisionRepo = mock(ComplianceDecisionRepository.class);
    private final ComplianceAuditStatsRepository auditRepo = mock(ComplianceAuditStatsRepository.class);
    private final ComplianceClassificationRepository classRepo = mock(ComplianceClassificationRepository.class);
    private final GatewayAgentRepository agentRepo = mock(GatewayAgentRepository.class);
    private final PolicyService policyService = mock(PolicyService.class);
    private final ComplianceService service =
            new ComplianceService(decisionRepo, auditRepo, classRepo, agentRepo, policyService);

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");
        // PDP coverage: 100 decisions, 90 allow / 10 deny, 60 attributed / 40 not.
        when(decisionRepo.policyDecisionCoverage(anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{ 100L, 90L, 10L, 60L, 40L }));
        // Audit: 500 events, 20 types, 450 human-attributed, 3 humans, 5 agents, 11th–13th Aug.
        when(auditRepo.tenantAuditStats(anyString())).thenReturn(List.<Object[]>of(new Object[]{
                500L, 20L, 450L, 3L, 5L,
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 11, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 13, 0, 0)) }));
        // Agents: 2 approved + 1 pending.
        when(agentRepo.findAllByWsTenantName(anyString()))
                .thenReturn(List.of(agent("APPROVED"), agent("APPROVED"), agent("PENDING")));
        // Policies: 1 MANUAL enabled + 1 LLM_GENERATED disabled.
        when(policyService.getAllPolicies()).thenReturn(List.of(
                policy(true, "MANUAL", LocalDateTime.of(2026, 8, 13, 9, 0)),
                policy(false, "LLM_GENERATED", LocalDateTime.of(2026, 8, 10, 9, 0))));
        // Egress classification: 200 classified, 40 sensitive / 15 restricted / 25 confidential, 30 financial,
        // 2 injections, 180 human-attributed, 11th–13th Aug.
        when(classRepo.dataClassStats(anyString())).thenReturn(List.<Object[]>of(new Object[]{
                200L, 40L, 15L, 25L, 30L, 2L, 180L,
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 11, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 13, 0, 0)) }));
        when(classRepo.distinctCategoryCount(anyString())).thenReturn(6L);
        when(classRepo.activeCustomRuleCount(anyString())).thenReturn(4L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void defaultSoc2Template_fillsControlsFromRealMenu() {
        ComplianceReport r = service.soc2Report();

        assertThat(r.framework()).contains("SOC 2");
        assertThat(r.tenant()).isEqualTo("acme");
        assertThat(r.disclaimer()).containsIgnoringCase("not a SOC 2 certification");
        assertThat(r.periodStart()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(r.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(r.controls()).extracting(ControlEvidence::ref)
                .containsExactly("CC6.1", "CC6.2", "CC6.3", "CC7.2", "CC7.3", "CC8.1");
        assertThat(r.controls()).allMatch(c -> "EVIDENCED".equals(c.status()));

        assertThat(ev(ctrl(r, "CC6.1"), "Access decisions rendered")).isEqualTo("100");
        assertThat(ev(ctrl(r, "CC6.1"), "Denied")).isEqualTo("10");
        assertThat(ev(ctrl(r, "CC6.1"), "Requests attributed to a verified human identity")).isEqualTo("450 of 500");
        assertThat(ev(ctrl(r, "CC6.2"), "Approved")).isEqualTo("2");
        assertThat(ev(ctrl(r, "CC6.2"), "Pending approval")).isEqualTo("1");
        assertThat(ev(ctrl(r, "CC6.3"), "Currently enabled")).isEqualTo("1");
        assertThat(ev(ctrl(r, "CC7.2"), "Audit events recorded")).isEqualTo("500");
        assertThat(ev(ctrl(r, "CC7.2"), "Coverage period")).isEqualTo("2026-08-11 to 2026-08-13");
        assertThat(ev(ctrl(r, "CC8.1"), "System defaults")).isEqualTo("0");
        assertThat(ev(ctrl(r, "CC8.1"), "Most recent policy change")).isEqualTo("2026-08-13");
    }

    @Test
    void defaultSoxTemplate_fillsControls_includingPostProcessorEvidence() {
        ComplianceReport r = service.render("sox", null);

        assertThat(r.framework()).contains("SOX");
        assertThat(r.tenant()).isEqualTo("acme");
        assertThat(r.disclaimer()).containsIgnoringCase("not a SOX audit");
        assertThat(r.controls()).extracting(ControlEvidence::ref)
                .containsExactly("APD.1", "APD.2", "LP.1", "PC.1", "DC.1", "CO.1");
        assertThat(r.controls()).allMatch(c -> "EVIDENCED".equals(c.status()));
        // Every metric the shipped SOX template cites must be a real menu key (guards against typos in the template).
        assertThat(r.controls()).allSatisfy(c ->
                assertThat(c.evidence()).allSatisfy(e ->
                        assertThat(e.value()).doesNotContain("unknown metric")));

        // The post-processor-fed evidence (data-confidentiality control) carries the mocked live values.
        assertThat(ev(ctrl(r, "DC.1"), "Responses inspected & classified")).isEqualTo("200");
        assertThat(ev(ctrl(r, "DC.1"), "Responses carrying financial data")).isEqualTo("30");
        assertThat(ev(ctrl(r, "DC.1"), "Sensitive responses (of total)")).isEqualTo("40 of 200");
        assertThat(ev(ctrl(r, "DC.1"), "Restricted-sensitivity responses")).isEqualTo("15");
        assertThat(ev(ctrl(r, "DC.1"), "Distinct data categories observed")).isEqualTo("6");
        assertThat(ev(ctrl(r, "PC.1"), "Active data-classification rules")).isEqualTo("4");
        assertThat(ev(ctrl(r, "CO.1"), "Classified egress attributed to a human (of total)"))
                .isEqualTo("180 of 200");
    }

    @Test
    void metricMenu_includesPostProcessorMetrics() {
        assertThat(service.metricMenu()).containsKeys(
                "dataclass.classifications", "dataclass.financial", "dataclass.sensitiveOfTotal",
                "dataclass.restricted", "dataclass.categories", "dataclass.rulesActive",
                "dataclass.humanAttributedOfTotal", "dataclass.period");
    }

    @Test
    void frameworks_listSoc2AndSox() {
        assertThat(service.frameworks()).extracting(m -> m.get("id")).contains("soc2", "sox");
    }

    @Test
    void render_withUploadedJsonTemplate_fillsFromMenu() {
        String tpl = """
                { "framework": "Custom Pack", "disclaimer": "custom note",
                  "controls": [ { "ref": "X1", "title": "t", "requirement": "r", "howSatisfied": "h",
                    "evidence": [ { "label": "Denials", "metric": "decisions.denied" },
                                  { "label": "Policies", "metric": "policies.total" } ] } ] }
                """;
        ComplianceTemplate t = service.parseTemplate(tpl);
        ComplianceReport r = service.render(t.framework(), t);

        assertThat(r.framework()).isEqualTo("Custom Pack");
        assertThat(r.disclaimer()).isEqualTo("custom note");
        assertThat(r.controls()).hasSize(1);
        assertThat(ev(ctrl(r, "X1"), "Denials")).isEqualTo("10");
        assertThat(ev(ctrl(r, "X1"), "Policies")).isEqualTo("2");
    }

    @Test
    void parseTemplate_acceptsYaml() {
        String yaml = """
                framework: YAML Pack
                controls:
                  - ref: Y1
                    title: t
                    evidence:
                      - label: Denied
                        metric: decisions.denied
                """;
        ComplianceTemplate t = service.parseTemplate(yaml);
        assertThat(t.framework()).isEqualTo("YAML Pack");
        assertThat(t.controls().get(0).evidence().get(0).metric()).isEqualTo("decisions.denied");
    }

    @Test
    void unknownMetrics_flagsReferencesTheGatewayCannotFill() {
        ComplianceTemplate bad = service.parseTemplate("""
                { "framework": "X", "controls": [ { "ref": "X1",
                  "evidence": [ { "label": "ok", "metric": "decisions.denied" },
                                { "label": "bad", "metric": "unicorns.count" } ] } ] }
                """);
        assertThat(service.unknownMetrics(bad)).containsExactly("unicorns.count");

        ComplianceTemplate good = service.parseTemplate("""
                { "framework": "X", "controls": [ { "ref": "X1",
                  "evidence": [ { "label": "ok", "metric": "decisions.total" } ] } ] }
                """);
        assertThat(service.unknownMetrics(good)).isEmpty();
    }

    @Test
    void toCsv_flattensReportToRows() {
        String csv = service.toCsv(service.soc2Report());
        assertThat(csv).contains("control_ref,control_title,status,evidence_label,evidence_value");
        assertThat(csv).contains("CC6.1");
        assertThat(csv).contains("Access decisions rendered");
    }

    private static ControlEvidence ctrl(ComplianceReport r, String ref) {
        return r.controls().stream().filter(c -> c.ref().equals(ref)).findFirst().orElseThrow();
    }

    private static String ev(ControlEvidence c, String label) {
        return c.evidence().stream().filter(e -> e.label().equals(label))
                .map(EvidenceItem::value).findFirst().orElse(null);
    }

    private static GatewayAgentEntity agent(String approvalStatus) {
        return GatewayAgentEntity.builder().approvalStatus(approvalStatus).build();
    }

    private static GatewayPolicyEntity policy(boolean enabled, String source, LocalDateTime updatedAt) {
        return GatewayPolicyEntity.builder().enabled(enabled).source(source).updatedAt(updatedAt).build();
    }
}
