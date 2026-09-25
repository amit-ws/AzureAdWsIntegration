package com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a "Test Connection" dry-run probe against a candidate (or saved) MCP server config. The probe
 * performs a real MCP initialize handshake and lists advertised capabilities, then tears the connection down
 * without persisting anything — so an admin can validate a server before committing it.
 *
 * <p>{@code ok=false} with a populated {@code error} means the probe ran but the server was unreachable /
 * rejected the handshake; the HTTP call itself still returns 200 so the UI can render the reason.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServerConfigTestResponse {

    /** True if the MCP handshake completed and capabilities were listed. */
    private boolean ok;

    /** Echoed server name (may be null for a brand-new unsaved config). */
    private String serverName;

    /** Echoed target URL that was probed. */
    private String url;

    /** Wall-clock time for handshake + capability listing, in milliseconds (0 on failure). */
    private long latencyMs;

    /** Server-reported implementation name from the initialize handshake (null on failure). */
    private String serverInfoName;

    /** Server-reported implementation version (null on failure). */
    private String serverInfoVersion;

    /** Number of tools the server advertises (null on failure). */
    private Integer toolCount;

    /** Number of resources the server advertises (null on failure). */
    private Integer resourceCount;

    /** Number of prompts the server advertises (null on failure). */
    private Integer promptCount;

    /** Human-readable failure reason; null when {@code ok=true}. */
    private String error;
}
