package com.ws.wsAgenticSecurityGateway.wsServer.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService.AgentBlockedException;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full MCP audit filter for HTTP Streamable transport mode.
 *
 * <p>
 * The MCP SDK ({@code HttpServletStreamableServerTransportProvider}) handles
 * {@code initialize}, {@code tools/list}, {@code prompts/list},
 * {@code resources/list},
 * and session DELETE internally with <em>no lifecycle hooks or callbacks</em>.
 * This filter intercepts ALL MCP requests at the HTTP layer to provide full
 * audit parity with stdio mode.
 *
 * <p>
 * <strong>How it works:</strong>
 * <ol>
 * <li>Wraps POST requests in {@link ContentCachingRequestWrapper} to re-read
 * the body
 * after the SDK processes it</li>
 * <li>Lets the SDK handle the request normally via
 * {@code chain.doFilter()}</li>
 * <li>After SDK processing, parses the cached request body to determine the
 * JSON-RPC
 * method and dispatches to the appropriate audit handler</li>
 * <li>Intercepts DELETE requests for session disconnect audit</li>
 * </ol>
 *
 * <p>
 * <strong>Probe filtering:</strong> The {@code mcp-remote} npm bridge creates
 * test/internal sessions ({@code mcp-remote-fallback-test},
 * {@code local-agent-mode-*})
 * that are NOT real agents. These are filtered from agent registration.
 */
@Slf4j
public class HttpMcpAuditFilter implements Filter {

    private final AgentRegistryService agentRegistryService;
    private final McpAuditService auditService;
    private final CapabilityRegistryService registryService;
    private final ObjectMapper objectMapper;

    /**
     * Tracks which sessions have been registered (prevents duplicate registration).
     */
    private final ConcurrentHashMap<String, Boolean> registeredSessions = new ConcurrentHashMap<>();

    /**
     * Tracks ALL session IDs created by the SDK (including probes) — used for stale
     * session detection.
     */
    private final Set<String> knownSessionIds = ConcurrentHashMap.newKeySet();

    /** Maps sessionId → agentName for use in list/disconnect audit calls. */
    private final ConcurrentHashMap<String, String> sessionAgentNames = new ConcurrentHashMap<>();

    /** Sessions explicitly rejected due to blocked agent policy. */
    private final Set<String> blockedSessionIds = ConcurrentHashMap.newKeySet();

    /** Agent names from mcp-remote that are probes/tests, not real agents. */
    private static final Set<String> PROBE_NAMES = Set.of("mcp-remote-fallback-test");

    public HttpMcpAuditFilter(AgentRegistryService agentRegistryService,
            McpAuditService auditService,
            CapabilityRegistryService registryService,
            ObjectMapper objectMapper) {
        this.agentRegistryService = agentRegistryService;
        this.auditService = auditService;
        this.registryService = registryService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String httpMethod = httpRequest.getMethod();

        // ── Handle DELETE (session disconnect) ──────────────────────
        if ("DELETE".equalsIgnoreCase(httpMethod)) {
            handleDelete(httpRequest, httpResponse, chain);
            return;
        }

        // ── Session validation: reject stale/unknown sessions ──────
        // If Mcp-Session-Id header is present but not in our tracked sessions,
        // the session is stale (e.g., from before a gateway restart).
        // Return HTTP 200 + JSON-RPC error so the agent receives a proper
        // error response instead of hanging until timeout.
        String existingSessionId = httpRequest.getHeader("Mcp-Session-Id");
        if (existingSessionId != null && !knownSessionIds.contains(existingSessionId)) {
            log.warn("Rejecting request with stale session ID: {} — gateway was restarted, agent must reconnect",
                    existingSessionId);
            rejectStaleSession(httpRequest, httpResponse, existingSessionId);
            return;
        }

        // ── Only process POST requests (JSON-RPC messages) ─────────
        if (!"POST".equalsIgnoreCase(httpMethod)) {
            chain.doFilter(request, response);
            return;
        }

        // ── Wrap request with cached body so we can inspect before AND after SDK ───
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(httpRequest);

        JsonNode requestJson = parseRequestJson(wrappedRequest.getCachedBody());
        String requestIdRaw = extractRequestIdRaw(requestJson);
        String requestId = extractRequestId(requestJson);
        String mcpMethod = requestJson != null ? requestJson.path("method").asText("") : "";

        // ── Block requests from sessions flagged as blocked ──────────
        String sessionId = wrappedRequest.getHeader("Mcp-Session-Id");
        if (sessionId != null && blockedSessionIds.contains(sessionId)) {
            String blockedAgentName = sessionAgentNames.getOrDefault(sessionId, "unknown");
            auditService.auditAgentConnectionRejected(
                    sessionId,
                    requestId,
                    blockedAgentName,
                    null,
                    mcpMethod,
                    "HTTP",
                    "Blocked session attempted request; reconnect after admin approval.");
            rejectBlockedRequest(httpResponse, requestIdRaw);
            return;
        }

        // ── Pre-initialize approval gate (must run BEFORE SDK creates session) ───
        if ("initialize".equals(mcpMethod) && requestJson != null) {
            JsonNode clientInfoNode = requestJson.path("params").path("clientInfo");
            String agentName = clientInfoNode.path("name").asText("unknown");
            String agentVersion = clientInfoNode.path("version").asText(null);

            // Probes are allowed through (handled separately in registration logic).
            if (!isProbeAgent(agentName)
                    && agentRegistryService.isAgentBlocked(agentName, agentVersion)) {
                log.warn("🚫 Pre-initialize rejection for BLOCKED agent: {} v{}",
                        agentName, agentVersion);
                auditService.auditAgentConnectionRejected(
                        null,
                        requestId,
                        agentName,
                        agentVersion,
                        "initialize",
                        "HTTP",
                        "Agent rejected during initialize because profile is BLOCKED.");
                rejectBlockedInitialize(httpResponse, requestIdRaw);
                return;
            }
        }

        long startTime = System.currentTimeMillis();

        // ── Let the SDK handle the request normally ────────────────
        chain.doFilter(wrappedRequest, httpResponse);

        long durationMs = System.currentTimeMillis() - startTime;

        // ── After SDK processing — audit based on method ───────────
        afterSdkProcessing(wrappedRequest, httpResponse, durationMs);
    }

