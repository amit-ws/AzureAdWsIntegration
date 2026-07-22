package com.ws.wsAgenticSecurityGateway.sts.service;

import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.orchestration.model.CapabilityType;
import com.ws.wsAgenticSecurityGateway.orchestration.model.Hop;
import com.ws.wsAgenticSecurityGateway.sts.model.ActChain;
import com.ws.wsAgenticSecurityGateway.sts.model.MintedToken;
import com.ws.wsAgenticSecurityGateway.sts.model.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HopTokenMinterTest {

    private final ScopeDeriver scopeDeriver = mock(ScopeDeriver.class);
    private final StsService stsService = mock(StsService.class);
    private final McpAuditService auditService = mock(McpAuditService.class);

    private final HopTokenMinter minter = new HopTokenMinter(scopeDeriver, stsService, auditService);

    private Hop hop;
    private ActChain chain;

    @BeforeEach
    void setUp() {
        hop = new Hop(CapabilityType.TOOL, "github_get_me", Map.of(), null, null);
        hop.resolve("github", "get_me");
        chain = new ActChain(List.of(
                Principal.human("sarah", null, null, true),
                Principal.agent("agent-uuid", "client", true)));
    }

    @Test
    void skipsMinting_whenNoTenantResolved() {
        when(auditService.resolveTenant("s")).thenReturn("unknown");

        MintedToken result = minter.mintForHop(hop, "s", chain, "rid", "corr", 1);

        assertThat(result).isNull();
        verify(stsService, never()).mint(any());
        verify(auditService, never()).auditStsTokenMinted(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void mintsAndAudits_whenTenantResolved() {
        when(auditService.resolveTenant("s")).thenReturn("amitdev.local");
        when(scopeDeriver.derive("github", "github_get_me", "TOOL"))
                .thenReturn("mcp:tool:github:github_get_me");
        MintedToken token = new MintedToken("jwt", "jti-1", "kid", Instant.now().plusSeconds(120));
        when(stsService.mint(any())).thenReturn(token);

        MintedToken result = minter.mintForHop(hop, "s", chain, "rid", "corr", 1);

        assertThat(result).isSameAs(token);
        verify(stsService).mint(any());
        verify(auditService).auditStsTokenMinted(eq("corr"), eq("s"), eq("amitdev.local"), eq("github"),
                eq("github_get_me"), eq("TOOL"), eq("jti-1"), eq("mcp:tool:github:github_get_me"),
                any(), any(), eq("rid"), any(), eq(1));
    }

    @Test
    void failClosed_propagatesStsMintException() {
        when(auditService.resolveTenant("s")).thenReturn("amitdev.local");
        when(scopeDeriver.derive(any(), any(), any())).thenReturn("scope");
        when(stsService.mint(any())).thenThrow(new StsMintException("boom"));

        assertThatThrownBy(() -> minter.mintForHop(hop, "s", chain, "rid", "corr", 1))
                .isInstanceOf(StsMintException.class);
    }
}
