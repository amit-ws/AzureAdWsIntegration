package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * An agent's attack surface: what it can reach, and what it actually has. The reliable spine is <b>actual</b>
 * reach (from the audit ledger — complete, ground-truth); policy grants are layered on as context and labeled
 * for exactly what they prove. See {@code BlastRadiusService}. Nothing here is fabricated.
 */
public record AgentBlastRadius(
        String agentName,
        boolean reachesAnyResource,
        List<ResourceReach> reach,
        ReachSummary summary,
        List<String> notes) {

    /**
     * One reachable/blocked/used resource.
     * <ul>
     *   <li>{@code status = ACTIVE} — granted by an enabled, unconditional PERMIT;</li>
     *   <li>{@code LATENT} — granted only by a disabled PERMIT (dormant — one toggle from active);</li>
     *   <li>{@code CONDITIONAL} — granted by a PERMIT with a {@code when}/{@code unless} clause (context-dependent);</li>
     *   <li>{@code BLOCKED} — named by an enabled FORBID (actively denied);</li>
     *   <li>{@code USED_ONLY} — the agent actually reached it, but no currently-applicable policy names it
     *       specifically (e.g. granted via a wildcard, or the naming policy was removed).</li>
     * </ul>
     * {@code used}/{@code useCount}/{@code lastUsed} come from the audit ledger, independent of the policy status.
     */
    public record ResourceReach(
            String resourceKind,
            String resourceId,
            String status,
            List<String> viaPolicies,
            boolean used,
            long useCount,
            LocalDateTime lastUsed) {}

    /** Counts across the reach list (a resource is counted once, by its resolved status; {@code used} is orthogonal). */
    public record ReachSummary(int active, int latent, int conditional, int blocked, int used) {}
}
