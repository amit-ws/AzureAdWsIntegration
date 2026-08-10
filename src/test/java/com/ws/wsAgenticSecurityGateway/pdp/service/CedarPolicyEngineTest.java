package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationRequest;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationResult;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Cedar engine gates on the {@code act_chain} lineage attributes that
 * {@link com.ws.wsAgenticSecurityGateway.orchestration.HopOrchestrator} populates on the request before
 * {@code evaluate()} (Stage-1 M4b). These pin the mechanism the Stage-2 default lineage policies
 * ({@code deny-unverified-root}, {@code deny-unverified-actor}) depend on.
 *
 * <p>Baseline: a broad {@code permit} (so normal tools work) plus the two lineage {@code forbid}s. Because
 * a matching forbid short-circuits over any permit, an unverified lineage is denied even though permit-all
 * would otherwise allow it.
 */
class CedarPolicyEngineTest {

    private final CedarPolicyEngine engine = new CedarPolicyEngine();

    private static final String PERMIT_ALL =
            "permit(principal, action, resource);";
    private static final String FORBID_UNVERIFIED_ROOT =
            "forbid(principal, action, resource) when { context.rootVerified == false };";
    private static final String FORBID_UNVERIFIED_ACTOR =
            "forbid(principal, action, resource) when { context.actorVerified == false };";

    @BeforeEach
    void loadBaselinePolicies() {
        engine.reloadPolicies(List.of(
                policy("allow-all", PERMIT_ALL, "PERMIT"),
                policy("deny-unverified-root", FORBID_UNVERIFIED_ROOT, "FORBID"),
                policy("deny-unverified-actor", FORBID_UNVERIFIED_ACTOR, "FORBID")));
    }

