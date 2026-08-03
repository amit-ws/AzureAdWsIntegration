package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationRequest;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationResult;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Stage 2.5 #3 tenant partitioning: each tenant's policies are evaluated in an isolated slot, so
 * one tenant's {@code forbid}/{@code permit} can never decide another tenant's request. Guards against the
 * old shared-singleton behavior where the last tenant to load clobbered everyone else.
 */
class CedarPolicyEngineTenantPartitionTest {

    private static final String PERMIT_ALL = "permit(principal, action, resource);";
    private static final String FORBID_ALL = "forbid(principal, action, resource);";

    private final CedarPolicyEngine engine = new CedarPolicyEngine();

    @Test
    void tenantForbid_doesNotAffectOtherTenant() {
        engine.reloadTenant("tenant-a", List.of(policy("block", FORBID_ALL, "FORBID")));
        engine.reloadTenant("tenant-b", List.of(policy("allow", PERMIT_ALL, "PERMIT")));

        assertThat(engine.evaluate("tenant-a", req()).isDenied()).isTrue();   // A's forbid applies to A
        assertThat(engine.evaluate("tenant-b", req()).isAllowed()).isTrue();  // ...but NOT to B
    }

    @Test
    void tenantPermit_doesNotLeakToOtherTenant() {
        engine.reloadTenant("tenant-a", List.of(policy("allow", PERMIT_ALL, "PERMIT")));
        // tenant-b has no slot and no loader → default-deny, NOT A's permit
        assertThat(engine.evaluate("tenant-a", req()).isAllowed()).isTrue();
        assertThat(engine.evaluate("tenant-b", req()).isDenied()).isTrue();
    }

    @Test
    void unseenTenant_isLazilyLoadedViaLoader() {
        engine.setTenantLoader(t -> "tenant-c".equals(t)
                ? List.of(policy("allow", PERMIT_ALL, "PERMIT"))
                : List.of());

        assertThat(engine.evaluate("tenant-c", req()).isAllowed()).isTrue(); // loader populates the slot
        assertThat(engine.evaluate("tenant-d", req()).isDenied()).isTrue();  // loader returns none → deny
    }

    @Test
    void nullTenant_usesCombinedFallbackSet() {
        engine.reloadGlobal(List.of(policy("allow", PERMIT_ALL, "PERMIT")));
        assertThat(engine.evaluate(null, req()).isAllowed()).isTrue();
    }

    @Test
    void reloadingOneTenant_doesNotClobberAnother() {
        engine.reloadTenant("tenant-a", List.of(policy("allow", PERMIT_ALL, "PERMIT")));
        engine.reloadTenant("tenant-b", List.of(policy("allow", PERMIT_ALL, "PERMIT")));

        // tenant-a flips to forbid — tenant-b must be untouched (the old bug clobbered the shared list)
        engine.reloadTenant("tenant-a", List.of(policy("block", FORBID_ALL, "FORBID")));

        assertThat(engine.evaluate("tenant-a", req()).isDenied()).isTrue();
        assertThat(engine.evaluate("tenant-b", req()).isAllowed()).isTrue();
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private static GatewayPolicyEntity policy(String name, String text, String effect) {
        return GatewayPolicyEntity.builder()
                .policyName(name).cedarPolicyId(name).policyText(text)
                .effect(effect).enabled(true).priority(100).build();
    }

    private static PolicyEvaluationRequest req() {
        return PolicyEvaluationRequest.builder()
                .agentName("test-agent").action("toolCall")
                .resourceName("github_get_me").resourceType("TOOL").build();
    }
}
