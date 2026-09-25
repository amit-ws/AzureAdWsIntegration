package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

/**
 * The peak sensitivity of one capability (tool / prompt / resource) of a server — keyed by exact server id +
 * capability type + name.
 */
public record ToolSensitivity(
        String serverId,
        String serverName,
        String capabilityType,
        String capabilityName,
        String peakSensitivity) {
}
