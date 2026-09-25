package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A complete, bidirectional forensic record of an agent's governed activity — for incident response and
 * accountability:
 * <ul>
 *   <li><b>outbound</b> — everything the agent itself did (it was the decision subject);</li>
 *   <li><b>inbound</b> — everything requested of it (someone invoked one of its skills).</li>
 * </ul>
 * Built from the PDP decision ledger (100% subject/resource coverage), stitched by correlation id. Read-only,
 * tenant-scoped; every entry is a real recorded decision.
 */
public record AgentActivityTrail(
        String agentName,
        String tenant,
        LocalDateTime generatedAt,
        LocalDate periodStart,
        LocalDate periodEnd,
        TrailSummary summary,
        List<ActivityEntry> outbound,
        List<ActivityEntry> inbound,
        List<String> notes) {

    /** Roll-up across both directions. */
    public record TrailSummary(
            long outboundActions,
            long outboundAllowed,
            long outboundDenied,
            long inboundActions,
            long inboundAllowed,
            long inboundDenied,
            int distinctResourcesReached,
            int distinctCallers,
            long humansServed,
            int distinctRequests) {}

    /** One recorded decision. {@code actor} did {@code action} on {@code resource} → {@code decision} (via {@code policy}). */
    public record ActivityEntry(
            LocalDateTime at,
            String actor,
            String action,
            String resource,
            String decision,
            String policy,
            String correlationId) {}
}
