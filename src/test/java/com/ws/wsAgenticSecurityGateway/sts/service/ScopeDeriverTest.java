package com.ws.wsAgenticSecurityGateway.sts.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeDeriverTest {

    private final ScopeDeriver deriver = new ScopeDeriver();

    @Test
    void derivesLeastPrivilegeScopeForOneCapabilityOnOneServer() {
        assertThat(deriver.derive("github", "github_get_me", "TOOL"))
                .isEqualTo("mcp:tool:github:github_get_me");
    }

    @Test
    void handlesNullsSafely() {
        assertThat(deriver.derive(null, null, null))
                .isEqualTo("mcp:capability:unknown:unknown");
    }
}
