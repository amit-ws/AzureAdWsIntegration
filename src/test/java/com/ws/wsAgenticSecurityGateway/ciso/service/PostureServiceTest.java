package com.ws.wsAgenticSecurityGateway.ciso.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayHumanUserRepository;
import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AccountabilityReport;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AccountabilityReport.AgentRow;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PostureReport;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PostureReport.PostureCheck;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.repository.GatewayPolicyRepository;
import com.ws.wsAgenticSecurityGateway.pdp.service.CedarPolicyEngine;
import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsKeyEntity;
import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsRotationPolicyEntity;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsKeyRepository;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsRevocationRepository;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsRotationPolicyRepository;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsSessionRevocationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Posture scoring for a DEFAULT-DENY gateway. Breadth is judged from the policy HEAD, not clause-presence: a fully
 * scoped permit with NO condition is GOOD (the corrected inversion), an any-principal/all-actions/all-resources
 * unconditional permit is CRITICAL, and a broad permit is only mitigated (not excused) by a condition → WARN.
 * Disabled permits are never penalised; enforcement + revocations are informational (weight 0).
 */
class PostureServiceTest {

    private final GatewayPolicyRepository policyRepo = mock(GatewayPolicyRepository.class);
    private final CedarPolicyEngine cedar = mock(CedarPolicyEngine.class);
    private final PdpAuditLogRepository pdpRepo = mock(PdpAuditLogRepository.class);
    private final AccountabilityService accountabilityService = mock(AccountabilityService.class);
    private final GatewayStsKeyRepository stsKeyRepo = mock(GatewayStsKeyRepository.class);
    private final GatewayStsRotationPolicyRepository rotationRepo = mock(GatewayStsRotationPolicyRepository.class);
    private final GatewayStsRevocationRepository revRepo = mock(GatewayStsRevocationRepository.class);
    private final GatewayStsSessionRevocationRepository sessRevRepo = mock(GatewayStsSessionRevocationRepository.class);
    private final GatewayHumanUserRepository humanRepo = mock(GatewayHumanUserRepository.class);
    private final GatewayAgentRepository agentRepo = mock(GatewayAgentRepository.class);

