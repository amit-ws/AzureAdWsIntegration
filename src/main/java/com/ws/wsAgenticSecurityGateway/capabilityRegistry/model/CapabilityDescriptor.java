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

    /**
     * The kind of capability. Protocol-neutral and intentionally extensible: a new protocol adds its own kind
     * additively (e.g. an A2A adapter would add {@code SKILL}) without touching existing kinds or the spine.
     * Query the index by kind via {@code CapabilityRegistryService.getByType(...)} rather than a bespoke
     * per-kind method, so a new kind needs no new query method.
     *
     * <p>A protocol whose unit maps cleanly onto an existing kind may reuse it instead of adding one — e.g. an
     * A2A skill is invocable like a {@link #TOOL}, so it can register as {@code TOOL} with no enum change.
     */
    public enum CapabilityType {
        TOOL,
        RESOURCE,
        PROMPT
    }
}
