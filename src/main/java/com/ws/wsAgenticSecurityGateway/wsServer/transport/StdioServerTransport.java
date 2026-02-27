package com.ws.wsAgenticSecurityGateway.wsServer.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.wsServer.session.ClientSession;
import com.ws.wsAgenticSecurityGateway.wsServer.session.SessionManager;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Slf4j
public class StdioServerTransport implements McpServerTransport {

    private final InputStream in;
    private final OutputStream out;
    private final ObjectMapper mapper;
    private final SessionManager sessionManager;
    private final McpAuditService auditService;
    private volatile boolean closed = false;

    /**
     * Tracks in-flight requests by JSON-RPC id so that when the response is sent,
     * we can calculate duration and dispatch the appropriate audit call.
     */
    private final ConcurrentHashMap<Object, RequestContext> inflightRequests = new ConcurrentHashMap<>();

    /**
     * Maps a parsed JSONRPCMessage → its JSON-RPC {@code id}, so that
     * {@link ServerTransportProvider} can look up the id and inject it into
     * Reactor Context via {@code .contextWrite()} before the SDK dispatches
     * the handler on a (possibly different) thread.
     *
     * <p>Entries are removed by {@link #removeRequestId(McpSchema.JSONRPCMessage)}
     * to prevent memory leaks.
     *
     * <p><strong>Why not ThreadLocal?</strong> The SDK's Reactor {@code flatMap()}
     * chain in {@code McpServerSession.handle()} can switch threads between transport
     * and handler — ThreadLocal values set on the reader thread are invisible to the
     * handler thread.
     */
    private final ConcurrentHashMap<McpSchema.JSONRPCMessage, Object> messageToRequestId = new ConcurrentHashMap<>();

    /**
     * Context stored for each incoming request, keyed by JSON-RPC id.
     */
    private record RequestContext(String method, Object params, long startTimeMs) {}

    public StdioServerTransport(SessionManager sessionManager, McpAuditService auditService) {
        this.in = System.in;
        this.out = System.out;
        this.mapper = new ObjectMapper();
        this.sessionManager = sessionManager;
        this.auditService = auditService;
    }

    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {

        return Mono.create(sink -> {
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {

                    log.info("Server reader thread started");
                    sink.success();

                    String line;
                    while (!closed && (line = reader.readLine()) != null) {
                        final String jsonLine = line.trim();

                        if (jsonLine.isEmpty() || !jsonLine.startsWith("{")) {
                            continue;
                        }

                        log.info("<<< RECEIVED: {}", jsonLine);

                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> rawData = mapper.readValue(jsonLine, Map.class);

                            // Capture client data on initialize
//                            if ("initialize".equals(rawData.get("method"))) {
//                                captureClientData(rawData);
//                            }
                            captureAllRequestData(rawData);  // ← Capture EVERYTHING

                            // Parse message
                            McpSchema.JSONRPCMessage message = parseMessage(rawData);

                            // Store JSON-RPC id → message mapping for Reactor Context injection.
                            // ServerTransportProvider will call removeRequestId(message) to look this up
                            // and inject it into Reactor Context via .contextWrite().
                            Object jsonRpcId = rawData.get("id");
                            if (jsonRpcId != null) {
                                messageToRequestId.put(message, jsonRpcId);
                            }

                            // Pass to handler
                            // The handler (session.handle) will process and send response internally
                            handler.apply(Mono.just(message))
                                    .doOnSuccess(v -> log.debug("Message processed"))
                                    .doOnError(e -> log.error("Handler error", e))
                                    .onErrorResume(e -> Mono.empty())
                                    .subscribe();

                        } catch (Exception e) {
                            log.error("Failed to process: {}", jsonLine, e);
                        }
                    }

                } catch (Exception e) {
                    if (!closed) {
                        log.error("Reader exception", e);
                        sink.error(e);
                    }
                }
            });

