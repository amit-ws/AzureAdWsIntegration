package com.ws.wsAgenticSecurityGateway.orchestration.model;

/**
 * The protocol-neutral identity of the caller on the inbound leg — "who is calling", as name and version.
 *
 * <p>Each protocol's inbound boundary maps its own notion of caller identity onto this: MCP maps the
 * {@code Implementation} from the client handshake; an A2A adapter would map its peer's agent card. The
 * spine reads only these two fields, so it never references any protocol's client-info type.
 *
 * <p>Either field may be {@code null} (an unknown or partial handshake); the spine applies its own
 * fallbacks (e.g. the JWT {@code client_id}) when {@link #name()} is absent.
 */
public record ClientInfo(String name, String version) {
}
