package com.ws.wsAgenticSecurityGateway.wsClient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServerConfigResponse {

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

    private boolean connected;
    private String connectionSessionId;
    private LocalDateTime connectedAt;
    private int toolCount;
    private int resourceCount;
    private int promptCount;
}
