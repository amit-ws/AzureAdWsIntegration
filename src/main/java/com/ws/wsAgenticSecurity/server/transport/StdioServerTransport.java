package com.ws.wsAgenticSecurity.server.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurity.server.session.ClientSession;
import com.ws.wsAgenticSecurity.server.session.SessionManager;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Slf4j
public class StdioServerTransport implements McpServerTransport {

    private final InputStream in;
    private final OutputStream out;
    private final ObjectMapper mapper;
    private final SessionManager sessionManager;
    private volatile boolean closed = false;

    public StdioServerTransport(SessionManager sessionManager) {
        this.in = System.in;
        this.out = System.out;
        this.mapper = new ObjectMapper();
        this.sessionManager = sessionManager;
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
//                log.info(">>> SENDING: {}", json);

                synchronized (out) {
                    out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
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

            // Extract tokens from ANY request
            extractTokens(rawData);
            if (params instanceof Map) {
                extractTokens((Map<String, Object>) params);
            }

            // Special handling for initialize
            if ("initialize".equals(method)) {
                captureClientData(rawData);
            }

        } catch (Exception e) {
            log.error("Error capturing request data", e);
        }
    }

    private void captureClientData(Map<String, Object> rawData) {
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
                session.initialize(protocolVersion, capabilities, clientInfo, params);
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

        for (String key : tokenKeys) {
            if (data.containsKey(key)) {
                Object value = data.get(key);
                if (value != null) {
                    log.info("TOKEN: {} = {}...", key, maskToken(value.toString()));
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