package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One item in the CISO → Dashboard "Priority actions" queue (#69) — a detected governance condition, ranked by
 * blast radius + sensitivity, with everything the admin needs to act. Detections are real (sensitive data with no
 * enforcing policy; an agent fanning out across many servers; actions with no verified human root). The
 * {@code owner} is resolved from a finding-type → team map (admin-overridable; "Unassigned" when unmapped) — never
 * invented per-agent. {@code context.assistantSeed} is the pre-filled brief handed to the policy AI assistant when
 * the admin clicks through to the PDP screen.
 */
public record PriorityAction(
        String id,
        String severity,        // CRITICAL | HIGH | MEDIUM
        String findingType,     // SENSITIVE_EXPOSURE | BROAD_FANOUT | UNROOTED_CHAIN
        String title,
        String detail,
        String owner,
        List<String> entities,  // e.g. ["3 principals", "2 agents", "1 server"]
        String actionLabel,     // "Create policy" | "Review chain" | "Investigate"
        String actionKind,      // CREATE_POLICY | REVIEW_CHAIN | INVESTIGATE
        ActionContext context,
        LocalDateTime lastSeen,
        int rank) {

    /** Deep-link seed for the click-through (e.g. the policy assistant), plus the entities the finding is about. */
    public record ActionContext(
            String capability,
            String capabilityType,
            String server,
            String serverId,
            String agent,
            String sensitivity,
            String rootPrincipal,
            String assistantSeed) {}
}
