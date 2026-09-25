package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Tenant-wide human-accountability roll-up: for every agent, which verified human is answerable and how (direct
 * vs delegated); the reverse per-human footprint; and policy ownership (authors). Built entirely from the OBO
 * delegation lineage on real PDP evaluations — no assigned-owner field is invented (owner-of-record is a future
 * platform-sync concern). Read-only, tenant-scoped.
 */
public record AccountabilityReport(
        String tenant,
        LocalDateTime generatedAt,
        Summary summary,
        List<AgentRow> agents,
        List<HumanAccountability> humans,
        PolicyOwnership policyOwnership,
        List<String> notes) {

    public record Summary(
            int registeredAgents,
            int actingAgents,
            int agentsWithHumanRoot,
            int agentsWithoutHumanRoot,
            int distinctHumans,
            long governedRequests,
            long directRequests,
            long delegatedRequests,
            long unrootedRequests) {}

    /** One acting agent (a PDP subject). {@code approvalStatus} is null when the agent isn't in the registry. */
    public record AgentRow(
            String agentName,
            String approvalStatus,
            long governedRequests,
            boolean hasVerifiedHumanRoot,
            long directRequests,
            long delegatedRequests,
            long unrootedRequests,
            int maxDelegationDepth,
            int distinctHumans,
            String primaryHuman) {}

    /** Policy authorship from {@code gateway_policy.created_by} (the real owner today). */
    public record PolicyOwnership(
            int totalPolicies,
            Map<String, Long> byOwner,
            int unowned) {}
}
