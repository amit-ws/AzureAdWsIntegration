package com.ws.wsAgenticSecurityGateway.pdp.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CISO Policy-Activity report — per-policy decision stats plus a tenant coverage roll-up. Every value is derived
 * from the {@code pdp_audit_log} decision ledger (see {@code PolicyActivityService}); nothing is fabricated.
 */
public record PolicyActivityReport(List<PolicyActivity> policies, Summary summary) {

    /**
     * One policy's decision activity. {@code dead == true} ⇒ the policy exists in the catalog but the ledger has
     * never attributed a decision to it (a candidate for review/removal). {@code lastFired} is null when dead.
     */
    public record PolicyActivity(
            String policyName,
            String cedarPolicyId,
            String effect,
            boolean enabled,
            long evaluations,
            long allows,
            long denies,
            LocalDateTime lastFired,
            boolean dead) {}

    /**
     * Tenant roll-up: policy counts (total / fired / dead) and decision coverage — how many decisions were
     * attributed to a named policy vs left to default-deny / no-match ({@code unattributedDecisions}).
     */
    public record Summary(
            int totalPolicies,
            int firedPolicies,
            int deadPolicies,
            long totalDecisions,
            long allows,
            long denies,
            long attributedDecisions,
            long unattributedDecisions) {}
}
