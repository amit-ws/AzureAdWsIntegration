package com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.health;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ws.gateway.southbound")
@Data
public class WsClientHealthProperties {

    private int healthCheckIntervalSeconds = 120;

    private boolean healthCheckEnabled = true;

    private boolean autoReconnect = true;

    private int maxReconnectAttempts = 3;
}