            readerThread.setDaemon(false);
            readerThread.setName("MCP-Server-Reader");
            readerThread.start();
        });
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> {
            try {
                String json = mapper.writeValueAsString(message);

                synchronized (out) {
                    out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }

                // ── Audit response interception ────────────────────────────
                auditResponseIfTracked(message, json);

            } catch (Exception e) {
                log.error("Failed to send", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            closed = true;
            try {
                // Audit session disconnect
                try {
                    ClientSession session = sessionManager.getCurrentSession();
                    String agentName = session.getClientInfo() != null ? session.getClientInfo().name() : null;
                    auditService.auditServerSessionDisconnected(session.getSessionId(), agentName);
                } catch (Exception e) {
                    log.error("Failed to audit session disconnect: {}", e.getMessage());
                }

                in.close();
                out.close();
            } catch (IOException e) {
                log.error("Error closing", e);
            }
        });
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
        return mapper.convertValue(data, typeRef);
    }

    @Override
    public List<String> protocolVersions() {
        return List.of("2024-11-05", "2025-03-26");
    }

    /**
     * Removes and returns the JSON-RPC id associated with the given parsed message.
     *
     * <p>Called by {@link ServerTransportProvider} in the Reactor chain, BEFORE
     * the SDK dispatches the handler. The id is then injected into Reactor Context
     * as a {@code McpTransportContext} so the orchestrator can read it from
     * {@code exchange.transportContext().get("jsonRpcRequestId")}.
     *
     * @param message the parsed JSONRPCMessage
     * @return the JSON-RPC id (String or Integer), or null if not a request
     */
    public Object removeRequestId(McpSchema.JSONRPCMessage message) {
        return messageToRequestId.remove(message);
    }

    private void captureAllRequestData(Map<String, Object> rawData) {
        try {
            String method = (String) rawData.get("method");
            Object id = rawData.get("id");
            Object params = rawData.get("params");

            // JSON-RPC id is now stored in messageToRequestId map (in connect()),
            // NOT ThreadLocal. ServerTransportProvider injects it into Reactor Context
            // via .contextWrite() so it's available in exchange.transportContext().

            log.info("====================================");
            log.info("INCOMING REQUEST");
            log.info("====================================");
            log.info("Method: {}", method);
            log.info("ID: {}", id);
            log.info("Params: {}", params);
            log.info("RAW DATA: {}", rawData);
            log.info("====================================");

            // Extract tokens from ANY request
            extractTokens(rawData);
            if (params instanceof Map) {
                extractTokens((Map<String, Object>) params);
            }

            // Special handling for initialize
            if ("initialize".equals(method)) {
                captureClientData(rawData, id);
            }

            // ── Track request for audit (request → response matching) ───
            if (id != null && method != null) {
                // Request with id — track it so we can audit when response is sent
                inflightRequests.put(id, new RequestContext(method, params, System.currentTimeMillis()));
            } else if (id == null && method != null) {
                // Notification (no id, no response expected) — audit immediately
                auditNotification(method, params);
            }

        } catch (Exception e) {
            log.error("Error capturing request data", e);
        }
    }

    private void captureClientData(Map<String, Object> rawData, Object requestId) {
        try {
            log.info("🔍 CAPTURING CLIENT DATA...");

            log.info(" ");
            log.info("rawData: {}", rawData);
            log.info(" ");

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) rawData.get("params");

            if (params != null) {
                log.info("====================================");
                log.info("CLIENT DATA:");
                params.forEach((key, value) -> log.info("  {} = {}", key, value));
                log.info("====================================");

                String protocolVersion = (String) params.get("protocolVersion");

                @SuppressWarnings("unchecked")
                Map<String, Object> clientInfoMap = (Map<String, Object>) params.get("clientInfo");
                McpSchema.Implementation clientInfo = null;
                if (clientInfoMap != null) {
                    clientInfo = new McpSchema.Implementation(
                            (String) clientInfoMap.get("name"),
                            (String) clientInfoMap.get("version")
                    );
                }

                Object capabilitiesObj = params.get("capabilities");
                McpSchema.ClientCapabilities capabilities = null;
                if (capabilitiesObj != null) {
                    capabilities = mapper.convertValue(
                            capabilitiesObj,
                            McpSchema.ClientCapabilities.class
                    );
                }

                extractTokens(params);
                extractTokens(rawData);

                ClientSession session = sessionManager.getCurrentSession();
                String requestIdStr = requestId != null ? String.valueOf(requestId) : null;
                session.initialize(protocolVersion, capabilities, clientInfo, params, requestIdStr);
            }

        } catch (Exception e) {
            log.error("Error capturing client data", e);
        }
    }

    /**
     * Extracts tokens from incoming request data and stores them in the ClientSession.
     *
     * <p>Scans for well-known token keys at top level and recursively in nested maps.
     * When a token is found, it is both logged (masked) and stored in
     * {@link ClientSession#storeToken(String, String)} for later use by the
     * orchestrator when forwarding tool calls to enterprise servers.
     */
    private void extractTokens(Map<String, Object> data) {
        String[] tokenKeys = {
                "token", "apiKey", "api_key", "accessToken", "access_token",
                "bearerToken", "bearer_token", "authToken", "auth_token",
                "authorization", "credentials", "secret", "key", "jwt"
        };

        // Get current session for token storage (best-effort)
        ClientSession session = null;
        try {
            session = sessionManager.getCurrentSession();
        } catch (Exception e) {
            log.debug("Could not get session for token storage: {}", e.getMessage());
        }

        for (String key : tokenKeys) {
            if (data.containsKey(key)) {
                Object value = data.get(key);
                if (value != null) {
                    String tokenValue = value.toString();
                    log.info("TOKEN: {} = {}...", key, maskToken(tokenValue));

                    // Store in ClientSession for orchestrator to use
                    if (session != null && !tokenValue.isBlank()) {
                        session.storeToken(key, tokenValue);
                    }
                }
            }
        }

        data.forEach((key, value) -> {
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) value;
                extractTokens(nested);
            }
        });
    }

    private String maskToken(String token) {
        if (token == null || token.length() <= 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    // ════════════════════════════════════════════════════════════════════
    //  AUDIT — Response interception & notification handling
    // ════════════════════════════════════════════════════════════════════

    /**
     * Matches an outgoing response to its original request by JSON-RPC id,
     * calculates the duration, and dispatches the appropriate audit method.
     */
    private void auditResponseIfTracked(McpSchema.JSONRPCMessage message, String json) {
        try {
            if (!(message instanceof McpSchema.JSONRPCResponse response)) {
                return;
            }

            Object responseId = response.id();
            if (responseId == null) {
                return;
            }

            RequestContext ctx = inflightRequests.remove(responseId);
            if (ctx == null) {
                return; // No matching request tracked (e.g., initialize is audited in ClientSession)
            }

            long durationMs = System.currentTimeMillis() - ctx.startTimeMs();
            ClientSession cs = sessionManager.getCurrentSession();
            String sessionId = cs.getSessionId();
            String requestId = String.valueOf(responseId);
            String agentName = cs.getClientInfo() != null ? cs.getClientInfo().name() : null;

            switch (ctx.method()) {
                case "tools/list" -> {
                    int toolCount = extractListCount(json, "tools");
                    auditService.auditServerToolsListRequested(sessionId, toolCount, durationMs, requestId, agentName);
                    log.debug("Audited tools/list — {} tools, {}ms (requestId={})", toolCount, durationMs, requestId);
                }
                case "resources/list" -> {
                    int resourceCount = extractListCount(json, "resources");
                    auditService.auditServerResourcesListRequested(sessionId, resourceCount, durationMs, requestId, agentName);
                    log.debug("Audited resources/list — {} resources, {}ms (requestId={})", resourceCount, durationMs, requestId);
                }
                case "prompts/list" -> {
                    int promptCount = extractListCount(json, "prompts");
                    auditService.auditServerPromptsListRequested(sessionId, promptCount, durationMs, requestId, agentName);
                    log.debug("Audited prompts/list — {} prompts, {}ms (requestId={})", promptCount, durationMs, requestId);
                }
                // tools/call is audited in McpServerApplication.handleToolCall() — no duplicate here
                // initialize is audited in ClientSession.initialize() — no duplicate here
                default -> log.debug("Response for method '{}' — no specific audit handler", ctx.method());
            }
        } catch (Exception e) {
            log.error("Error auditing response: {}", e.getMessage());
        }
    }

    /**
     * Audits an incoming notification (JSON-RPC message with method but no id).
     */
    private void auditNotification(String method, Object params) {
        try {
            // Skip initialize — it's not a notification, but it's handled separately anyway
            if ("initialize".equals(method)) {
                return;
            }

            ClientSession cs = sessionManager.getCurrentSession();
            String sessionId = cs.getSessionId();
            String agentName = cs.getClientInfo() != null ? cs.getClientInfo().name() : null;
            auditService.auditServerNotificationReceived(sessionId, method, params, agentName);
            log.debug("Audited notification: {}", method);
        } catch (Exception e) {
            log.error("Error auditing notification '{}': {}", method, e.getMessage());
        }
    }

    /**
     * Extracts the count of items in a list result from the JSON response.
     * For example, for tools/list, the response is: {"result": {"tools": [...]}}
     * This parses the JSON to count the array items.
     */
    @SuppressWarnings("unchecked")
    private int extractListCount(String json, String listKey) {
        try {
            Map<String, Object> responseMap = mapper.readValue(json, Map.class);
            Object result = responseMap.get("result");
            if (result instanceof Map) {
                Object list = ((Map<String, Object>) result).get(listKey);
                if (list instanceof List) {
                    return ((List<?>) list).size();
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract {} count from response: {}", listKey, e.getMessage());
        }
        return -1; // Unknown count
    }

    private McpSchema.JSONRPCMessage parseMessage(Map<String, Object> rawData) {
        boolean hasMethod = rawData.containsKey("method");
        boolean hasId = rawData.containsKey("id");
        boolean hasResult = rawData.containsKey("result");
        boolean hasError = rawData.containsKey("error");

        if (hasResult || hasError) {
            return unmarshalFrom(rawData, new TypeReference<McpSchema.JSONRPCResponse>() {});
        } else if (hasMethod && hasId) {
            return unmarshalFrom(rawData, new TypeReference<McpSchema.JSONRPCRequest>() {});
        } else if (hasMethod && !hasId) {
            return unmarshalFrom(rawData, new TypeReference<McpSchema.JSONRPCNotification>() {});
        } else {
            throw new IllegalArgumentException("Unknown message type");
        }
    }
}