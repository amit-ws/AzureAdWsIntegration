package com.ws.wsAgenticSecurityGateway.agentRegistry.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.event.BlockedSessionEvent;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayHumanUserRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayNhiRepository;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Governance-enforcement tests for the Stage-2 deprovision lifecycle (2a.2): deprovisioning sets the
 * terminal {@code status=DEPROVISIONED}, tears down live sessions (so in-flight requests are refused), and
 * exposes the lifecycle status for the request-time guard.
 */
class AgentRegistryServiceTest {

    private final GatewayAgentRepository agentRepository = mock(GatewayAgentRepository.class);
    private final GatewayAgentSessionRepository sessionRepository = mock(GatewayAgentSessionRepository.class);
    private final GatewayHumanUserRepository humanUserRepository = mock(GatewayHumanUserRepository.class);
    private final GatewayNhiRepository nhiRepository = mock(GatewayNhiRepository.class);
    private final McpAuditService auditService = mock(McpAuditService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final AgentRegistryService service = new AgentRegistryService(
            agentRepository, sessionRepository, humanUserRepository, nhiRepository, auditService, eventPublisher);

    @Test
    void deprovision_setsTerminalStatus_terminatesSessions_andAudits() {
        UUID agentId = UUID.randomUUID();
        GatewayAgentEntity agent = GatewayAgentEntity.builder()
                .id(agentId).agentName("claude-desktop").agentVersion("1.0").status("ACTIVE").build();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(agentRepository.saveAndFlush(agent)).thenReturn(agent);

        GatewayAgentSessionEntity session = mock(GatewayAgentSessionEntity.class);
        when(session.getSessionId()).thenReturn("s1");
        when(sessionRepository.findConnectedByAgentId(agentId)).thenReturn(List.of(session));

        AgentRegistryService.AgentBlockResult result =
                service.deprovisionAgent(agentId, "admin@ws", "10.0.0.1");

        assertThat(agent.getStatus()).isEqualTo("DEPROVISIONED");
        assertThat(result.sessionsTerminated()).isEqualTo(1);
        verify(sessionRepository).markDisconnected("s1");
        verify(eventPublisher).publishEvent(any(BlockedSessionEvent.class));
        verify(auditService).auditAgentDeprovisioned(
                eq(agentId), eq("claude-desktop"), eq("1.0"), eq("ACTIVE"), eq("admin@ws"), eq("10.0.0.1"), eq(1));
    }

    @Test
    void deprovision_throwsWhenAgentNotFound() {
        UUID missing = UUID.randomUUID();
        when(agentRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deprovisionAgent(missing, "admin", "ip"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lifecycleStatusForSession_fallsBackToDb_whenNotCached() {
        when(sessionRepository.findAgentStatusBySessionId("s1")).thenReturn(Optional.of("DEPROVISIONED"));

        assertThat(service.getAgentLifecycleStatusForSession("s1")).isEqualTo("DEPROVISIONED");
    }

    @Test
    void lifecycleStatusForSession_isNullForNullSession() {
        assertThat(service.getAgentLifecycleStatusForSession(null)).isNull();
    }
}
