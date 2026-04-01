package com.ws.wsAgenticSecurityGateway.capabilityRegistry.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.UUID;

@Getter
@Builder
@ToString
public class CapabilityDescriptor {

    private final String publicName;

    private final String originalName;

    private final String serverConfigName;

    private final CapabilityType type;

    private final String description;

    private final String inputSchema;

    private final String resourceUri;

    private final String mimeType;

    private final String arguments;

    private final UUID serverId;

    public enum CapabilityType {
        TOOL,
        RESOURCE,
        PROMPT
    }
}
