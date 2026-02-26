package com.ws.wsAgenticSecurityGateway.wsServer.session;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SessionManager {

    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final McpAuditService auditService;
    private ClientSession currentSession;

    /** Agent registry service — injected via setter from McpServerApplication. */
    @Getter @Setter
    private AgentRegistryService agentRegistryService;

    public SessionManager(McpAuditService auditService) {
        this.auditService = auditService;
    }

    public ClientSession createSession() {
        ClientSession session = new ClientSession(auditService, agentRegistryService);
        sessions.put(session.getSessionId(), session);
        this.currentSession = session;
        log.info("✨ Created session: {}", session.getSessionId());
        return session;
    }

    public ClientSession getCurrentSession() {
        if (currentSession == null) {
            currentSession = createSession();
        }
        return currentSession;
    }
}