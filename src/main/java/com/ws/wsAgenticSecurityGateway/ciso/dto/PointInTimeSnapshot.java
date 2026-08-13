package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A "time machine" reconstruction of governance as it was <em>observed</em> over a past window, replayed from the
 * timestamped decision ledger. It answers "as of / through date T: which agents were active, what did they do
 * (allow/deny), which policies were actually deciding, who was accountable, and what did the gate enforce?"
 *
 * <p><b>Observed, not declared.</b> The gateway does not version policies, so this reflects what was actually
 * exercised — not policy text that existed but was never triggered, and not a config diff over time. The window is
 * bounded to the ledger's coverage. (A true declared-config time machine needs the policy-versioning feature — see
 * {@code docs/policy-versioning-design.md}.) Read-only, tenant-scoped; every number is a real recorded decision.
 */
public record PointInTimeSnapshot(
        String tenant,
        LocalDateTime generatedAt,
        LocalDate windowFrom,
        LocalDate windowTo,
        LocalDate ledgerStart,
        LocalDate ledgerEnd,
        Summary summary,
        List<PolicyInForce> effectivePolicies,
        List<AgentSnapshot> agents,
        List<DecisionEvent> recentEvents,
        List<String> notes) {

    public record Summary(
            long decisions,
            long allowed,
            long denied,
            long defaultDeny,
            long forbidDeny,
            int activeAgents,
            long governedEvaluations,
            int distinctHumans,
            long accountable,
            long unrooted,
            int effectivePolicies) {}

    /** A policy observed deciding requests in the window. {@code stillPresent/stillEnabled} compare to now. */
    public record PolicyInForce(
            String policyId,
            long decisions,
            boolean stillPresent,
            boolean stillEnabled) {}

    /** One agent's observed footprint in the window. */
    public record AgentSnapshot(
            String agentName,
            long decisions,
            long allowed,
            long denied,
            int distinctResources) {}
}
