package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.util.List;

/**
 * The CISO → Dashboard "Top MCP tools by call volume" table (#74) — prioritise controls where usage and data
 * sensitivity intersect. One row per capability: its server, call volume, the peak data class observed, the count
 * of denied calls (matched to the PDP ledger by exact resource name — unmatched shows 0), and a risk band.
 */
public record TopTools(List<ToolRow> tools) {

    public record ToolRow(
            String tool,
            String capabilityType,
            String server,
            String protocol,
            long calls,
            String dataClass,     // friendly peak sensitivity (Public / Internal / Confidential / Restricted)
            long denied,
            String risk) {}       // CRITICAL | HIGH | MEDIUM | LOW
}
