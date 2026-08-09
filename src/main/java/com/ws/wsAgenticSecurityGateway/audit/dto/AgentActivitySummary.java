package com.ws.wsAgenticSecurityGateway.audit.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The 360° activity picture for one agent, aggregated from the audit trail — the answer to "what did this
 * agent do". OUTBOUND = calls this agent made (to other agents over A2A, to MCP servers over MCP). INBOUND =
 * calls other agents made TO this agent (its own skills invoked). Each edge carries the governed outcome
 * (allowed vs denied), the peer, the capability, a call count and when it was last seen.
 */
public record AgentActivitySummary(
        String agentKey,
        List<Edge> outbound,
        List<Edge> inbound,
        Totals totals) {

    /** One rolled-up relationship: protocol, the peer (target for outbound / caller for inbound), the
     *  capability, total calls, allowed vs denied, and the most recent timestamp. */
    public record Edge(
            String protocol,     // "A2A" | "MCP"
            String peer,
            String capability,
            long calls,
            long allowed,
            long denied,
            LocalDateTime lastAt) {}

    public record Totals(long calls, long allowed, long denied, int peers) {}
}
