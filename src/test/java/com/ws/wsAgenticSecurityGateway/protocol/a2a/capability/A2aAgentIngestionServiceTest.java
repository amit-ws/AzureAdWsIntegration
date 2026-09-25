package com.ws.wsAgenticSecurityGateway.protocol.a2a.capability;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.protocol.a2a.outbound.A2aAgentDirectory;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent-registration guardrails (via the fetch-free card path): a base URL already held by a DIFFERENT agent is
 * rejected before anything is registered, and the agent-registered audit records new-vs-refresh correctly.
 */
class A2aAgentIngestionServiceTest {

    private final A2aAgentDirectory directory = mock(A2aAgentDirectory.class);
    private final A2aCapabilityRegistrar registrar = mock(A2aCapabilityRegistrar.class);
    private final AgentRegistryService agentRegistryService = mock(AgentRegistryService.class);
    private final GatewayAuditService auditService = mock(GatewayAuditService.class);
    private final AgentCard card = mock(AgentCard.class);

    private final A2aAgentIngestionService service =
            new A2aAgentIngestionService(directory, registrar, agentRegistryService, auditService);

    @BeforeEach
    void setUp() {
        when(card.name()).thenReturn("market-data");
        when(registrar.register(anyString(), any())).thenReturn(3);
    }

    @Test
    void newAgent_registers_andAuditsAsNew() {
        when(agentRegistryService.getA2aAgentByUrl("http://md")).thenReturn(Optional.empty());
        when(agentRegistryService.getA2aAgent("market-data")).thenReturn(Optional.empty());   // not yet registered

        A2aAgentIngestionService.IngestResult r = service.ingestFromCard("market-data", "http://md", card);

        assertThatSkills(r);
        verify(registrar).register("market-data", card);
        verify(agentRegistryService).registerA2aEndpoint("market-data", "http://md");
        verify(auditService).auditAgentRegistered("market-data", "http://md", "market-data", 3, true);   // newly
    }

    @Test
    void existingAgent_reingest_auditsAsRefresh() {
        GatewayAgentEntity same = GatewayAgentEntity.builder().agentName("market-data").build();
        when(agentRegistryService.getA2aAgentByUrl("http://md")).thenReturn(Optional.of(same));   // same name = ok
        when(agentRegistryService.getA2aAgent("market-data")).thenReturn(Optional.of(same));      // already exists

        service.ingestFromCard("market-data", "http://md", card);

        verify(auditService).auditAgentRegistered("market-data", "http://md", "market-data", 3, false);   // refresh
    }

    @Test
    void sameUrl_differentName_isRejected_beforeAnyRegistration() {
        GatewayAgentEntity other = GatewayAgentEntity.builder().agentName("other-agent").build();
        when(agentRegistryService.getA2aAgentByUrl("http://md")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.ingestFromCard("market-data", "http://md", card))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered to agent 'other-agent'");

        verify(registrar, never()).register(anyString(), any());            // nothing registered
        verify(agentRegistryService, never()).registerA2aEndpoint(anyString(), anyString());
        verify(auditService, never()).auditAgentRegistered(anyString(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    private static void assertThatSkills(A2aAgentIngestionService.IngestResult r) {
        org.assertj.core.api.Assertions.assertThat(r.skillsRegistered()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(r.agentName()).isEqualTo("market-data");
    }
}
