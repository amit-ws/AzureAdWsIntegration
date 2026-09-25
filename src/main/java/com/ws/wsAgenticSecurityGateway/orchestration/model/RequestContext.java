package com.ws.wsAgenticSecurityGateway.orchestration.model;

/**
 * The protocol-neutral inbound request context the spine governs against — the request-side mirror of
 * {@link CapabilityResult} on the response side. It replaces the MCP {@code McpSyncServerExchange} that the
 * spine ({@code HopOrchestrator}, {@code PolicyContextBuilder}) used to read directly, so those classes no
 * longer depend on any protocol SDK.
 *
 * <p>Each protocol's inbound boundary builds one of these from its own request handle (MCP:
 * {@code McpRequestContextFactory}; A2A later: its own factory), extracting:
 * <ul>
 *   <li>{@link #clientInfo()} — the caller's name/version (nullable);</li>
 *   <li>{@link #sessionId()} — the transport session id, if the protocol has one (nullable);</li>
 *   <li>{@link #attributes()} — a by-key accessor over the per-request attributes the transport's context
 *       extractor populated (JWT claims, client ip, trace id, request id, http headers, …). The spine reads
 *       these by key only, so this is a lookup, not a map — see {@link RequestAttributes}.</li>
 * </ul>
 *
 * <p>{@code attributes} reads through to the transport's live per-request context by key — each boundary
 * adapts its own carrier onto {@link RequestAttributes} — so no attributes are copied or materialized just
 * to be governed. The keys the spine reads are the contract in {@link RequestAttributeKeys}.
 */
public record RequestContext(ClientInfo clientInfo, String sessionId, RequestAttributes attributes) {

    public RequestContext {
        attributes = attributes != null ? attributes : RequestAttributes.EMPTY;
    }

    /** An empty context — used when a protocol has no request handle for this leg. */
    public static RequestContext empty() {
        return new RequestContext(null, null, RequestAttributes.EMPTY);
    }
}
