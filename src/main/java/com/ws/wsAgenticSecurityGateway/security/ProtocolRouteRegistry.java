package com.ws.wsAgenticSecurityGateway.security;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Registry of inbound route prefixes that carry protocol data-plane traffic and therefore require
 * authentication. Each protocol adapter's transport registers its own prefix(es) at startup — MCP declares
 * {@code /mcp} and {@code /mcp-stateless}; an A2A adapter will declare its own endpoint later — so
 * {@link GatewaySecurityConfig} authenticates them <b>without hardcoding any single protocol's paths</b>.
 *
 * <p>This closes a real security gap: with the old literal {@code path.startsWith("/mcp")} matcher, a new
 * adapter's endpoint (e.g. {@code /a2a}) would silently fall through to the permissive catch-all chain and
 * be served unauthenticated. Here a new adapter is protected the moment it registers its prefix.
 *
 * <p>Registration happens during bean initialization (before the embedded server accepts requests), so the
 * matcher — which reads this registry per request — never sees an empty set while traffic is being served.
 */
@Component
public class ProtocolRouteRegistry {

    private final Set<String> protectedPrefixes = new CopyOnWriteArraySet<>();

    /** Register an inbound path prefix (e.g. {@code /mcp}) whose traffic must be authenticated. */
    public void registerProtectedPrefix(String prefix) {
        if (prefix != null && !prefix.isBlank()) {
            protectedPrefixes.add(prefix);
        }
    }

    /** Whether the request path falls under any registered protocol data-plane prefix. */
    public boolean isProtected(String requestPath) {
        if (requestPath == null) {
            return false;
        }
        for (String prefix : protectedPrefixes) {
            if (requestPath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The currently registered prefixes (immutable snapshot). */
    public Set<String> prefixes() {
        return Set.copyOf(protectedPrefixes);
    }
}