    private final PostureService service = new PostureService(policyRepo, cedar, pdpRepo, accountabilityService,
            stsKeyRepo, rotationRepo, revRepo, sessRevRepo, humanRepo, agentRepo);

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");
        when(revRepo.findByWsTenantNameAndExpiresAtAfter(eq("acme"), any())).thenReturn(List.of());
        when(sessRevRepo.findByWsTenantNameAndExpiresAtAfter(eq("acme"), any())).thenReturn(List.of());
        when(humanRepo.findByStatusAndWsTenantName("BLOCKED", "acme")).thenReturn(List.of());
        when(agentRepo.findByApprovalStatusAndWsTenantName("PENDING", "acme")).thenReturn(List.of());
        when(stsKeyRepo.findFirstByWsTenantNameAndStatus("acme", "ACTIVE")).thenReturn(Optional.of(
                GatewayStsKeyEntity.builder().kid("k1").status("ACTIVE").createdAt(LocalDateTime.now()).build()));
        when(rotationRepo.findByWsTenantName("acme")).thenReturn(Optional.of(
                GatewayStsRotationPolicyEntity.builder().autoRotate(true).intervalDays(1).build()));
        // enforcement + accountability defaults (INFO signal + a clean accountability report).
        when(pdpRepo.decisionOutcomeCounts("acme")).thenReturn(List.of(
                new Object[]{ "ALLOW", "POLICY_MATCH", 50L }, new Object[]{ "DENY", "DEFAULT_DENY", 2L }));
        when(accountabilityService.getReport()).thenReturn(acct(50, 0));
        // hasConditions routes off the policy text: true iff it contains a "when" clause.
        when(cedar.hasConditions(anyString())).thenAnswer(inv -> inv.getArgument(0, String.class).contains("when"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static AccountabilityReport acct(long governed, long unrooted) {
        return new AccountabilityReport("acme", LocalDateTime.now(),
                new AccountabilityReport.Summary(3, 4, 3, 1, 1, governed, 0, 0, unrooted),
                List.of(new AgentRow("ghost", null, 3, false, 0, 0, 3, 1, 0, null)),
                List.of(), new AccountabilityReport.PolicyOwnership(0, Map.of(), 0), List.of());
    }

    private static GatewayPolicyEntity permit(String id, boolean enabled, String principalKind, String text) {
        return GatewayPolicyEntity.builder().cedarPolicyId(id).enabled(enabled).effect("PERMIT")
                .principalKind(principalKind).policyText(text).build();
    }

    private static GatewayPolicyEntity guardrail(String id, boolean enabled) {
        return GatewayPolicyEntity.builder().cedarPolicyId(id).enabled(enabled).effect("FORBID")
                .source("DEFAULT").policyText("forbid(principal, action, resource) when { ... };").build();
    }

    private static PostureCheck find(PostureReport r, String id) {
        return r.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void fullyScopedPermit_noCondition_scoresGoodAndHigh() {
        when(policyRepo.findAllByWsTenantName("acme")).thenReturn(List.of(
                // Fully head-scoped, NO when-clause → must be GOOD (the corrected inversion), not WARN.
                permit("scoped", true, "AGENT",
                        "permit(principal == Agent::\"x\", action == Action::\"toolCall\", resource == Tool::\"y\");"),
                permit("old", false, "ANY", "permit(principal, action, resource);"),   // disabled → ignored
                guardrail("deny-unverified-root", true),
                guardrail("deny-unverified-actor", true)));

        PostureReport r = service.getReport();

        assertThat(find(r, "broad_enabled_permits").status()).isEqualTo("GOOD");   // scoped, unconditioned → GOOD
        assertThat(find(r, "key_rotation").status()).isEqualTo("GOOD");
        assertThat(find(r, "unaccountable_actions").status()).isEqualTo("GOOD");
        assertThat(find(r, "guardrail_forbids").status()).isEqualTo("GOOD");       // both enabled
        assertThat(find(r, "enforcement_observed").status()).isEqualTo("INFO");    // not scored
        assertThat(find(r, "revocations_blocked").status()).isEqualTo("INFO");
        assertThat(find(r, "enforcement_observed").weight()).isEqualTo(0);

        assertThat(r.score()).isEqualTo(100);   // 40 + 25 + 20 + 15
        assertThat(r.grade()).isEqualTo("A");
        assertThat(r.criticalCount()).isEqualTo(0);
        assertThat(r.warningCount()).isEqualTo(0);
        assertThat(r.headline()).contains("tight");
    }

    @Test
    void anyPrincipalUnconditionalPermit_isCritical() {
        when(policyRepo.findAllByWsTenantName("acme")).thenReturn(List.of(
                permit("allow-all", true, "ANY", "permit(principal, action, resource);")));

        PostureReport r = service.getReport();

        PostureCheck broad = find(r, "broad_enabled_permits");
        assertThat(broad.status()).isEqualTo("CRITICAL");   // any principal, all actions, all resources, unguarded
        assertThat(broad.pointsEarned()).isEqualTo(0);
        assertThat(r.criticalCount()).isEqualTo(1);
        assertThat(r.grade()).isIn("D", "F");
    }

    @Test
    void broadPermit_withCondition_isWarnNotGood() {
        when(policyRepo.findAllByWsTenantName("acme")).thenReturn(List.of(
                // Group principal but wildcard action + resource; a when-clause mitigates but does NOT scope → WARN.
                permit("desk-grant", true, "AGENT_GROUP",
                        "permit(principal in AgentGroup::\"g\", action, resource) when { context.rootVerified };")));

        PostureReport r = service.getReport();

        PostureCheck broad = find(r, "broad_enabled_permits");
        assertThat(broad.status()).isEqualTo("WARN");        // broad head, condition doesn't excuse it
        assertThat(broad.pointsEarned()).isEqualTo(20);      // (40 + 1) / 2, round-half-up
    }
}
