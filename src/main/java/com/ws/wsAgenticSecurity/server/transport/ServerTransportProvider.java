package com.ws.wsAgenticSecurity.server.transport;

import com.ws.wsAgenticSecurity.audit.service.McpAuditService;
import com.ws.wsAgenticSecurity.server.session.SessionManager;
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
        log.info("✓ Session factory set - creating session and starting connection");

        try {
            // Create the session
            this.session = sessionFactory.create(stdioTransport);

            log.info("Session created.....");

            // Start the transport with proper handler that uses session.handle()
            //
            // IMPORTANT: We inject the agent's JSON-RPC request id into Reactor Context
            // via .contextWrite(). This is the same pattern used by the SDK's HTTP transports
            // (HttpServletSseServerTransportProvider). The SDK reads this in
            // McpServerSession.handle() via Mono.deferContextual() and passes it to
            // handlers through McpSyncServerExchange.transportContext().
            //
            // This replaces the broken ThreadLocal approach (JsonRpcRequestContext)
            // which failed because the SDK dispatches handlers on a different thread.
            stdioTransport.connect(messageMonoIn ->
                    messageMonoIn.flatMap(message -> {
                        log.debug("Processing message through session");

                        // Look up the JSON-RPC id that the transport extracted from the raw message
                        Object jsonRpcId = stdioTransport.removeRequestId(message);

                        // Build transport context with the request id (same as SDK HTTP transports)
                        McpTransportContext transportContext = (jsonRpcId != null)
                                ? McpTransportContext.create(Map.of("jsonRpcRequestId", jsonRpcId))
                                : McpTransportContext.EMPTY;

                        // session.handle() processes the message and sends response via transport.
                        // .contextWrite() injects our transport context into the Reactor chain
                        // so the SDK can propagate it to the handler exchange (thread-safe).
                        return session.handle(message)
                                .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
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