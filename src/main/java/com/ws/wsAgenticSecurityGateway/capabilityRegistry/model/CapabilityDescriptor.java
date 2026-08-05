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
     * The protocol/connector that reaches this capability's target — {@code "MCP"} today, {@code "A2A"} for an
     * agent skill, or a partner-connector key later. The spine routes the hop to the matching
     * {@code ProtocolAdapter} by this value and derives the OBO token scope from it. Defaults to {@code "MCP"}
     * so every existing registration is unaffected.
     */
    @Builder.Default
    private final String protocol = "MCP";

    /**
     * The kind of capability. Protocol-neutral and intentionally extensible: a new protocol adds its own kind
     * additively without touching existing kinds or the spine. Query the index by kind via
     * {@code CapabilityRegistryService.getByType(...)} rather than a bespoke per-kind method, so a new kind
     * needs no new query method.
     *
     * <p>{@code SKILL} is the A2A agent→agent unit — kept distinct from {@link #TOOL} (rather than mapped onto
     * it) so agent-skill policy, audit, and dispatch stay first-class.
     */
    public enum CapabilityType {
        TOOL,
        RESOURCE,
        PROMPT,
        SKILL
    }
}
