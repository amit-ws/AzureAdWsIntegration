package com.ws.wsAgenticSecurityGateway.wsClient.health;

import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSession;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSessionManager;
import com.ws.wsAgenticSecurityGateway.wsClient.repository.GatewayMcpServerSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodic health checker for WS Client-side (Gateway → Enterprise MCP Server) connections.
 *
 * <p>Runs on a configurable schedule (default: every 120 seconds). For each active
 * in-memory session, it performs a passive health probe by checking
 * {@code session.isActive()} (which calls {@code client.isInitialized()}).
 *
 * <p>Tracks consecutive failures per server. After reaching the configured threshold
 * (default: 3), the server is marked as unhealthy in the DB and an audit event is logged.
 *
 * <p>Can be disabled via {@code ws.gateway.southbound.health-check-enabled=false}.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "ws.gateway.southbound.health-check-enabled",
        havingValue = "true", matchIfMissing = true)
public class WsClientHealthCheckService {

    private final McpSessionManager sessionManager;
    private final GatewayMcpServerSessionRepository serverSessionRepository;
    private final McpAuditService auditService;
    private final WsClientHealthProperties properties;

    /** In-memory consecutive failure counter per server name. */
    private final Map<String, Integer> failureCounts = new ConcurrentHashMap<>();

    public WsClientHealthCheckService(McpSessionManager sessionManager,
                                      GatewayMcpServerSessionRepository serverSessionRepository,
                                      McpAuditService auditService,
                                      WsClientHealthProperties properties) {
        this.sessionManager = sessionManager;
        this.serverSessionRepository = serverSessionRepository;
        this.auditService = auditService;
        this.properties = properties;
    }

    /**
     * Scheduled health check — runs every {@code ws.gateway.southbound.health-check-interval-seconds} seconds.
     */
    @Scheduled(fixedDelayString = "${ws.gateway.southbound.health-check-interval-seconds:120}000")
    @Transactional
    public void checkHealth() {
        Map<String, McpSession> sessions = sessionManager.getAllSessions();
        if (sessions.isEmpty()) {
            return;
        }

        log.debug("WS Client-side health check: checking {} server(s)", sessions.size());

        for (Map.Entry<String, McpSession> entry : sessions.entrySet()) {
            String serverName = entry.getKey();
            McpSession session = entry.getValue();

            try {
                checkServerHealth(serverName, session);
            } catch (Exception e) {
                log.error("Error during health check for server '{}': {}",
                        serverName, e.getMessage());
            }
        }
    }

    private void checkServerHealth(String serverName, McpSession session) {
        boolean healthy = false;

        try {
            healthy = session.isActive();
        } catch (Exception e) {
            log.warn("Health probe threw exception for server '{}': {}", serverName, e.getMessage());
        }

        if (healthy) {
            // Reset failure counter on success
            Integer prev = failureCounts.remove(serverName);
            if (prev != null && prev > 0) {
                log.info("Server '{}' recovered after {} consecutive failure(s)", serverName, prev);
            }

            // Update DB health status
            try {
                serverSessionRepository.updateHealthCheck(serverName, "HEALTHY", 0);
            } catch (Exception e) {
                log.debug("Failed to update health check status for '{}': {}", serverName, e.getMessage());
            }
        } else {
            // Increment failure counter
            int failures = failureCounts.merge(serverName, 1, Integer::sum);
            log.warn("Server '{}' health check failed (consecutive: {})", serverName, failures);

            // Update DB health status
            try {
                serverSessionRepository.updateHealthCheck(serverName, "UNHEALTHY", failures);
            } catch (Exception e) {
                log.debug("Failed to update health check status for '{}': {}", serverName, e.getMessage());
            }

            // Audit the failure
            auditService.auditClientHealthCheckFailed(
                    session.getSessionId(), serverName, failures,
                    "Passive health probe returned unhealthy");

            // After max failures, log critical warning
            if (failures >= properties.getMaxReconnectAttempts()) {
                log.error("Server '{}' has failed {} consecutive health checks — marked UNHEALTHY",
                        serverName, failures);
            }
        }
    }
}
