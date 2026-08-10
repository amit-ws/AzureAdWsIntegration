package com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
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

@Slf4j
public class HttpMcpTransport implements McpClientTransport {

    private static final ThreadLocal<Map<String, String>> requestOverrideHeaders = new ThreadLocal<>();

    private final String baseUrl;
    private final Map<String, String> headers;
    private final ObjectMapper mapper;
    private final AtomicBoolean connected;
    private final int timeout;

    private Thread sseThread;
    private volatile HttpURLConnection sseConnection;
    private volatile boolean closed = false;
    // Streamable-HTTP session id: session-strict servers (e.g. Alpha Vantage) hand this back on
    // initialize and require it echoed on every follow-up. Session-lax servers (e.g. github) omit it.
    private volatile String mcpSessionId;
    private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> messageHandler;

    public HttpMcpTransport(String baseUrl, Map<String, String> headers, int timeout) {
        // Only normalize a trailing slash for path-style base URLs. A URL carrying a query string
        // (e.g. ...?apikey=KEY) must be left intact — appending "/" would corrupt the query value.
        this.baseUrl = (baseUrl.endsWith("/") || baseUrl.contains("?")) ? baseUrl : baseUrl + "/";
        this.headers = headers;
        // Be lenient in what we accept: real-world MCP servers (e.g. Alpha Vantage) add non-spec
        // capability fields like tools.{list,call}. Ignore unknown JSON fields so an extra property
        // never aborts the whole initialize handshake.
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.connected = new AtomicBoolean(false);
        this.timeout = timeout;

 log.debug("HTTP MCP Transport created for: {}", baseUrl);
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        this.messageHandler = handler;
 log.debug("HTTP transport ready");
        return Mono.empty();
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> {
            try {
                String json = mapper.writeValueAsString(message);
 log.debug("Sending: {}", json);

                URL url = new URL(baseUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setRequestProperty("Accept", "application/json, text/event-stream");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Cache-Control", "no-cache");
                // Carry the negotiated streamable-HTTP session on every follow-up request.
                if (mcpSessionId != null) {
                    conn.setRequestProperty("mcp-session-id", mcpSessionId);
                }

                Map<String, String> overrides = requestOverrideHeaders.get();
                if (overrides != null && !overrides.isEmpty()) {
                    overrides.forEach(conn::setRequestProperty);
 log.debug("Using agent-provided token override ({} headers)", overrides.size());
                } else if (headers != null) {
                    headers.forEach(conn::setRequestProperty);
                }

                conn.setConnectTimeout(timeout * 1000);
                conn.setReadTimeout(0);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    // A status code came back → the server is reachable and replied → tool/application error,
                    // NOT a transport failure. Read the error body best-effort; even if the body is unreadable,
                    // still throw DownstreamHttpException so the connection is never dropped on a status alone.
                    String error = "HTTP " + code;
                    try (var es = conn.getErrorStream()) {
                        if (es != null) {
                            error += ": " + new String(es.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    } catch (Exception bodyReadFailure) {
                        error += " (error body unreadable: " + bodyReadFailure.getMessage() + ")";
                    }
                    throw new DownstreamHttpException(error);
                }

                // Capture (or refresh) the server-assigned session id from the initialize response
                // so subsequent requests stay in the same session. Header name is case-insensitive.
                String sid = conn.getHeaderField("mcp-session-id");
                if (sid != null && !sid.isBlank()) {
                    mcpSessionId = sid;
                }

                String ct = conn.getHeaderField("Content-Type");
                if (ct != null && ct.contains("text/event-stream")) {
 log.debug("Got SSE stream");
                    connected.set(true);
                    sseConnection = conn;
                    startSseReader(conn);
                } else {
                    try (var is = conn.getInputStream()) {
                        String resp = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        if (!resp.trim().isEmpty()) {
 log.debug("JSON response: {}", resp);
                            processMessage(resp);
                        }
                    }
                    connected.set(true);
                    conn.disconnect();
                }

            } catch (Exception e) {
                // Only a genuine transport / I-O failure means we are disconnected. A downstream HTTP error
                // RESPONSE (the server replied with a tool error or a rate-limit 500) is NOT a disconnect —
                // keep the connection alive. Otherwise one bad tool call (e.g. a model's empty-arg call, or
                // an Alpha Vantage rate-limit) would flip `connected=false` and knock the whole server
                // offline for the broker path (isConnected → false → every later call rejected "not connected").
                if (!(e instanceof DownstreamHttpException)) {
                    connected.set(false);
                }
                log.error("Send failed: {}", e.getMessage());
                throw new RuntimeException(e);
            } finally {
                requestOverrideHeaders.remove();
            }
        });
    }

    private void startSseReader(HttpURLConnection conn) {
        sseThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

 log.debug("SSE reader started");
                String line;
                StringBuilder data = new StringBuilder();

                while (!closed && (line = r.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        data.append(line.substring(6));
                    } else if (line.isEmpty() && data.length() > 0) {
                        String msg = data.toString().trim();
                        data.setLength(0);
                        if (!msg.isEmpty()) {
 log.debug("SSE: {}", msg);
                            processMessage(msg);
                        }
                    }
                }

 log.debug("SSE ended (closed={})", closed);
                if (closed) {
                    connected.set(false);
                }

            } catch (Exception e) {
                if (!closed) {
 log.error("SSE error: {}", e.getMessage());
                    connected.set(false);
                }
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
 log.error("Process failed: {}", e.getMessage());
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

    /**
     * A downstream HTTP error RESPONSE (4xx/5xx): the server was reachable and replied — with a tool error
     * or a rate-limit — so it is a request-level failure, NOT a transport disconnect. Distinguishing it keeps
     * one bad tool call from marking the whole server "disconnected" for the broker path.
     */
    private static final class DownstreamHttpException extends RuntimeException {
        DownstreamHttpException(String message) { super(message); }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public static void setRequestOverrideHeaders(Map<String, String> overrideHeaders) {
        requestOverrideHeaders.set(overrideHeaders);
    }

    public static void clearRequestOverrideHeaders() {
        requestOverrideHeaders.remove();
    }

    public Map<String, String> getConfigHeaders() {
        return headers;
    }
}