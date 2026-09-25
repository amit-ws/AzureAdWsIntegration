package com.ws.wsAgenticSecurityGateway.ciso.service;

import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentBlastRadius;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentBlastRadius.ResourceReach;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.service.CedarPolicyEngine;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Blast-radius merge logic. Uses a REAL {@link CedarPolicyEngine} (its parsing is pure) and mocks the catalog +
 * ledger. Pins the behaviours that make the result true: status classification (active/latent/conditional/blocked),
 * the group policy recovered via observed audit decisions, actual-reach merge, USED_ONLY, and the wildcard flag.
 */
class BlastRadiusServiceTest {

    private final PolicyService policyService = mock(PolicyService.class);
    private final PdpAuditLogRepository pdpRepo = mock(PdpAuditLogRepository.class);
    private final CedarPolicyEngine cedarEngine = new CedarPolicyEngine();
    private final BlastRadiusService service = new BlastRadiusService(policyService, cedarEngine, pdpRepo);

    @BeforeEach
    void setTenant() {
        TenantContext.set("acme");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void classifiesGrants_recoversGroupPolicyFromAudit_andMergesActualReach() {
        LocalDateTime t = LocalDateTime.of(2026, 8, 13, 0, 31, 33);

        when(policyService.getAllPolicies()).thenReturn(List.of(
                policy("grant-a", "AGENT", "advisor", "PERMIT", true,
                        "permit(principal == Agent::\"advisor\", action, resource == Tool::\"toolA\");"),
                policy("grant-b", "AGENT", "advisor", "PERMIT", false,
                        "permit(principal == Agent::\"advisor\", action, resource == Tool::\"toolB\");"),
                policy("grant-c", "AGENT", "advisor", "PERMIT", true,
                        "permit(principal == Agent::\"advisor\", action, resource == Skill::\"skillC\") when { context.x == true };"),
                policy("block-d", "AGENT", "advisor", "FORBID", true,
                        "forbid(principal == Agent::\"advisor\", action, resource == Tool::\"toolD\");"),
                // Disabled AND conditional → must resolve to LATENT (dormant), not CONDITIONAL (regression guard).
                policy("grant-e", "AGENT", "advisor", "PERMIT", false,
                        "permit(principal == Agent::\"advisor\", action, resource == Tool::\"toolE\") when { context.x == true };"),
                // Group policy: names a GROUP, not the agent → only reachable via the audit-observed path.
                policy("group-grant", "AGENT_GROUP", "fin", "PERMIT", true,
                        "permit(principal in AgentGroup::\"fin\", action, resource == Skill::\"skillG\");"),
                // Broad wildcard grant → reachesAnyResource.
                policy("wild", "AGENT", "advisor", "PERMIT", true,
                        "permit(principal == Agent::\"advisor\", action, resource);"),
                // Belongs to a different agent, never observed for advisor → must be excluded.
                policy("other", "AGENT", "someone-else", "PERMIT", true,
                        "permit(principal == Agent::\"someone-else\", action, resource == Tool::\"toolO\");")));

        // Audit proves advisor exercised the group policy (evidence of membership).
        when(pdpRepo.policyIdsThatDecidedFor(anyString(), anyString())).thenReturn(List.of("group-grant"));

        // Actual reach: toolA (grant), skillG (group grant), toolX (used but no naming policy → USED_ONLY).
        when(pdpRepo.actualReachBySubject(anyString(), anyString())).thenReturn(List.of(
                new Object[]{ "toolA", "toolCall", 10L, Timestamp.valueOf(t) },
                new Object[]{ "skillG", "skillInvocation", 5L, Timestamp.valueOf(t) },
                new Object[]{ "toolX", "toolCall", 3L, Timestamp.valueOf(t) }));

        AgentBlastRadius br = service.getAgentBlastRadius("advisor");

        Map<String, ResourceReach> byId = br.reach().stream()
                .collect(Collectors.toMap(ResourceReach::resourceId, Function.identity()));

        // The other agent's resource must NOT leak in.
        assertThat(byId).doesNotContainKey("toolO");

        // Status classification.
        assertThat(byId.get("toolA").status()).isEqualTo("ACTIVE");
        assertThat(byId.get("toolB").status()).isEqualTo("LATENT");        // disabled permit — dormant, kept
        assertThat(byId.get("skillC").status()).isEqualTo("CONDITIONAL");  // when {…}
        assertThat(byId.get("toolD").status()).isEqualTo("BLOCKED");       // enabled forbid
        assertThat(byId.get("toolE").status()).isEqualTo("LATENT");        // disabled + conditional → dormant, NOT conditional
        assertThat(byId.get("toolX").status()).isEqualTo("USED_ONLY");     // reached, no naming policy

        // The crux: the GROUP grant is recovered from the audit — skillG is ACTIVE, not missing.
        assertThat(byId.get("skillG").status()).isEqualTo("ACTIVE");
        assertThat(byId.get("skillG").viaPolicies()).contains("group-grant");

        // Actual reach merged onto the policy view.
        assertThat(byId.get("toolA").used()).isTrue();
        assertThat(byId.get("toolA").useCount()).isEqualTo(10);
        assertThat(byId.get("skillG").used()).isTrue();
        assertThat(byId.get("toolB").used()).isFalse();
        assertThat(byId.get("toolB").useCount()).isZero();

        // Wildcard flag.
        assertThat(br.reachesAnyResource()).isTrue();
        assertThat(br.notes()).anyMatch(n -> n.contains("wildcard"));

        // Summary: active {toolA, skillG}=2, latent {toolB, toolE}=2, conditional {skillC}=1, blocked {toolD}=1,
        // used {toolA, skillG, toolX}=3.
        assertThat(br.summary().active()).isEqualTo(2);
        assertThat(br.summary().latent()).isEqualTo(2);
        assertThat(br.summary().conditional()).isEqualTo(1);
        assertThat(br.summary().blocked()).isEqualTo(1);
        assertThat(br.summary().used()).isEqualTo(3);
    }

    private static GatewayPolicyEntity policy(String id, String principalKind, String principalId,
                                              String effect, boolean enabled, String text) {
        return GatewayPolicyEntity.builder()
                .policyName(id)
                .cedarPolicyId(id)
                .principalKind(principalKind)
                .principalId(principalId)
                .effect(effect)
                .enabled(enabled)
                .policyText(text)
                .build();
    }
}
