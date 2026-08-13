package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyActivityReport;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyActivityReport.PolicyActivity;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyActivityReport.Summary;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Merge logic for the Policy-Activity report: ledger aggregates joined onto the policy catalog, dead-policy
 * detection, ordering, and coverage roll-up. The native SQL itself (multi-policy split via unnest) is validated
 * against the real database; here we pin the pure-Java merge so it can't silently regress.
 */
class PolicyActivityServiceTest {

    private final PdpAuditLogRepository pdpRepo = mock(PdpAuditLogRepository.class);
    private final PolicyService policyService = mock(PolicyService.class);
    private final PolicyActivityService service = new PolicyActivityService(pdpRepo, policyService);

    @BeforeEach
    void setTenant() {
        TenantContext.set("acme");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void mergesLedgerActivityWithCatalog_flagsDeadPolicies_andRollsUpCoverage() {
        LocalDateTime fired = LocalDateTime.of(2026, 8, 13, 0, 31, 39);

        // Ledger: 'grant' decided 87 (all ALLOW), 'block' decided 8 (all DENY); 'dormant' never appears → dead.
        when(pdpRepo.aggregatePolicyActivity(anyString())).thenReturn(List.of(
                new Object[]{ "grant", 87L, 87L, 0L, Timestamp.valueOf(fired) },
                new Object[]{ "block", 8L, 0L, 8L, Timestamp.valueOf(fired) }));
        // Coverage: 100 decisions, 87 ALLOW / 13 DENY, 95 attributed / 5 default-deny.
        // (Explicit <Object[]> witness: List.of(singleArray) would otherwise spread it into List<Object>.)
        when(pdpRepo.policyDecisionCoverage(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{ 100L, 87L, 13L, 95L, 5L }));

        when(policyService.getAllPolicies()).thenReturn(List.of(
                policy("grant", "PERMIT", true),
                policy("block", "FORBID", false),
                policy("dormant", "PERMIT", true)));

        PolicyActivityReport report = service.getPolicyActivity();

        // Most-active first, dead sinks to the bottom.
        assertThat(report.policies()).extracting(PolicyActivity::cedarPolicyId)
                .containsExactly("grant", "block", "dormant");

        PolicyActivity grant = report.policies().get(0);
        assertThat(grant.evaluations()).isEqualTo(87);
        assertThat(grant.allows()).isEqualTo(87);
        assertThat(grant.denies()).isEqualTo(0);
        assertThat(grant.enabled()).isTrue();
        assertThat(grant.dead()).isFalse();
        assertThat(grant.lastFired()).isEqualTo(fired);

        PolicyActivity block = report.policies().get(1);
        assertThat(block.denies()).isEqualTo(8);
        assertThat(block.enabled()).isFalse();

        PolicyActivity dormant = report.policies().get(2);
        assertThat(dormant.dead()).isTrue();
        assertThat(dormant.evaluations()).isZero();
        assertThat(dormant.allows()).isZero();
        assertThat(dormant.lastFired()).isNull();

        Summary s = report.summary();
        assertThat(s.totalPolicies()).isEqualTo(3);
        assertThat(s.firedPolicies()).isEqualTo(2);
        assertThat(s.deadPolicies()).isEqualTo(1);
        assertThat(s.totalDecisions()).isEqualTo(100);
        assertThat(s.allows()).isEqualTo(87);
        assertThat(s.denies()).isEqualTo(13);
        assertThat(s.attributedDecisions()).isEqualTo(95);
        assertThat(s.unattributedDecisions()).isEqualTo(5);
    }

    private static GatewayPolicyEntity policy(String cedarId, String effect, boolean enabled) {
        return GatewayPolicyEntity.builder()
                .policyName(cedarId)
                .cedarPolicyId(cedarId)
                .effect(effect)
                .enabled(enabled)
                .build();
    }
}
