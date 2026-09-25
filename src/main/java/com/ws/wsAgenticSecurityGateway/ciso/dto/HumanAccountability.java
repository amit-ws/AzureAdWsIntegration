package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.util.List;

/**
 * The reverse view: everything a verified human is answerable for — every agent acting on their behalf, whether
 * driven directly or reached down an OBO delegation chain. Answers "if this person is compromised or offboarded,
 * which agents (and how) act for them?" Enriched from the human registry where available.
 */
public record HumanAccountability(
        String humanId,
        String username,
        String fullName,
        String email,
        String status,
        boolean verified,
        long totalRequests,
        int directAgents,
        int delegatedAgents,
        List<AgentLink> agents) {

    /**
     * One agent acting for the human. {@code attribution} is DIRECT (human drove it), DELEGATED (reached through
     * other agents), or MIXED (both seen).
     */
    public record AgentLink(
            String agentName,
            long requests,
            String attribution) {}
}
