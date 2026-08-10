package com.ws.wsAgenticSecurityGateway.pdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyEvaluationResult {

    private String decision;

    private Set<String> matchedPolicies;

    private String reason;

    private long evaluationDurationMs;

    private boolean hasErrors;

    private String diagnostics;

    /**
     * Why this decision was reached, for the audit trail — used only when {@link #matchedPolicies} is empty
     * (no rule decided). One of: {@code POLICY_MATCH} (a policy in matchedPolicies decided), {@code DEFAULT_DENY}
     * (no permit matched), {@code NO_POLICIES} (empty policy set), {@code EVAL_ERROR} (fail-closed). Lets a
     * denial attribute to <em>something</em> instead of pointing at zero policies.
     */
    private String decisionBasis;

    public boolean isAllowed() {
        return "ALLOW".equalsIgnoreCase(decision);
    }

    public boolean isDenied() {
        return "DENY".equalsIgnoreCase(decision);
    }

    /**
     * Stable attribution for the audit log: the deciding policy id(s) when a policy decided, else the synthetic
     * basis marker ({@code DEFAULT_DENY} / {@code NO_POLICIES} / {@code EVAL_ERROR}). Never null for a rendered
     * decision — so "which policy decided request R" always resolves to a concrete, queryable value.
     */
    public String decidedBy() {
        if (matchedPolicies != null && !matchedPolicies.isEmpty()) {
            return String.join(",", matchedPolicies);
        }
        if (decisionBasis != null && !decisionBasis.isBlank()) {
            return decisionBasis;
        }
        return hasErrors ? "EVAL_ERROR" : "DEFAULT_DENY";
    }

    public static PolicyEvaluationResult allow(Set<String> matchedPolicies, long durationMs) {
        return PolicyEvaluationResult.builder()
                .decision("ALLOW")
                .matchedPolicies(matchedPolicies)
                .reason("Permitted by policy: " + matchedPolicies)
                .decisionBasis("POLICY_MATCH")
                .evaluationDurationMs(durationMs)
                .build();
    }

    public static PolicyEvaluationResult deny(Set<String> matchedPolicies, String reason, long durationMs) {
        return PolicyEvaluationResult.builder()
                .decision("DENY")
                .matchedPolicies(matchedPolicies)
                .reason(reason)
                .decisionBasis((matchedPolicies == null || matchedPolicies.isEmpty()) ? "DEFAULT_DENY" : "POLICY_MATCH")
                .evaluationDurationMs(durationMs)
                .build();
    }

    public static PolicyEvaluationResult deny(Set<String> matchedPolicies, long durationMs) {
        String reason = matchedPolicies.isEmpty()
                ? "No matching permit policy (default deny)"
                : "Denied by policy: " + matchedPolicies;
        return deny(matchedPolicies, reason, durationMs);
    }

    public static PolicyEvaluationResult noPolicies(long durationMs) {
        return PolicyEvaluationResult.builder()
                .decision("DENY")
                .matchedPolicies(Set.of())
                .reason("No policies configured — default deny (add a permit policy to allow access)")
                .decisionBasis("NO_POLICIES")
                .evaluationDurationMs(durationMs)
                .build();
    }
}
