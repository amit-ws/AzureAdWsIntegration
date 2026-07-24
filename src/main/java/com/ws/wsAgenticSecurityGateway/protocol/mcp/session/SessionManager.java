package com.ws.wsAgenticSecurityGateway.protocol.mcp.session;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SessionManager {

    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final GatewayAuditService auditService;
    private ClientSession currentSession;

    @Getter @Setter
    private AgentRegistryService agentRegistryService;

    public SessionManager(GatewayAuditService auditService) {
        this.auditService = auditService;
    }

    public ClientSession createSession() {
        ClientSession session = new ClientSession(auditService, agentRegistryService);
        sessions.put(session.getSessionId(), session);
        this.currentSession = session;
 log.info("Created session: {}", session.getSessionId());
        return session;
    }

    public ClientSession getCurrentSession() {
        if (currentSession == null) {
            currentSession = createSession();
        }
        return currentSession;
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        if (currentSession != null && sessionId.equals(currentSession.getSessionId())) {
            currentSession = null;
        }
        if (agentRegistryService != null) {
            try {
                agentRegistryService.disconnectSession(sessionId);
            } catch (Exception e) {
                log.error("Failed to disconnect session in agent registry: {}", e.getMessage());
            }
        }
        log.info("Session removed (full cleanup): {}", sessionId);
    }

    public void removeSessionFromMemory(String sessionId) {
        sessions.remove(sessionId);
        if (currentSession != null && sessionId.equals(currentSession.getSessionId())) {
            currentSession = null;
        }
        log.info("Session removed from memory: {}", sessionId);
    }

    public Map<String, ClientSession> getAllSessions() {
        return Map.copyOf(sessions);
    }
}