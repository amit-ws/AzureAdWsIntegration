package com.ws.wsAgenticSecurityGateway.wsServer.session;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Periodically scans for idle agent sessions and marks them as DISCONNECTED.
 *
 * <p>This is the enterprise-grade solution for detecting agent disconnections
 * that cannot be detected via transport-level events (e.g., HTTP agents that
 * simply stop sending requests, network drops, ungraceful process kills).
 *
 * <p>Controlled by {@code ws.gateway.session.*} properties in application.yml.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "ws.gateway.session.reaper-enabled",
        havingValue = "true", matchIfMissing = true)
public class SessionReaperService {

    private final GatewayAgentSessionRepository sessionRepository;
    private final AgentRegistryService agentRegistryService;
    private final McpAuditService auditService;
    private final SessionLifecycleProperties properties;

    /** SessionManager is a POJO (not a Spring bean) — injected via setter after creation. */
    private volatile SessionManager sessionManager;

    public SessionReaperService(GatewayAgentSessionRepository sessionRepository,
                                AgentRegistryService agentRegistryService,
                                McpAuditService auditService,
                                SessionLifecycleProperties properties) {
        this.sessionRepository = sessionRepository;
        this.agentRegistryService = agentRegistryService;
        this.auditService = auditService;
        this.properties = properties;
    }

    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Periodic scan for stale CONNECTED sessions.
     *
     * <p>A session is considered stale if its last activity
     * ({@code COALESCE(lastRequestAt, connectedAt)}) is older than
     * the configured idle timeout.
     */
    @Scheduled(fixedDelayString = "${ws.gateway.session.reaper-interval-seconds:60}000")
    @Transactional
    public void reapIdleSessions() {
        try {
            LocalDateTime cutoff = LocalDateTime.now()
                    .minusMinutes(properties.getIdleTimeoutMinutes());

            List<GatewayAgentSessionEntity> staleSessions =
                    sessionRepository.findStaleConnectedSessions(cutoff);

            if (staleSessions.isEmpty()) {
                return;
            }

            log.info("Session reaper found {} stale session(s) (idle > {}min)",
                    staleSessions.size(), properties.getIdleTimeoutMinutes());

            for (GatewayAgentSessionEntity staleSession : staleSessions) {
                reapSession(staleSession);
            }
        } catch (Exception e) {
            log.error("Session reaper encountered an error: {}", e.getMessage(), e);
        }
    }

    private void reapSession(GatewayAgentSessionEntity staleSession) {
        try {
            String sessionId = staleSession.getSessionId();
            String agentName = staleSession.getAgent() != null
                    ? staleSession.getAgent().getAgentName() : null;

            // Compute how long this session has been idle
            LocalDateTime lastActivity = staleSession.getLastRequestAt() != null
                    ? staleSession.getLastRequestAt()
                    : staleSession.getConnectedAt();
            long idleDurationMs = Duration.between(lastActivity, LocalDateTime.now()).toMillis();

            // 1. Mark DB as DISCONNECTED (WHERE status='CONNECTED' — idempotent)
            sessionRepository.markDisconnected(sessionId);

            // 2. Remove from AgentRegistryService in-memory maps
            try {
                agentRegistryService.disconnectSession(sessionId);
            } catch (Exception e) {
                log.debug("Agent registry disconnect for reaped session: {}", e.getMessage());
            }

            // 3. Remove from SessionManager if available
            if (sessionManager != null) {
                sessionManager.removeSessionFromMemory(sessionId);
            }

            // 4. Audit the idle timeout reap (distinct from graceful disconnect)
            auditService.auditServerSessionIdleReaped(sessionId, agentName, idleDurationMs);

            log.info("Reaped idle session: {} (agent={}, idle for {}s)",
                    sessionId, agentName, idleDurationMs / 1000);

        } catch (Exception e) {
            log.error("Failed to reap session {}: {}",
                    staleSession.getSessionId(), e.getMessage());
        }
    }
}
