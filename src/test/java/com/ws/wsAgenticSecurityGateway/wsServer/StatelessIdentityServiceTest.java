package com.ws.wsAgenticSecurityGateway.protocol.mcp.inbound;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayHumanUserEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.authConfig.repository.GatewayAuthConfigRepository;
import com.ws.wsAgenticSecurityGateway.sts.service.StsRevocationService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the stateless per-request identity bootstrap (Delta 2): resolve agent/human/tenant from the JWT on
 * the transport context, link it in-memory (no DB session), and refuse blocked agents at the door.
 */
class StatelessIdentityServiceTest {

    private final AgentRegistryService agentRegistry = mock(AgentRegistryService.class);
    private final GatewayAuditService auditService = mock(GatewayAuditService.class);
    private final GatewayAuthConfigRepository authConfigRepository = mock(GatewayAuthConfigRepository.class);
    // Nothing revoked by default (mock returns false) — these tests cover the identity-bootstrap paths.
    private final StsRevocationService revocationService = mock(StsRevocationService.class);

    private final StatelessIdentityService service =
            new StatelessIdentityService(agentRegistry, auditService, authConfigRepository, revocationService);

    @Test
    void bootstrap_resolvesJwtIdentity_linksInMemory_withTenantFromHeader() {
        UUID agentId = UUID.randomUUID();
        UUID humanId = UUID.randomUUID();
        when(agentRegistry.discoverAgent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(GatewayAgentEntity.builder().id(agentId).build());
        when(agentRegistry.discoverHumanUser(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(GatewayHumanUserEntity.builder().id(humanId).build());

        McpTransportContext ctx = McpTransportContext.create(Map.of(
                "jwtSubject", "sarah@acme.com",
                "agentClientId", "claude-desktop",
                "tokenType", "HUMAN_DELEGATED",
                "idpIssuer", "https://kc/realms/ws",
                "_httpHeaders", Map.of("X-WS-Tenant", "amitdev.local")));

        service.bootstrap(ctx, "stateless-1");

        // agent + human resolved from the JWT, linked in-memory (no DB session), authIdentity = subject
        verify(agentRegistry).linkSessionIdentity("stateless-1", agentId, humanId, null, "sarah@acme.com");
        // tenant (from the X-WS-Tenant header) cached for STS/audit resolution
        verify(auditService).registerSessionIdentity(org.mockito.ArgumentMatchers.eq("stateless-1"), any());
    }

    @Test
    void cleanup_dropsPerRequestIdentity() {
        service.cleanup("stateless-1");
        // Agent-map link drops immediately (only synchronous readers).
        verify(agentRegistry).unlinkSession("stateless-1");
        // Audit identity is evicted after a grace window (0s in unit tests) so async audit rows keep their
        // attribution — verify it lands, but allow for the deferred scheduling.
        verify(auditService, org.mockito.Mockito.timeout(2000)).evictSessionIdentity("stateless-1");
    }

    @Test
    void bootstrap_propagatesBlockedAgent_soTheRequestIsRefused() {
        when(agentRegistry.discoverAgent(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new AgentRegistryService.AgentBlockedException("blocked"));

        McpTransportContext ctx = McpTransportContext.create(Map.of(
                "agentClientId", "rogue-agent", "tokenType", "AUTOMATED_AGENT"));

        assertThatThrownBy(() -> service.bootstrap(ctx, "stateless-2"))
                .isInstanceOf(AgentRegistryService.AgentBlockedException.class);
    }
}
