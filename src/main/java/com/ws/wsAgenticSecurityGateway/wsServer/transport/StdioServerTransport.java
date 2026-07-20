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

    private final ConcurrentHashMap<Object, RequestContext> inflightRequests = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<McpSchema.JSONRPCMessage, Object> messageToRequestId = new ConcurrentHashMap<>();

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

                            captureAllRequestData(rawData);

                            McpSchema.JSONRPCMessage message = parseMessage(rawData);

                            Object jsonRpcId = rawData.get("id");
                            if (jsonRpcId != null) {
                                messageToRequestId.put(message, jsonRpcId);
                            }

                            handler.apply(Mono.just(message))
                                    .doOnSuccess(v -> log.debug("Message processed"))
                                    .doOnError(e -> log.error("Handler error", e))
                                    .onErrorResume(e -> Mono.empty())
                                    .subscribe();

                        } catch (Exception e) {
                            log.error("Failed to process: {}", jsonLine, e);
                        }
                    }

                    if (!closed) {
                        closed = true;
 log.info("Stdio pipe closed — agent disconnected");
                        try {
                            ClientSession session = sessionManager.getCurrentSession();
                            String sessionId = session.getSessionId();
                            String agentName = session.getClientInfo() != null
                                    ? session.getClientInfo().name() : null;

                            auditService.auditServerSessionDisconnectedSync(sessionId, agentName);
                            sessionManager.removeSession(sessionId);
 log.info("Session {} cleaned up after pipe close", sessionId);
                        } catch (Exception cleanupEx) {
                            log.error("Failed to cleanup session after pipe close: {}",
                                    cleanupEx.getMessage());
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
                try {
                    ClientSession session = sessionManager.getCurrentSession();
                    String sessionId = session.getSessionId();
                    String agentName = session.getClientInfo() != null ? session.getClientInfo().name() : null;

                    auditService.auditServerSessionDisconnectedSync(sessionId, agentName);

                    sessionManager.removeSession(sessionId);
                } catch (Exception e) {
                    log.error("Failed to clean up session on disconnect: {}", e.getMessage());
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

    public Object removeRequestId(McpSchema.JSONRPCMessage message) {
        return messageToRequestId.remove(message);
    }

    private void captureAllRequestData(Map<String, Object> rawData) {
        try {
            String method = (String) rawData.get("method");
            Object id = rawData.get("id");
            Object params = rawData.get("params");

            log.info("====================================");
            log.info("INCOMING REQUEST");
            log.info("====================================");
            log.info("Method: {}", method);
            log.info("ID: {}", id);
            log.info("Params: {}", params);
            log.info("RAW DATA: {}", rawData);
            log.info("====================================");

            extractTokens(rawData);
            if (params instanceof Map) {
                extractTokens((Map<String, Object>) params);
            }

            if ("initialize".equals(method)) {
                captureClientData(rawData, id);
            }

            if (id != null && method != null) {
                inflightRequests.put(id, new RequestContext(method, params, System.currentTimeMillis()));
            } else if (id == null && method != null) {
                auditNotification(method, params);
            }

        } catch (Exception e) {
            log.error("Error capturing request data", e);
        }
    }

    private void captureClientData(Map<String, Object> rawData, Object requestId) {
        try {
 log.info("CAPTURING CLIENT DATA...");

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

    private void extractTokens(Map<String, Object> data) {
        String[] tokenKeys = {
                "token", "apiKey", "api_key", "accessToken", "access_token",
                "bearerToken", "bearer_token", "authToken", "auth_token",
                "authorization", "credentials", "secret", "key", "jwt"
        };

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
                return;
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
                default -> log.debug("Response for method '{}' — no specific audit handler", ctx.method());
            }
        } catch (Exception e) {
            log.error("Error auditing response: {}", e.getMessage());
        }
    }

    private void auditNotification(String method, Object params) {
        try {
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
        return -1;
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