    // ════════════════════════════════════════════════════════════════════
    // POST-PROCESSING — parse cached request body and dispatch audit
    // ════════════════════════════════════════════════════════════════════

    private void afterSdkProcessing(CachedBodyHttpServletRequest wrappedRequest,
            HttpServletResponse httpResponse,
            long durationMs) {
        try {
            byte[] body = wrappedRequest.getCachedBody();
            if (body.length == 0)
                return;

            JsonNode json = objectMapper.readTree(body);
            String mcpMethod = json.path("method").asText("");
            String requestId = json.has("id") ? json.get("id").asText() : null;

            // Session ID: from response header (initialize creates it) or request header
            String sessionId = httpResponse.getHeader("Mcp-Session-Id");
            if (sessionId == null) {
                sessionId = wrappedRequest.getHeader("Mcp-Session-Id");
            }
            if (sessionId == null)
                return;

            switch (mcpMethod) {
                case "initialize" -> handleInitialize(json, sessionId, requestId);
                case "tools/list" -> handleToolsList(sessionId, requestId, durationMs);
                case "prompts/list" -> handlePromptsList(sessionId, requestId, durationMs);
                case "resources/list" -> handleResourcesList(sessionId, requestId, durationMs);
                case "notifications/initialized" -> {
                    // SDK internal handshake — no audit needed
                }
                default -> {
                    // Other notifications (no id field = notification per JSON-RPC)
                    if (requestId == null && !mcpMethod.isEmpty()) {
                        handleNotification(sessionId, mcpMethod, json.path("params"));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error in HTTP MCP audit post-processing: {}", e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // INITIALIZE — agent registration + audit
    // ════════════════════════════════════════════════════════════════════

    private void handleInitialize(JsonNode json, String sessionId, String requestId) {
        // Track ALL sessions (including probes) for stale session detection
        knownSessionIds.add(sessionId);

        if (registeredSessions.containsKey(sessionId))
            return;
        if (registeredSessions.putIfAbsent(sessionId, Boolean.TRUE) != null)
            return;

        String agentName = "unknown";
        String agentVersion = null;
        String protocolVersion = null;

        try {
            JsonNode params = json.path("params");
            JsonNode clientInfoNode = params.path("clientInfo");
            agentName = clientInfoNode.path("name").asText("unknown");
            agentVersion = clientInfoNode.path("version").asText(null);
            protocolVersion = params.path("protocolVersion").asText(null);
            JsonNode capabilities = params.path("capabilities");

            // ── Probe filtering ──────────────────────────────────────
            if (isProbeAgent(agentName)) {
                log.debug("Skipping probe/test agent registration: {} (session={})", agentName, sessionId);
                registeredSessions.remove(sessionId);
                return;
            }

            // Track sessionId → agentName for list/disconnect audit
            sessionAgentNames.put(sessionId, agentName);

            // Agent Registry — discover (upsert) and register session
            GatewayAgentEntity agent = agentRegistryService.discoverAgent(
                    agentName, agentVersion, protocolVersion,
                    capabilities.isMissingNode() || capabilities.isEmpty() ? null : capabilities);
            agentRegistryService.registerSession(agent.getId(), sessionId, "HTTP", null);

            // ── Layer 1: Active Session Replacement ──────────────────
            // Disconnect stale sessions for this agent (zombie cleanup on reconnect).
            // This handles: close/reopen, delete/reconfig, network interruption +
            // reconnect.
            int replaced = agentRegistryService.disconnectExistingSessionsForAgent(
                    agent.getId(), sessionId);
            if (replaced > 0) {
                log.info("♻️ Replaced {} stale session(s) for agent {} on reconnect",
                        replaced, agentName);
                final String currentAgentName = agentName;
                // Clean up in-memory tracking maps for replaced sessions
                registeredSessions.entrySet().removeIf(entry -> !entry.getKey().equals(sessionId) &&
                        currentAgentName.equals(sessionAgentNames.get(entry.getKey())));
                knownSessionIds.removeIf(id -> !id.equals(sessionId) &&
                        currentAgentName.equals(sessionAgentNames.get(id)));
                sessionAgentNames.entrySet().removeIf(entry -> !entry.getKey().equals(sessionId) &&
                        currentAgentName.equals(entry.getValue()));
            }

            // Audit — session initialization
            auditService.auditServerSessionInitialized(
                    sessionId, protocolVersion,
                    agentName + (agentVersion != null ? " v" + agentVersion : ""),
                    capabilities, requestId, agentName);

            log.info("════════════════════════════════════════════════");
            log.info("   HTTP AGENT SESSION REGISTERED");
            log.info("════════════════════════════════════════════════");
            log.info("   Session:  {}", sessionId);
            log.info("   Agent:    {} v{}", agentName, agentVersion);
            log.info("   Protocol: {}", protocolVersion);
            if (replaced > 0) {
                log.info("   Replaced: {} stale session(s)", replaced);
            }
            log.info("════════════════════════════════════════════════");

        } catch (AgentBlockedException blocked) {
            blockedSessionIds.add(sessionId);
            sessionAgentNames.remove(sessionId);
            registeredSessions.remove(sessionId);
            auditService.auditAgentConnectionRejected(
                    sessionId,
                    requestId,
                    agentName,
                    agentVersion,
                    "initialize",
                    "HTTP",
                    blocked.getMessage());
            log.warn("🚫 Session {} flagged as blocked after initialize: {}", sessionId, blocked.getMessage());
        } catch (Exception e) {
            log.error("Failed to register HTTP agent session {}: {}", sessionId, e.getMessage(), e);
            registeredSessions.remove(sessionId);
            sessionAgentNames.remove(sessionId);
        }
    }

    /**
     * Returns true if the agent name belongs to a probe/test connection
     * created by the mcp-remote npm bridge, not a real AI agent.
     */
    private boolean isProbeAgent(String agentName) {
        if (agentName == null)
            return false;
        if (PROBE_NAMES.contains(agentName))
            return true;
        if (agentName.startsWith("local-agent-mode"))
            return true;
        if (agentName.contains("fallback-test"))
            return true;
        return false;
    }

    // ════════════════════════════════════════════════════════════════════
    // LIST OPERATIONS — audit using CapabilityRegistryService for counts
    // ════════════════════════════════════════════════════════════════════

    private void handleToolsList(String sessionId, String requestId, long durationMs) {
        String agentName = sessionAgentNames.getOrDefault(sessionId, "unknown");
        int toolCount = registryService.getToolDescriptors().size();
        auditService.auditServerToolsListRequested(sessionId, toolCount, durationMs, requestId, agentName);
        log.debug("Audited HTTP tools/list: session={}, count={}, duration={}ms", sessionId, toolCount, durationMs);
    }

    private void handlePromptsList(String sessionId, String requestId, long durationMs) {
        String agentName = sessionAgentNames.getOrDefault(sessionId, "unknown");
        int promptCount = registryService.getPromptDescriptors().size();
        auditService.auditServerPromptsListRequested(sessionId, promptCount, durationMs, requestId, agentName);
        log.debug("Audited HTTP prompts/list: session={}, count={}, duration={}ms", sessionId, promptCount, durationMs);
    }

    private void handleResourcesList(String sessionId, String requestId, long durationMs) {
        String agentName = sessionAgentNames.getOrDefault(sessionId, "unknown");
        int resourceCount = registryService.getResourceDescriptors().size();
        auditService.auditServerResourcesListRequested(sessionId, resourceCount, durationMs, requestId, agentName);
        log.debug("Audited HTTP resources/list: session={}, count={}, duration={}ms", sessionId, resourceCount,
                durationMs);
    }

    // ════════════════════════════════════════════════════════════════════
    // NOTIFICATIONS
    // ════════════════════════════════════════════════════════════════════

    private void handleNotification(String sessionId, String method, JsonNode params) {
        String agentName = sessionAgentNames.getOrDefault(sessionId, "unknown");
        auditService.auditServerNotificationReceived(sessionId, method, params, agentName);
        log.debug("Audited HTTP notification: session={}, method={}", sessionId, method);
    }

    // ════════════════════════════════════════════════════════════════════
    // STALE SESSION — return JSON-RPC error on HTTP 200
    // ════════════════════════════════════════════════════════════════════

    /**
     * Rejects a request with a stale/unknown session ID by returning a proper
     * JSON-RPC error on HTTP 200. This ensures the MCP client receives the error
     * as a response to its pending request instead of hanging until timeout.
     */
    private void rejectStaleSession(HttpServletRequest request, HttpServletResponse response, String existingSessionId)
            throws IOException {
        // Parse request body to extract the full JSON for auditing and the JSON-RPC
        // "id" for a proper error response
        String errorMessage = "Session expired. Gateway was restarted. Please reconnect the AI agent.";
        String requestId = null;
        JsonNode requestJson = null;
        try {
            byte[] body = request.getInputStream().readAllBytes();
            if (body.length > 0) {
                requestJson = objectMapper.readTree(body);
                if (requestJson.has("id")) {
                    requestId = requestJson.get("id").toString(); // raw value (could be number or string)
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse request body for stale session rejection: {}", e.getMessage());
        }

        // Perform the audit log
        String agentName = agentRegistryService.getAgentNameBySessionId(existingSessionId);
        auditService.auditServerRequestRejected(existingSessionId, agentName, requestJson, errorMessage);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"" + errorMessage + "\"},\"id\":"
                        + (requestId != null ? requestId : "null") + "}");
    }

    private void rejectBlockedInitialize(HttpServletResponse response, String requestIdRaw)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-33007,\"message\":\"Agent is blocked by admin. Contact your gateway administrator.\"},\"id\":"
                        + requestIdRaw + "}");
    }

    private void rejectBlockedRequest(HttpServletResponse response, String requestIdRaw)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-33007,\"message\":\"Agent is blocked by admin. Reconnect after approval.\"},\"id\":"
                        + requestIdRaw + "}");
    }

    private JsonNode parseRequestJson(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.debug("Could not parse request JSON in HTTP audit filter: {}", e.getMessage());
            return null;
        }
    }

    private String extractRequestIdRaw(JsonNode requestJson) {
        if (requestJson != null && requestJson.has("id")) {
            return requestJson.get("id").toString();
        }
        return "null";
    }

    private String extractRequestId(JsonNode requestJson) {
        if (requestJson != null && requestJson.has("id")) {
            return requestJson.get("id").asText();
        }
        return null;
    }

    /**
     * HTTP request wrapper with re-readable cached body.
     */
    private static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = request.getInputStream().readAllBytes();
        }

