package com.ws.wsAgenticSecurityGateway.wsServer.session;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for agent-facing session lifecycle management.
 *
 * <p>Controls the idle session reaper that detects and cleans up
 * stale agent sessions (e.g., agent crashed, network dropped).
 */
@Component
@ConfigurationProperties(prefix = "ws.gateway.session")
@Data
public class SessionLifecycleProperties {

    /** Mark sessions as DISCONNECTED after this many minutes of inactivity. */
    private int idleTimeoutMinutes = 30;

    /** How often (in seconds) the reaper scans for stale sessions. */
    private int reaperIntervalSeconds = 60;

    /** Master switch to enable/disable the session reaper. */
    private boolean reaperEnabled = true;
}
