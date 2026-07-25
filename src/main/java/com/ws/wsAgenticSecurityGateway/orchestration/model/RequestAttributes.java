package com.ws.wsAgenticSecurityGateway.orchestration.model;

/**
 * A protocol-neutral, read-only lookup over a request's per-request attributes (JWT claims, client ip,
 * trace id, request id, http headers, …). The spine reads these by key only; it never enumerates them,
 * so a single-method accessor is all it needs — and all any transport can be asked to provide.
 *
 * <p>Each protocol's inbound boundary adapts its own carrier onto this: MCP adapts the transport context's
 * {@code get}; an A2A boundary would adapt its own request metadata. Keeping it a functional accessor (not a
 * {@code Map}) means the spine depends on no protocol's context type, and no boundary is forced to
 * materialize its attributes into a map just to be governed.
 */
@FunctionalInterface
public interface RequestAttributes {

    /** The attribute for {@code key}, or {@code null} if absent. */
    Object get(String key);

    /** An accessor that has no attributes. */
    RequestAttributes EMPTY = key -> null;
}