    @Test
    void verifiedHumanRoot_andVerifiedActor_isAllowed() {
        PolicyEvaluationResult result = engine.evaluate(request(
                principal("human", "sarah", true),
                principal("agent", "claude-desktop", true)));

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getMatchedPolicies()).contains("allow-all");
    }

    @Test
    void unverifiedRoot_isDenied() {
        PolicyEvaluationResult result = engine.evaluate(request(
                principal("human", "sarah", false),
                principal("agent", "claude-desktop", true)));

        assertThat(result.isDenied()).isTrue();
        assertThat(result.getMatchedPolicies()).contains("deny-unverified-root");
    }

    @Test
    void unverifiedActor_isDenied() {
        PolicyEvaluationResult result = engine.evaluate(request(
                principal("human", "sarah", true),
                principal("agent", "rogue-agent", false)));

        assertThat(result.isDenied()).isTrue();
        assertThat(result.getMatchedPolicies()).contains("deny-unverified-actor");
    }

    @Test
    void roleGating_permitsOnlyUsersWithTheRole() {
        // ABAC on the human's roles — the Stage-2 Cedar enrichment (roles now exposed as principal attributes).
        engine.reloadPolicies(List.of(policy("finance-only",
                "permit(principal, action, resource) when { principal.roles.contains(\"finance\") };", "PERMIT")));

        PolicyEvaluationResult withRole = engine.evaluate(PolicyEvaluationRequest.builder()
                .agentName("a").action("toolCall").resourceName("t").resourceType("TOOL")
                .agentRoles(List.of("finance", "user")).build());
        assertThat(withRole.isAllowed()).isTrue();

        PolicyEvaluationResult withoutRole = engine.evaluate(PolicyEvaluationRequest.builder()
                .agentName("a").action("toolCall").resourceName("t").resourceType("TOOL")
                .agentRoles(List.of("user")).build());
        assertThat(withoutRole.isDenied()).isTrue();
    }

    @Test
    void absentActChain_fallsThroughToPermit_documentingTheGap() {
        // The gateway always builds an act_chain before PDP (M4b), so an absent chain only occurs in open
        // mode. The regex engine cannot express "attribute missing", so these forbids do NOT fire when the
        // lineage is absent — the request is allowed by permit-all. Documented here; hardening (treat missing
        // lineage as deny) is tracked as a Stage-2 limitation, not a regression.
        PolicyEvaluationRequest req = baseRequest();
        req.setActChain(null);

        PolicyEvaluationResult result = engine.evaluate(req);

        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    void extractPrincipal_mapsHeadClauseToScope() {
        // Powers the queryable "policies for agent X" read-model — mirrors the runtime principal parsing.
        assertThat(engine.extractPrincipal("permit(principal == Agent::\"advisor\", action, resource);"))
                .isEqualTo(new CedarPolicyEngine.PolicyPrincipal("AGENT", "advisor"));

        assertThat(engine.extractPrincipal("permit(principal in AgentGroup::\"finance-agents\", action, resource);"))
                .isEqualTo(new CedarPolicyEngine.PolicyPrincipal("AGENT_GROUP", "finance-agents"));

        assertThat(engine.extractPrincipal("permit(principal is Agent, action, resource);"))
                .isEqualTo(new CedarPolicyEngine.PolicyPrincipal("AGENT_TYPE", "Agent"));

        // A principal-agnostic guardrail (bare principal) is a wildcard — applies to every agent.
        assertThat(engine.extractPrincipal(FORBID_UNVERIFIED_ROOT))
                .isEqualTo(new CedarPolicyEngine.PolicyPrincipal("ANY", null));
        assertThat(engine.extractPrincipal("permit(principal, action, resource);"))
                .isEqualTo(new CedarPolicyEngine.PolicyPrincipal("ANY", null));

        // Null / blank text defends against odd rows — treated as wildcard, never NPEs.
        assertThat(engine.extractPrincipal(null))
                .isEqualTo(new CedarPolicyEngine.PolicyPrincipal("ANY", null));
        assertThat(engine.extractPrincipal("   "))
                .isEqualTo(new CedarPolicyEngine.PolicyPrincipal("ANY", null));
    }

    @Test
    void decidedBy_attributesEveryDecisionCase() {
        // The audit trail must always attribute a decision — a matched policy id, or a synthetic marker.

        // ALLOW → the matching permit's id.
        PolicyEvaluationResult allow = engine.evaluate(request(
                principal("human", "sarah", true), principal("agent", "claude-desktop", true)));
        assertThat(allow.isAllowed()).isTrue();
        assertThat(allow.decidedBy()).contains("allow-all");

        // FORBID DENY → the deciding forbid's id.
        PolicyEvaluationResult forbid = engine.evaluate(request(
                principal("human", "sarah", false), principal("agent", "claude-desktop", true)));
        assertThat(forbid.decidedBy()).isEqualTo("deny-unverified-root");

        // DEFAULT-DENY (a permit exists but none matched) → synthetic marker, not an empty attribution.
        engine.reloadPolicies(List.of(policy("finance-only",
                "permit(principal, action, resource) when { principal.roles.contains(\"finance\") };", "PERMIT")));
        PolicyEvaluationResult defaultDeny = engine.evaluate(PolicyEvaluationRequest.builder()
                .agentName("a").action("toolCall").resourceName("t").resourceType("TOOL")
                .agentRoles(List.of("user")).build());
        assertThat(defaultDeny.isDenied()).isTrue();
        assertThat(defaultDeny.decidedBy()).isEqualTo("DEFAULT_DENY");

        // NO POLICIES configured → its own marker.
        engine.reloadPolicies(List.of());
        assertThat(engine.evaluate(baseRequest()).decidedBy()).isEqualTo("NO_POLICIES");
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private static GatewayPolicyEntity policy(String name, String text, String effect) {
        return GatewayPolicyEntity.builder()
                .policyName(name)
                .cedarPolicyId(name)
                .policyText(text)
                .effect(effect)
                .enabled(true)
                .priority(100)
                .build();
    }

    private static Map<String, Object> principal(String type, String id, boolean verified) {
        return Map.of("type", type, "id", id, "verified", verified);
    }

    private static PolicyEvaluationRequest baseRequest() {
        return PolicyEvaluationRequest.builder()
                .agentName("test-agent")
                .action("toolCall")
                .resourceName("github_get_me")
                .resourceType("TOOL")
                .build();
    }

    private static PolicyEvaluationRequest request(Map<String, Object> root, Map<String, Object> actor) {
        PolicyEvaluationRequest req = baseRequest();
        req.setActChain(List.of(root, actor));
        return req;
    }
}
