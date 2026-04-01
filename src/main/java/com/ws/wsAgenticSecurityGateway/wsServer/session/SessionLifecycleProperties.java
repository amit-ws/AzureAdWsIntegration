package com.ws.wsAgenticSecurityGateway.wsServer.session;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ws.gateway.session")
@Data
public class SessionLifecycleProperties {

    private int idleTimeoutMinutes = 30;

    private int reaperIntervalSeconds = 60;

    private boolean reaperEnabled = true;
}
