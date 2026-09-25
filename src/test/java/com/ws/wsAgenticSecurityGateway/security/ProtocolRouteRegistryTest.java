package com.ws.wsAgenticSecurityGateway.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the security-critical matching logic: only paths under a registered protocol prefix are treated as
 * data-plane traffic (and thus authenticated). A path an adapter has NOT registered must NOT match, and an
 * unregistered new prefix (e.g. a future {@code /a2a}) must be matched only once its adapter registers it.
 */
class ProtocolRouteRegistryTest {

    private final ProtocolRouteRegistry registry = new ProtocolRouteRegistry();

    @Test
    void registeredPrefixMatchesItsPaths() {
        registry.registerProtectedPrefix("/mcp");
        registry.registerProtectedPrefix("/stateless/mcp");

        assertThat(registry.isProtected("/mcp")).isTrue();
        assertThat(registry.isProtected("/mcp/anything")).isTrue();
        assertThat(registry.isProtected("/stateless/mcp")).isTrue();
    }

    @Test
    void unregisteredPathIsNotProtected() {
        registry.registerProtectedPrefix("/mcp");

        assertThat(registry.isProtected("/a2a")).isFalse();
        assertThat(registry.isProtected("/api/admin/agents")).isFalse();
        assertThat(registry.isProtected("/")).isFalse();
    }

    @Test
    void newAdapterIsProtectedOnlyAfterItRegisters() {
        assertThat(registry.isProtected("/a2a/skill")).isFalse();   // before registration → not matched
        registry.registerProtectedPrefix("/a2a");
        assertThat(registry.isProtected("/a2a/skill")).isTrue();    // after registration → matched
    }

    @Test
    void handlesNullAndBlankSafely() {
        registry.registerProtectedPrefix(null);
        registry.registerProtectedPrefix("  ");
        assertThat(registry.prefixes()).isEmpty();
        assertThat(registry.isProtected(null)).isFalse();
    }
}
