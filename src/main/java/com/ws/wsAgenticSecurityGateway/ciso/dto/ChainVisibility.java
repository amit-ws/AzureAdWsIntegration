package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The CISO → Dashboard "Human → agent → MCP chain visibility" table (#73) — the gateway is the shared observation
 * point for every request path, so each row reconstructs one governed trace: the root human, the agent that acted,
 * an optional agent-to-agent (A2A) hop, the downstream MCP server, and the tool/skill reached — with the peak risk
 * on the path and when it was last seen. Built from the real classification hops stitched by trace id (our
 * OBO/ActChain lineage); nothing is inferred beyond what the ledger records.
 */
public record ChainVisibility(List<ChainRow> chains) {

    public record ChainRow(
            String traceId,
            String human,
            String humanKind,      // HUMAN | NHI (root principal kind)
            String agent,
            String agentToAgent,   // null / "—" when the agent called the server directly
            String server,
            String tool,
            String capabilityType,
            String protocol,
            String risk,           // CRITICAL | HIGH | MEDIUM | LOW (peak sensitivity on the path)
            LocalDateTime seen) {}
}
