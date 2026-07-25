package com.ws.wsAgenticSecurityGateway.orchestration.model;

import java.util.Map;

/**
 * The protocol-agnostic unit of work the orchestration spine operates on.
 *
 * <p>A {@code Hop} carries a protocol-neutral {@link RequestContext} as its request handle — each
 * protocol's inbound boundary builds one from its own request object (MCP from the exchange, A2A from
 * its request) — so the spine governs a hop without depending on any protocol SDK.
 *
 * <p>{@link #serverName} and {@link #originalName} are populated by the spine after the
 * capability-registry lookup via {@link #resolve(String, String)}.
 */
public final class Hop {

    private final CapabilityType capabilityType;
    private final String publicName;
    private final Map<String, Object> arguments; // tool / prompt args; null for resource
    private final String resourceUri;            // resource only; null otherwise
    private final RequestContext requestContext;

    private String serverName;   // resolved after registry lookup
    private String originalName; // resolved (tool/prompt name); resource uri for RESOURCE
    private String traceId;      // request-scoped umbrella id (whole journey); set by the spine in handle()
    private String protocol;     // which adapter handles this hop (MCP/A2A); set by the spine in handle()

    public Hop(CapabilityType capabilityType,
               String publicName,
               Map<String, Object> arguments,
               String resourceUri,
               RequestContext requestContext) {
        this.capabilityType = capabilityType;
        this.publicName = publicName;
        this.arguments = arguments;
        this.resourceUri = resourceUri;
        this.requestContext = requestContext;
    }

    public CapabilityType capabilityType() { return capabilityType; }

    public String publicName() { return publicName; }

    public Map<String, Object> arguments() { return arguments; }

    public String resourceUri() { return resourceUri; }

    public RequestContext requestContext() { return requestContext; }

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
