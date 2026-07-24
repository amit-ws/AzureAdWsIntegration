package com.ws.wsAgenticSecurityGateway.protocol.mcp.session;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@ConditionalOnProperty(name = "ws.gateway.session.reaper-enabled",
        havingValue = "true", matchIfMissing = true)
public class SessionReaperService {

    private final GatewayAgentSessionRepository sessionRepository;
    private final AgentRegistryService agentRegistryService;
    private final GatewayAuditService auditService;
    private final SessionLifecycleProperties properties;

    private volatile SessionManager sessionManager;

    public SessionReaperService(GatewayAgentSessionRepository sessionRepository,
                                AgentRegistryService agentRegistryService,
                                GatewayAuditService auditService,
                                SessionLifecycleProperties properties) {
        this.sessionRepository = sessionRepository;
        this.agentRegistryService = agentRegistryService;
        this.auditService = auditService;
        this.properties = properties;
    }

    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

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

            LocalDateTime lastActivity = staleSession.getLastRequestAt() != null
                    ? staleSession.getLastRequestAt()
                    : staleSession.getConnectedAt();
            long idleDurationMs = Duration.between(lastActivity, LocalDateTime.now()).toMillis();

            sessionRepository.markDisconnected(sessionId);

            try {
                agentRegistryService.disconnectSession(sessionId);
            } catch (Exception e) {
                log.debug("Agent registry disconnect for reaped session: {}", e.getMessage());
            }

            if (sessionManager != null) {
                sessionManager.removeSessionFromMemory(sessionId);
            }

            auditService.auditServerSessionIdleReaped(sessionId, agentName, idleDurationMs);

            log.info("Reaped idle session: {} (agent={}, idle for {}s)",
                    sessionId, agentName, idleDurationMs / 1000);

        } catch (Exception e) {
            log.error("Failed to reap session {}: {}",
                    staleSession.getSessionId(), e.getMessage());
        }
    }
}
