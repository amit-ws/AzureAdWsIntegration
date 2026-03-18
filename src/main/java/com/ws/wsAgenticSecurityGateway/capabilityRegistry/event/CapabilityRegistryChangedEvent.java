package com.ws.wsAgenticSecurityGateway.capabilityRegistry.event;

/**
 * Application event emitted when the capability registry changes at runtime.
 *
 * <p>Used by the WS Server MCP server to refresh exposed
 * tools/prompts/resources without requiring a gateway restart.
 */
public class CapabilityRegistryChangedEvent {

    private final String reason;
    private final String serverConfigName;

    public CapabilityRegistryChangedEvent(String reason, String serverConfigName) {
        this.reason = reason;
        this.serverConfigName = serverConfigName;
    }

    public String getReason() {
        return reason;
    }

    public String getServerConfigName() {
        return serverConfigName;
    }
}
