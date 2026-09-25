package com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Slf4j
public class GatewayShutdownHook {

    private final McpSessionManager sessionManager;
    private final GatewayAgentSessionRepository agentSessionRepository;
    private final GatewayAuditService auditService;

    public GatewayShutdownHook(McpSessionManager sessionManager,
                               GatewayAgentSessionRepository agentSessionRepository,
                               GatewayAuditService auditService) {
        this.sessionManager = sessionManager;
        this.agentSessionRepository = agentSessionRepository;
        this.auditService = auditService;
    }

    @EventListener(ContextClosedEvent.class)
    @Order(0)
    @Transactional
    public void onShutdown(ContextClosedEvent event) {
 log.info("Gateway shutting down — cleaning up all sessions...");

        try {
            List<GatewayAgentSessionEntity> agentSessions = agentSessionRepository.findByStatus("CONNECTED");
            for (GatewayAgentSessionEntity session : agentSessions) {
                try {
                    auditService.auditServerSessionDisconnectedSync(
                            session.getSessionId(), session.getAgent().getAgentName());
                } catch (Exception e) {
 log.error("⚠ Failed shutdown audit for agent session '{}': {}",
                            session.getSessionId(), e.getMessage());
                }
            }
            agentSessionRepository.markAllDisconnected();
 log.info("{} agent session(s) marked DISCONNECTED in DB", agentSessions.size());
        } catch (Exception e) {
 log.error("⚠ Failed to mark agent sessions DISCONNECTED: {}", e.getMessage());
        }

        sessionManager.shutdown();
    }
}
