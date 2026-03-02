package com.ws.wsAgenticSecurityGateway.wsClient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for MCP server configuration — enriched with live connection status.
 *
 * <p>Header values containing auth/token/secret/key/password/bearer are masked
 * for security. The original values are never exposed via the API.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServerConfigResponse {

    // ── Config fields ───────────────────────────────────────────────────
    private UUID id;
    private String serverName;
    private String type;
    private String url;
    private Map<String, String> headers;
    private Map<String, Object> serverConfig;
    private Integer timeoutSeconds;
    private Boolean enabled;
    private Boolean autoConnect;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── Live connection status (enriched) ────────────────────────────────
    private boolean connected;
    private String connectionSessionId;
    private LocalDateTime connectedAt;
    private int toolCount;
    private int resourceCount;
    private int promptCount;
}
