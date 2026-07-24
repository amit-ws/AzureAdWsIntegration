package com.ws.wsAgenticSecurityGateway.sts.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeDeriverTest {

    private final ScopeDeriver deriver = new ScopeDeriver();

    @Test
    void derivesLeastPrivilegeScopeForOneCapabilityOnOneServer() {
        assertThat(deriver.derive("MCP", "github", "github_get_me", "TOOL"))
                .isEqualTo("mcp:tool:github:github_get_me");
    }

    @Test
    void protocolPrefixIsAdapterSupplied_soA2aMintsItsOwnScheme() {
        assertThat(deriver.derive("A2A", "planner", "decompose", "SKILL"))
                .isEqualTo("a2a:skill:planner:decompose");
    }

    @Test
    void handlesNullsSafely_defaultingProtocolToMcp() {
        assertThat(deriver.derive(null, null, null, null))
                .isEqualTo("mcp:capability:unknown:unknown");
    }
}