        byte[] getCachedBody() {
            return cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return bais.read();
                }

                @Override
                public boolean isFinished() {
                    return bais.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // No async read support needed for this wrapper.
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // DELETE — session disconnect
    // ════════════════════════════════════════════════════════════════════

    private void handleDelete(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        String sessionId = request.getHeader("Mcp-Session-Id");

        // Let SDK process the DELETE first
        chain.doFilter(request, response);

        // After SDK processing, audit if this was a tracked session
        if (sessionId != null && registeredSessions.containsKey(sessionId)) {
            try {
                String agentName = sessionAgentNames.getOrDefault(sessionId, "unknown");

                // Audit disconnect (synchronous — ensure it persists)
                auditService.auditServerSessionDisconnectedSync(sessionId, agentName);

                // Update Agent Registry DB state
                agentRegistryService.disconnectSession(sessionId);

                // Clean up tracking maps
                registeredSessions.remove(sessionId);
                knownSessionIds.remove(sessionId);
                blockedSessionIds.remove(sessionId);
                sessionAgentNames.remove(sessionId);

                log.info("HTTP session disconnected: {} (agent={})", sessionId, agentName);
            } catch (Exception e) {
                log.error("Failed to audit HTTP session disconnect {}: {}", sessionId, e.getMessage());
            }
        }
    }
}
