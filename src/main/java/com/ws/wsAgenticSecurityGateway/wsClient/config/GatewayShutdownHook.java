package com.ws.wsAgenticSecurityGateway.wsClient.config;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Handles graceful shutdown of ALL sessions — both WS Server-side (agent) and WS Client-side (MCP server).
 *
 * <p>Uses {@link ContextClosedEvent} instead of {@code @PreDestroy} so that
 * the EntityManager, transaction manager, and async executor are still alive
 * when we perform DB updates, audit logging, and close connections.
 *
 * <p>{@code @Order(0)} ensures this runs before other shutdown listeners.
 */
@Component
@Slf4j
public class GatewayShutdownHook {

    private final McpSessionManager sessionManager;
    private final GatewayAgentSessionRepository agentSessionRepository;
    private final McpAuditService auditService;

    public GatewayShutdownHook(McpSessionManager sessionManager,
                               GatewayAgentSessionRepository agentSessionRepository,
                               McpAuditService auditService) {
        this.sessionManager = sessionManager;
        this.agentSessionRepository = agentSessionRepository;
        this.auditService = auditService;
    }

    @EventListener(ContextClosedEvent.class)
    @Order(0)
    @Transactional
    public void onShutdown(ContextClosedEvent event) {
        log.info("🛑 Gateway shutting down — cleaning up all sessions...");

        // 1. WS Server-side: audit + mark all agent sessions DISCONNECTED
        try {
            List<GatewayAgentSessionEntity> agentSessions = agentSessionRepository.findByStatus("CONNECTED");
            for (GatewayAgentSessionEntity session : agentSessions) {
                try {
                    auditService.auditServerSessionDisconnectedSync(
                            session.getSessionId(), session.getAgent().getAgentName());
                } catch (Exception e) {
                    log.error("⚠️  Failed shutdown audit for agent session '{}': {}",
                            session.getSessionId(), e.getMessage());
                }
            }
            agentSessionRepository.markAllDisconnected();
            log.info("💾 {} agent session(s) marked DISCONNECTED in DB", agentSessions.size());
        } catch (Exception e) {
            log.error("⚠️  Failed to mark agent sessions DISCONNECTED: {}", e.getMessage());
        }

        // 2. WS Client-side: audit + mark all MCP server sessions DISCONNECTED + close clients
        sessionManager.shutdown();
    }
}
