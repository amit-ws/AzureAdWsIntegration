package com.ws.wsAgenticSecurity.server.transport;

import com.ws.wsAgenticSecurity.audit.service.McpAuditService;
import com.ws.wsAgenticSecurity.server.session.SessionManager;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
public class ServerTransportProvider implements McpServerTransportProvider {

    private final SessionManager sessionManager;
    private final StdioServerTransport stdioTransport;
    private McpServerSession session;

    public ServerTransportProvider(SessionManager sessionManager, McpAuditService auditService) {
        this.sessionManager = sessionManager;
        this.stdioTransport = new StdioServerTransport(sessionManager, auditService);
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        log.info("✓ Session factory set - creating session and starting connection");

        try {
            // Create the session
            this.session = sessionFactory.create(stdioTransport);

            log.info("Session created.....");

            // Start the transport with proper handler that uses session.handle()
            stdioTransport.connect(messageMonoIn ->
                    messageMonoIn.flatMap(message -> {
                        log.debug("Processing message through session");
                        // session.handle() processes the message and sends response via transport
                        // We need to return an empty message since responses are sent inside handle()
                        return session.handle(message)
                                .then(Mono.empty()); // Return empty Mono<JSONRPCMessage>
                    })
            ).subscribe(
                    v -> log.info("Transport connected and listening....."),
                    e -> log.error("Transport connection error...", e)
            );

        } catch (Exception e) {
            log.error("Failed to setup session", e);
            throw new RuntimeException("Failed to setup session", e);
        }
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (session != null) {
            return session.sendNotification(method, params);
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return stdioTransport.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return List.of("2024-11-05", "2025-03-26");
    }
}