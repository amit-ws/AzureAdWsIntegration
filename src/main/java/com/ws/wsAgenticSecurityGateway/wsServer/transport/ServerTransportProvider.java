package com.ws.wsAgenticSecurityGateway.wsServer.transport;

import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.wsServer.session.SessionManager;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

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
 log.info("Session factory set - creating session and starting connection");

        try {
            this.session = sessionFactory.create(stdioTransport);

            log.info("Session created.....");

            stdioTransport.connect(messageMonoIn ->
                    messageMonoIn.flatMap(message -> {
                        log.debug("Processing message through session");

                        Object jsonRpcId = stdioTransport.removeRequestId(message);

                        McpTransportContext transportContext = (jsonRpcId != null)
                                ? McpTransportContext.create(Map.of("jsonRpcRequestId", jsonRpcId))
                                : McpTransportContext.EMPTY;

                        return session.handle(message)
                                .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
                                .then(Mono.empty());
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