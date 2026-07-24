package com.ws.wsAgenticSecurityGateway.orchestration.model;

import io.modelcontextprotocol.server.McpSyncServerExchange;

import java.util.Map;

/**
 * The protocol-agnostic unit of work the orchestration spine operates on.
 *
 * <p><b>Stage-0 scope decision (deliberate, not residue):</b> a {@code Hop} currently carries
 * the MCP {@link McpSyncServerExchange} as its request context. Fully neutralizing the exchange
 * into a protocol-independent {@code RequestContext} is intentionally deferred to the A2A phase,
 * when a second protocol's real requirements can shape that abstraction correctly — designing it
 * now, against MCP alone, would be guesswork that gets reworked. See
 * {@code docs/stage-0-refactor-plan.md} → "KEY SCOPE DECISION".
 *
 * <p>{@link #serverName} and {@link #originalName} are populated by the spine after the
 * capability-registry lookup via {@link #resolve(String, String)}.
 */
public final class Hop {

    private final CapabilityType capabilityType;
    private final String publicName;
    private final Map<String, Object> arguments; // tool / prompt args; null for resource
    private final String resourceUri;            // resource only; null otherwise
    private final McpSyncServerExchange exchange;

    private String serverName;   // resolved after registry lookup
    private String originalName; // resolved (tool/prompt name); resource uri for RESOURCE
    private String traceId;      // request-scoped umbrella id (whole journey); set by the spine in handle()
    private String protocol;     // which adapter handles this hop (MCP/A2A); set by the spine in handle()

    public Hop(CapabilityType capabilityType,
               String publicName,
               Map<String, Object> arguments,
               String resourceUri,
               McpSyncServerExchange exchange) {
        this.capabilityType = capabilityType;
        this.publicName = publicName;
        this.arguments = arguments;
        this.resourceUri = resourceUri;
        this.exchange = exchange;
    }

    public CapabilityType capabilityType() { return capabilityType; }

    public String publicName() { return publicName; }

    public Map<String, Object> arguments() { return arguments; }

    public String resourceUri() { return resourceUri; }

    public McpSyncServerExchange exchange() { return exchange; }

    public String serverName() { return serverName; }

    public String originalName() { return originalName; }

    public String traceId() { return traceId; }

    public String protocol() { return protocol; }

    /** The request-scoped trace id (umbrella over all legs); set by the spine at dispatch. */
    public void setTraceId(String traceId) { this.traceId = traceId; }

    /** The protocol handling this hop ({@code MCP}/{@code A2A}); set by the spine at dispatch. */
    public void setProtocol(String protocol) { this.protocol = protocol; }

    /** Set by the spine after the capability-registry lookup resolves the public name. */
    public void resolve(String serverName, String originalName) {
        this.serverName = serverName;
        this.originalName = originalName;
    }
}
