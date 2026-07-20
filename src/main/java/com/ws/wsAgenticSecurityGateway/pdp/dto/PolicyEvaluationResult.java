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

    public boolean isAllowed() {
        return "ALLOW".equalsIgnoreCase(decision);
    }

    public boolean isDenied() {
        return "DENY".equalsIgnoreCase(decision);
    }

    public static PolicyEvaluationResult allow(Set<String> matchedPolicies, long durationMs) {
        return PolicyEvaluationResult.builder()
                .decision("ALLOW")
                .matchedPolicies(matchedPolicies)
                .reason("Permitted by policy: " + matchedPolicies)
                .evaluationDurationMs(durationMs)
                .build();
    }

    public static PolicyEvaluationResult deny(Set<String> matchedPolicies, String reason, long durationMs) {
        return PolicyEvaluationResult.builder()
                .decision("DENY")
                .matchedPolicies(matchedPolicies)
                .reason(reason)
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
                .evaluationDurationMs(durationMs)
                .build();
    }
}
