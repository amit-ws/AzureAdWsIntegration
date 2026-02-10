package com.ws.wsAgenticSecurity.client.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * HTTP-based MCP transport - sends initialize message as first POST,
 * which returns SSE stream for all subsequent server messages.
 *
 * <p>Supports per-request header overrides via {@link #setRequestOverrideHeaders(Map)}.
 * When the orchestrator detects an agent-provided token, it sets override headers
 * on the current thread before calling {@code McpSyncClient.callTool()}.
 * The override is applied in {@link #sendMessage(McpSchema.JSONRPCMessage)} and
 * must be cleaned up by the caller via {@link #clearRequestOverrideHeaders()}.
 */
@Slf4j
public class HttpMcpTransport implements McpClientTransport {

    /**
     * ThreadLocal for per-request header overrides. When set, these headers
     * replace the config-based headers for the current HTTP request only.
     * The calling code (ToolCallOrchestrator) MUST clear this in a finally block.
     *
     * <p>ThreadLocal is safe here because handleToolCall() is synchronous —
     * the same thread flows from orchestrator → callTool() → sendMessage().
     */
    private static final ThreadLocal<Map<String, String>> requestOverrideHeaders = new ThreadLocal<>();

    private final String baseUrl;
    private final Map<String, String> headers;
    private final ObjectMapper mapper;
    private final AtomicBoolean connected;
    private final int timeout;

    private Thread sseThread;
    private volatile HttpURLConnection sseConnection;
    private volatile boolean closed = false;
    private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> messageHandler;

    public HttpMcpTransport(String baseUrl, Map<String, String> headers, int timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.headers = headers;
        this.mapper = new ObjectMapper();
        this.connected = new AtomicBoolean(false);
        this.timeout = timeout;

        log.info("🌐 HTTP MCP Transport created for: {}", baseUrl);
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        this.messageHandler = handler;
        log.info("🔌 HTTP transport ready");
        return Mono.empty();
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> {
            try {
                String json = mapper.writeValueAsString(message);
                log.info("📤 Sending: {}", json);

                URL url = new URL(baseUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setRequestProperty("Accept", "application/json, text/event-stream");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Cache-Control", "no-cache");

                // ── Apply headers: agent override (if set) takes priority over config ──
                Map<String, String> overrides = requestOverrideHeaders.get();
                if (overrides != null && !overrides.isEmpty()) {
                    overrides.forEach(conn::setRequestProperty);
                    log.debug("🔑 Using agent-provided token override ({} headers)", overrides.size());
                } else if (headers != null) {
                    headers.forEach(conn::setRequestProperty);
                }

                conn.setConnectTimeout(timeout * 1000);
                conn.setReadTimeout(0); // Infinite for SSE

                // Send message
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    String error = "HTTP " + code;
                    try (var es = conn.getErrorStream()) {
                        if (es != null) {
                            error += ": " + new String(es.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }
                    throw new RuntimeException(error);
                }

                // Check if SSE response
                String ct = conn.getHeaderField("Content-Type");
                if (ct != null && ct.contains("text/event-stream")) {
                    log.info("✅ Got SSE stream");
                    if (!connected.getAndSet(true)) {
                        sseConnection = conn;
                        startSseReader(conn);
                    }
                } else {
                    // Regular JSON
                    try (var is = conn.getInputStream()) {
                        String resp = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        if (!resp.trim().isEmpty()) {
                            log.info("📥 JSON response: {}", resp);
                            processMessage(resp);
                        }
                    }
                    conn.disconnect();
                }

            } catch (Exception e) {
                log.error("❌ Send failed: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    private void startSseReader(HttpURLConnection conn) {
        sseThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

                log.info("📡 SSE reader started");
                String line;
                StringBuilder data = new StringBuilder();

                while (!closed && (line = r.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        data.append(line.substring(6));
                    } else if (line.isEmpty() && data.length() > 0) {
                        String msg = data.toString().trim();
                        data.setLength(0);
                        if (!msg.isEmpty()) {
                            log.info("📥 SSE: {}", msg);
                            processMessage(msg);
                        }
                    }
                }

                log.info("📡 SSE ended");
                connected.set(false);

            } catch (Exception e) {
                if (!closed) log.error("❌ SSE error: {}", e.getMessage());
                connected.set(false);
            }
        });

        sseThread.setName("SSE-Reader");
        sseThread.setDaemon(true);
        sseThread.start();
    }

    private void processMessage(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = mapper.readValue(json, Map.class);
            McpSchema.JSONRPCMessage msg = parseMessage(raw);

            if (messageHandler != null) {
                messageHandler.apply(Mono.just(msg))
                        .onErrorResume(e -> Mono.empty())
                        .subscribe();
            }
        } catch (Exception e) {
            log.error("❌ Process failed: {}", e.getMessage());
        }
    }

    private McpSchema.JSONRPCMessage parseMessage(Map<String, Object> raw) {
        boolean hasMethod = raw.containsKey("method");
        boolean hasId = raw.containsKey("id");
        boolean hasResult = raw.containsKey("result");
        boolean hasError = raw.containsKey("error");

        if (hasResult || hasError) {
            return unmarshalFrom(raw, new TypeReference<McpSchema.JSONRPCResponse>() {});
        } else if (hasMethod && hasId) {
            return unmarshalFrom(raw, new TypeReference<McpSchema.JSONRPCRequest>() {});
        } else if (hasMethod) {
            return unmarshalFrom(raw, new TypeReference<McpSchema.JSONRPCNotification>() {});
        }
        throw new IllegalArgumentException("Unknown message: " + raw);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            closed = true;
            connected.set(false);
            if (sseConnection != null) sseConnection.disconnect();
            if (sseThread != null) sseThread.interrupt();
        });
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
        return mapper.convertValue(data, typeRef);
    }

    public boolean isConnected() {
        return connected.get();
    }

    // ════════════════════════════════════════════════════════════════════
    //  PER-REQUEST HEADER OVERRIDE (Agent Token Support)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Set override headers for the current thread's next HTTP request.
     * When set, these headers REPLACE the config-based headers entirely.
     *
     * <p>The caller MUST call {@link #clearRequestOverrideHeaders()} in a
     * finally block after the tool call completes.
     *
     * @param overrideHeaders the headers to use instead of config headers
     */
    public static void setRequestOverrideHeaders(Map<String, String> overrideHeaders) {
        requestOverrideHeaders.set(overrideHeaders);
    }

    /**
     * Clear the per-request header override for the current thread.
     * Must be called in a finally block after every tool call that set overrides.
     */
    public static void clearRequestOverrideHeaders() {
        requestOverrideHeaders.remove();
    }

    /**
     * Get the config-based headers for this transport.
     * Used by the orchestrator to merge non-auth config headers with agent tokens.
     *
     * @return the config-based headers (may be null)
     */
    public Map<String, String> getConfigHeaders() {
        return headers;
    }
}