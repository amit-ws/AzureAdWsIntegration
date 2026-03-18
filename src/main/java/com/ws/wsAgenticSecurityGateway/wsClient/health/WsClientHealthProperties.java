package com.ws.wsAgenticSecurityGateway.wsClient.health;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for WS Client-side (Gateway → MCP Server) health monitoring.
 */
@Component
@ConfigurationProperties(prefix = "ws.gateway.southbound")
@Data
public class WsClientHealthProperties {

    /** How often (in seconds) to check enterprise MCP server health. */
    private int healthCheckIntervalSeconds = 120;

    /** Master switch to enable/disable health checking. */
    private boolean healthCheckEnabled = true;

    /** Whether to automatically attempt reconnection on server failure. */
    private boolean autoReconnect = true;

    /** Maximum number of reconnection attempts before giving up. */
    private int maxReconnectAttempts = 3;
}
