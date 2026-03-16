package com.ws.wsAgenticSecurityGateway.pdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Result of a Cedar policy evaluation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyEvaluationResult {

    /** ALLOW or DENY. */
    private String decision;

    /** Cedar policy IDs that contributed to this decision. */
    private Set<String> matchedPolicies;

    /** Human-readable explanation of the decision. */
    private String reason;

    /** Time taken for policy evaluation in milliseconds. */
    private long evaluationDurationMs;

    /** Whether the evaluation encountered errors (policies skipped). */
    private boolean hasErrors;

    /** Error diagnostics from Cedar engine (if any). */
    private String diagnostics;

    public boolean isAllowed() {
        return "ALLOW".equalsIgnoreCase(decision);
    }

    public boolean isDenied() {
        return "DENY".equalsIgnoreCase(decision);
    }

    /** Convenience factory for ALLOW. */
    public static PolicyEvaluationResult allow(Set<String> matchedPolicies, long durationMs) {
        return PolicyEvaluationResult.builder()
                .decision("ALLOW")
                .matchedPolicies(matchedPolicies)
                .reason("Permitted by policy: " + matchedPolicies)
                .evaluationDurationMs(durationMs)
                .build();
    }

    /** Convenience factory for DENY. */
    public static PolicyEvaluationResult deny(Set<String> matchedPolicies, String reason, long durationMs) {
        return PolicyEvaluationResult.builder()
                .decision("DENY")
                .matchedPolicies(matchedPolicies)
                .reason(reason)
                .evaluationDurationMs(durationMs)
                .build();
    }

    /** Convenience factory for DENY with default reason. */
    public static PolicyEvaluationResult deny(Set<String> matchedPolicies, long durationMs) {
        String reason = matchedPolicies.isEmpty()
                ? "No matching permit policy (default deny)"
                : "Denied by policy: " + matchedPolicies;
        return deny(matchedPolicies, reason, durationMs);
    }

    /** Convenience factory for when no policies are loaded — default deny. */
    public static PolicyEvaluationResult noPolicies(long durationMs) {
        return PolicyEvaluationResult.builder()
                .decision("DENY")
                .matchedPolicies(Set.of())
                .reason("No policies configured — default deny (add a permit policy to allow access)")
                .evaluationDurationMs(durationMs)
                .build();
    }
}
