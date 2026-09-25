package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Who is answerable for one agent's governed actions — derived from the OBO delegation lineage ({@code actChain})
 * recorded on every PDP evaluation, so it holds even for agents reached several hops downstream. Each governed
 * request roots at a verified principal (a human, for OBO calls); the chain depth gives the attribution:
 * <ul>
 *   <li><b>DIRECT</b> — a human drove this agent itself ({@code human → agent}, depth 2);</li>
 *   <li><b>DELEGATED</b> — this agent was reached through other agents ({@code human → … → agent}, depth ≥ 3).</li>
 * </ul>
 * Read-only, tenant-scoped. Every number is a real recorded evaluation.
 */
public record AgentAccountability(
        String agentName,
        String approvalStatus,
        LocalDateTime generatedAt,
        long governedRequests,
        boolean hasVerifiedHumanRoot,
        long directRequests,
        long delegatedRequests,
        long unrootedRequests,
        int maxDelegationDepth,
        AccountableHuman primaryHuman,
        List<AccountableHuman> accountableHumans,
        List<DelegationPath> delegationPaths,
        List<String> notes) {

    /** A verified human answerable for the agent, enriched from the human registry where available. */
    public record AccountableHuman(
            String humanId,
            String username,
            String fullName,
            String email,
            String status,
            boolean verified,
            long requests,
            long directRequests,
            long delegatedRequests) {}

    /** A distinct delegation lineage that reached the agent: {@code [rootHuman, …, agent]} and how often. */
    public record DelegationPath(
            List<String> path,
            String attribution,
            long requests) {}
}
