package com.ws.wsAgenticSecurityGateway.capabilityRegistry.event;

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
