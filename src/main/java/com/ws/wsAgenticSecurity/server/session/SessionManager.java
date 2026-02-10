package com.ws.wsAgenticSecurity.server.session;

import com.ws.wsAgenticSecurity.audit.service.McpAuditService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SessionManager {

    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final McpAuditService auditService;
    private ClientSession currentSession;

    public SessionManager(McpAuditService auditService) {
        this.auditService = auditService;
    }

    public ClientSession createSession() {
        ClientSession session = new ClientSession(auditService);
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