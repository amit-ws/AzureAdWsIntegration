package com.ws.wsAgenticSecurity.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurity.audit.error.McpErrorCode;
import com.ws.wsAgenticSecurity.audit.service.McpAuditService;
import com.ws.wsAgenticSecurity.client.McpClientService;
import com.ws.wsAgenticSecurity.client.config.HttpMcpTransport;
import com.ws.wsAgenticSecurity.registry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurity.registry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurity.server.session.ClientSession;
import com.ws.wsAgenticSecurity.server.session.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Core orchestration service — the control plane of the MCP Gateway.
 *
 * <p>Responsible for deterministic, bidirectional routing of tool invocations:
 * <ol>
 *   <li>Receives tool call from WS MCP Server (AI agent request)</li>
 *   <li>Resolves public tool name → enterprise server + original tool name via Registry</li>
 *   <li>Forwards the call to the correct enterprise MCP server via WS MCP Client</li>
 *   <li>Returns the response to the AI agent</li>
 * </ol>
 *
 * <p>Every step is audited. Every error is caught and returned as a structured
 * {@link McpSchema.CallToolResult} with {@code isError=true}.
 *
 * <p><strong>Thread Safety:</strong> This service is a stateless singleton.
 * All dependencies use thread-safe data structures (ConcurrentHashMap).
 * The {@code sessionManager} reference is {@code volatile} and set once at startup.
 *
 * <p><strong>Design Decision:</strong> A single class handles the entire flow
 * because the handler is synchronous (blocks until the enterprise server responds).
 * Splitting into 5 subcomponents (Router, Resolver, Dispatcher, etc.) would create
 * unnecessary indirection for a single synchronous call chain.
 */
@Service
@Slf4j
public class ToolCallOrchestrator {

    private final CapabilityRegistryService registryService;
    private final McpClientService mcpClientService;
    private final McpAuditService auditService;
    private final InFlightRequestRegistry inFlightRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Set via setter from McpServerApplication.run() because SessionManager is a
     * plain POJO (not a Spring bean) created at runtime. Volatile ensures visibility
     * across threads — set once before any tool calls arrive.
     */
    private volatile SessionManager sessionManager;

    public ToolCallOrchestrator(CapabilityRegistryService registryService,
                                 McpClientService mcpClientService,
                                 McpAuditService auditService,
                                 InFlightRequestRegistry inFlightRegistry,
                                 ObjectMapper objectMapper) {
        this.registryService = registryService;
        this.mcpClientService = mcpClientService;
        this.auditService = auditService;
        this.inFlightRegistry = inFlightRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * Setter for SessionManager — called once from McpServerApplication.run()
     * after the SessionManager is created.
     */
    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    // ════════════════════════════════════════════════════════════════════
    //  PRIMARY ENTRY POINT
    // ════════════════════════════════════════════════════════════════════

    /**
     * Orchestrate a tool call from an AI agent to the correct enterprise MCP server.
     *
     * <p>This is the primary entry point, called from
     * {@code McpServerApplication.handleToolCall()}.
     *
     * <p><strong>10-step lifecycle (no step may be skipped):</strong>
     * <ol>
     *   <li>Generate correlation ID</li>
     *   <li>Extract session ID + client info from exchange</li>
     *   <li>Audit: tool extracted</li>
     *   <li>Registry lookup: publicName → serverConfigName + originalName</li>
     *   <li>Audit: registry lookup</li>
     *   <li>Register in-flight</li>
     *   <li>Convert arguments Map → JsonNode</li>
     *   <li>Resolve agent token override (agent token → override config, else use config)</li>
     *   <li>Forward call via McpClientService (with token override if applicable)</li>
     *   <li>Audit: call forwarded / error</li>
     *   <li>Deregister in-flight, clean up token override, return result</li>
     * </ol>
     *
     * @param exchange   the MCP SDK exchange — carries session ID, client info, and capabilities
     * @param publicName the namespaced tool name as sent by the AI agent (e.g., "github_create_issue")
     * @param arguments  the tool arguments from CallToolRequest.arguments()
     * @return CallToolResult to be returned directly to the MCP SDK
     */
    public McpSchema.CallToolResult orchestrate(McpSyncServerExchange exchange,
                                                 String publicName,
                                                 Map<String, Object> arguments) {

        // ── Step 1: Generate correlation ID ─────────────────────────────
        String correlationId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // ── Step 1b: Capture agent's JSON-RPC request id from Reactor Context ──
        // The transport injects the id into McpTransportContext via .contextWrite().
        // The SDK propagates it to exchange.transportContext() (thread-safe).
        Object rawJsonRpcId = exchange.transportContext().get("jsonRpcRequestId");
        String requestId = rawJsonRpcId != null ? String.valueOf(rawJsonRpcId) : null;

        // ── Step 2: Extract session ID + client info from exchange ──────
        String sessionId = resolveSessionId(exchange);
        String clientName = resolveClientName(exchange);

        log.info("═══════════════════════════════════════════════════════════");
        log.info("🎯 Tool ORCHESTRATION START [{}]", correlationId);
        log.info("   Tool: {}", publicName);
        log.info("   Args: {}", arguments != null ? arguments.keySet() : "null");
        log.info("   Session: {}", sessionId != null ? sessionId : "unknown");
        log.info("   Agent: {}", clientName);
        log.info("   JSON-RPC ID: {}", requestId != null ? requestId : "n/a");
        log.info("═══════════════════════════════════════════════════════════");

        long orchestrationStart = System.currentTimeMillis();

        // ── Step 3: Audit — tool extracted from AI agent request ────────
        auditService.auditOrchestrationToolExtracted(correlationId, publicName, sessionId, requestId);

        // ── Step 4: Registry lookup ─────────────────────────────────────
        long lookupStart = System.currentTimeMillis();
        Optional<CapabilityDescriptor> optDescriptor = registryService.lookupByPublicName(publicName);
        long lookupDuration = System.currentTimeMillis() - lookupStart;

        if (optDescriptor.isEmpty()) {
            log.error("❌ [{}] Tool '{}' NOT FOUND in capability registry", correlationId, publicName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    McpErrorCode.CAPABILITY_NOT_FOUND,
                    "Tool '" + publicName + "' not found in capability registry",
                    requestId);
            return buildErrorResult(McpErrorCode.CAPABILITY_NOT_FOUND, publicName);
        }

        CapabilityDescriptor descriptor = optDescriptor.get();
        String serverName = descriptor.getServerConfigName();
        String originalName = descriptor.getOriginalName();

        log.info("🔍 [{}] Registry resolved: {} → server='{}', original='{}'",
                correlationId, publicName, serverName, originalName);

        // ── Step 5: Audit — registry lookup success ─────────────────────
        auditService.auditOrchestrationRegistryLookup(
                correlationId, sessionId, publicName, serverName, lookupDuration, requestId);

        // ── Step 6: Register in-flight ──────────────────────────────────
        inFlightRegistry.register(correlationId, publicName, serverName, originalName, sessionId, requestId);

        // ── Step 7: Convert arguments to JsonNode ───────────────────────
        JsonNode argsAsJson;
        try {
            argsAsJson = objectMapper.valueToTree(
                    arguments != null ? arguments : Map.of());
        } catch (Exception e) {
            log.error("❌ [{}] Failed to convert arguments: {}", correlationId, e.getMessage());
            inFlightRegistry.fail(correlationId, "Argument conversion failed: " + e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    McpErrorCode.ORCHESTRATION_FAILURE,
                    "Argument conversion failed: " + e.getMessage(),
                    requestId);
            return buildErrorResult(McpErrorCode.ORCHESTRATION_FAILURE,
                    publicName, serverName, "Argument conversion failed: " + e.getMessage());
        }

        // ── Step 7.5: Resolve agent token override ─────────────────────
        boolean usingAgentToken = resolveAndApplyAgentToken(correlationId, serverName);

        // ── Step 8: Forward call via McpClientService ───────────────────
        long callStart = System.currentTimeMillis();
        try {
            log.info("📤 [{}] Forwarding to enterprise server '{}' → tool '{}' (token: {})",
                    correlationId, serverName, originalName,
                    usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

            List<McpSchema.Content> contentList =
                    mcpClientService.callTool(serverName, originalName, argsAsJson);

            long callDuration = System.currentTimeMillis() - callStart;
            long totalDuration = System.currentTimeMillis() - orchestrationStart;

            // ── Step 9: Audit — call forwarded successfully ─────────────
            auditService.auditOrchestrationCallForwarded(
                    correlationId, sessionId, serverName, originalName, callDuration, requestId);

            // ── Step 10: Deregister in-flight, return result ────────────
            inFlightRegistry.complete(correlationId);

            log.info("═══════════════════════════════════════════════════════════");
            log.info("✅ ORCHESTRATION COMPLETE [{}]", correlationId);
            log.info("   Tool: {} → {}.{}", publicName, serverName, originalName);
            log.info("   Response: {} content item(s)", contentList.size());
            log.info("   Forward: {}ms | Total: {}ms", callDuration, totalDuration);
            log.info("   Token: {}", usingAgentToken ? "agent-provided" : "config-based");
            log.info("   In-flight: {} active", inFlightRegistry.getActiveCount());
            log.info("═══════════════════════════════════════════════════════════");

            return new McpSchema.CallToolResult(contentList, false);

        } catch (IllegalArgumentException e) {
            // Server not connected — thrown by McpSessionManager.getSession()
            long callDuration = System.currentTimeMillis() - callStart;
            log.error("❌ [{}] Server '{}' unavailable: {}", correlationId, serverName, e.getMessage());

            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    McpErrorCode.SERVER_UNAVAILABLE, e.getMessage(),
                    requestId);

            return buildErrorResult(McpErrorCode.SERVER_UNAVAILABLE,
                    publicName, serverName, e.getMessage());

        } catch (Exception e) {
            // Any other failure during forwarding
            long callDuration = System.currentTimeMillis() - callStart;
            log.error("❌ [{}] Orchestration failure for '{}' on '{}': {}",
                    correlationId, publicName, serverName, e.getMessage(), e);

            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    McpErrorCode.ORCHESTRATION_FAILURE, e.getMessage(),
                    requestId);

            return buildErrorResult(McpErrorCode.ORCHESTRATION_FAILURE,
                    publicName, serverName, e.getMessage());
        } finally {
            // ── Always clean up ThreadLocal token override ───────────────
            if (usingAgentToken) {
                HttpMcpTransport.clearRequestOverrideHeaders();
                log.debug("🔑 [{}] Cleared agent token override from ThreadLocal", correlationId);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Resolve the agent session ID from the MCP SDK exchange.
     * Falls back to SessionManager if exchange doesn't provide one.
     * Best-effort — returns null if neither source has a session ID.
     */
    private String resolveSessionId(McpSyncServerExchange exchange) {
        // Primary: SDK exchange (authoritative, per-request)
        try {
            if (exchange != null) {
                String id = exchange.sessionId();
                if (id != null && !id.isBlank()) {
                    return id;
                }
            }
        } catch (Exception e) {
            log.debug("Could not get session ID from exchange: {}", e.getMessage());
        }

        // Fallback: SessionManager (our own POJO session)
        try {
            if (sessionManager != null) {
                return sessionManager.getCurrentSession().getSessionId();
            }
        } catch (Exception e) {
            log.debug("Could not resolve session ID from SessionManager: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Resolve the AI agent's client name + version from the exchange.
     * Returns a formatted string like "claude-desktop v1.2" for logging/audit.
     */
    private String resolveClientName(McpSyncServerExchange exchange) {
        try {
            if (exchange != null) {
                McpSchema.Implementation clientInfo = exchange.getClientInfo();
                if (clientInfo != null) {
                    String name = clientInfo.name() != null ? clientInfo.name() : "unknown";
                    String version = clientInfo.version() != null ? " v" + clientInfo.version() : "";
                    return name + version;
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve client info from exchange: {}", e.getMessage());
        }
        return "unknown";
    }

    // ════════════════════════════════════════════════════════════════════
    //  AGENT TOKEN OVERRIDE
    // ════════════════════════════════════════════════════════════════════

    /**
     * Well-known token keys that map to the HTTP Authorization header.
     * When any of these are found in the agent's session tokens, the value
     * is used to construct an Authorization header.
     */
    private static final Set<String> AUTH_TOKEN_KEYS = Set.of(
            "token", "accessToken", "access_token",
            "bearerToken", "bearer_token",
            "authToken", "auth_token",
            "authorization", "jwt"
    );

    /**
     * Token keys that map to an API key header (X-API-Key).
     */
    private static final Set<String> API_KEY_KEYS = Set.of(
            "apiKey", "api_key"
    );

    /**
     * Resolve agent-provided tokens from the session and apply them as
     * per-request header overrides on HttpMcpTransport via ThreadLocal.
     *
     * <p>If the agent has provided tokens during the initialize request,
     * they are stored in {@link ClientSession#getTokens()}. This method
     * maps those tokens to HTTP headers and sets them as overrides.
     *
     * <p>The override includes ALL non-auth config headers (like Content-Type,
     * Accept, etc.) merged with the agent's auth token — so only the auth
     * header is replaced, not the entire header set.
     *
     * @param correlationId for logging
     * @param serverName    the target enterprise server (for accessing config headers)
     * @return true if agent token was applied, false if using config token
     */
    private boolean resolveAndApplyAgentToken(String correlationId, String serverName) {
        try {
            if (sessionManager == null) {
                return false;
            }

            ClientSession session = sessionManager.getCurrentSession();
            if (session == null || !session.hasTokens()) {
                return false;
            }

            Map<String, String> agentTokens = session.getTokens();
            Map<String, String> overrideHeaders = buildOverrideHeaders(agentTokens, serverName);

            if (overrideHeaders != null && !overrideHeaders.isEmpty()) {
                HttpMcpTransport.setRequestOverrideHeaders(overrideHeaders);
                log.info("🔑 [{}] Agent token override applied ({} headers, keys: {})",
                        correlationId, overrideHeaders.size(), agentTokens.keySet());
                return true;
            }

        } catch (Exception e) {
            log.debug("Could not resolve agent token (using config token): {}", e.getMessage());
        }
        return false;
    }

    /**
     * Build the override header map by merging:
     * 1. All non-auth config headers from the transport (Accept, Content-Type, etc.)
     * 2. Agent-provided auth token mapped to the appropriate HTTP header
     *
     * @param agentTokens the tokens from ClientSession
     * @param serverName  the target server name (to look up config headers)
     * @return merged headers map, or null if no meaningful override
     */
    private Map<String, String> buildOverrideHeaders(Map<String, String> agentTokens,
                                                       String serverName) {
        if (agentTokens == null || agentTokens.isEmpty()) {
            return null;
        }

        Map<String, String> overrideHeaders = new HashMap<>();

        // Note: HttpMcpTransport.sendMessage() sets Accept, Content-Type, Cache-Control
        // directly (lines 68-70) BEFORE applying config/override headers. So the override
        // only needs to contain auth-related headers. Non-auth headers are always applied.

        // Map agent tokens to HTTP headers
        for (Map.Entry<String, String> entry : agentTokens.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value == null || value.isBlank()) {
                continue;
            }

            if ("authorization".equalsIgnoreCase(key)) {
                // Agent provided the full Authorization header value (e.g., "Bearer xyz")
                overrideHeaders.put("Authorization", value);
            } else if (AUTH_TOKEN_KEYS.contains(key)) {
                // Wrap as Bearer token
                if (value.toLowerCase().startsWith("bearer ")) {
                    overrideHeaders.put("Authorization", value);
                } else {
                    overrideHeaders.put("Authorization", "Bearer " + value);
                }
            } else if (API_KEY_KEYS.contains(key)) {
                // Map to X-API-Key header
                overrideHeaders.put("X-API-Key", value);
            }
            // Other keys (credentials, secret, key) — skip for now, no standard header mapping
        }

        return overrideHeaders.isEmpty() ? null : overrideHeaders;
    }

    /**
     * Build a CallToolResult with isError=true for a capability-not-found error.
     */
    private McpSchema.CallToolResult buildErrorResult(McpErrorCode errorCode,
                                                       String publicName) {
        String message = String.format("[%d] %s: tool '%s'",
                errorCode.getCode(), errorCode.getMessage(), publicName);
        return new McpSchema.CallToolResult(message, true);
    }

    /**
     * Build a CallToolResult with isError=true for a server/forwarding error.
     */
    private McpSchema.CallToolResult buildErrorResult(McpErrorCode errorCode,
                                                       String publicName,
                                                       String serverName,
                                                       String detail) {
        String message = String.format("[%d] %s: tool '%s' on server '%s' — %s",
                errorCode.getCode(), errorCode.getMessage(),
                publicName, serverName, detail);
        return new McpSchema.CallToolResult(message, true);
    }

    // ════════════════════════════════════════════════════════════════════
    //  PROMPT ORCHESTRATION
    // ════════════════════════════════════════════════════════════════════

    /**
     * Orchestrate a getPrompt request from an AI agent to the correct enterprise MCP server.
     *
     * <p>Follows the same audited flow as tool call orchestration:
     * correlation ID → session resolution → registry lookup → forward → audit → return.
     *
     * @param exchange   the MCP SDK exchange
     * @param publicName the namespaced prompt name as registered with the AI agent (e.g., "github_generate_pr_description")
     * @param arguments  the prompt arguments from GetPromptRequest.arguments()
     * @return GetPromptResult from the enterprise server
     */
    public McpSchema.GetPromptResult orchestrateGetPrompt(McpSyncServerExchange exchange,
                                                            String publicName,
                                                            Map<String, Object> arguments) {
        String correlationId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Object rawJsonRpcId = exchange.transportContext().get("jsonRpcRequestId");
        String requestId = rawJsonRpcId != null ? String.valueOf(rawJsonRpcId) : null;
        String sessionId = resolveSessionId(exchange);
        String clientName = resolveClientName(exchange);

        log.info("═══════════════════════════════════════════════════════════");
        log.info("📝 PROMPT ORCHESTRATION START [{}]", correlationId);
        log.info("   Prompt: {}", publicName);
        log.info("   Args: {}", arguments != null ? arguments.keySet() : "null");
        log.info("   Session: {}", sessionId != null ? sessionId : "unknown");
        log.info("   Agent: {}", clientName);
        log.info("   JSON-RPC ID: {}", requestId != null ? requestId : "n/a");
        log.info("═══════════════════════════════════════════════════════════");

        long orchestrationStart = System.currentTimeMillis();

        // Audit — prompt extracted
        auditService.auditOrchestrationToolExtracted(correlationId, publicName, sessionId, requestId);

        // Registry lookup
        long lookupStart = System.currentTimeMillis();
        Optional<CapabilityDescriptor> optDescriptor = registryService.lookupByPublicName(publicName);
        long lookupDuration = System.currentTimeMillis() - lookupStart;

        if (optDescriptor.isEmpty()) {
            log.error("❌ [{}] Prompt '{}' NOT FOUND in capability registry", correlationId, publicName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    McpErrorCode.CAPABILITY_NOT_FOUND,
                    "Prompt '" + publicName + "' not found in capability registry",
                    requestId);
            throw new RuntimeException(String.format("[%d] %s: prompt '%s'",
                    McpErrorCode.CAPABILITY_NOT_FOUND.getCode(),
                    McpErrorCode.CAPABILITY_NOT_FOUND.getMessage(), publicName));
        }

        CapabilityDescriptor descriptor = optDescriptor.get();
        String serverName = descriptor.getServerConfigName();
        String originalName = descriptor.getOriginalName();

        log.info("🔍 [{}] Registry resolved: {} → server='{}', original='{}'",
                correlationId, publicName, serverName, originalName);

        auditService.auditOrchestrationRegistryLookup(
                correlationId, sessionId, publicName, serverName, lookupDuration, requestId);

        // Register in-flight
        inFlightRegistry.register(correlationId, publicName, serverName, originalName, sessionId, requestId);

        // Resolve agent token override
        boolean usingAgentToken = resolveAndApplyAgentToken(correlationId, serverName);

        // Forward to enterprise server
        long callStart = System.currentTimeMillis();
        try {
            log.info("📤 [{}] Forwarding getPrompt to '{}' → prompt '{}' (token: {})",
                    correlationId, serverName, originalName,
                    usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

            McpSchema.GetPromptResult result =
                    mcpClientService.getPrompt(serverName, originalName, arguments);

            long callDuration = System.currentTimeMillis() - callStart;
            long totalDuration = System.currentTimeMillis() - orchestrationStart;

            auditService.auditOrchestrationCallForwarded(
                    correlationId, sessionId, serverName, originalName, callDuration, requestId);

            inFlightRegistry.complete(correlationId);

            int messageCount = result.messages() != null ? result.messages().size() : 0;
            log.info("═══════════════════════════════════════════════════════════");
            log.info("✅ PROMPT ORCHESTRATION COMPLETE [{}]", correlationId);
            log.info("   Prompt: {} → {}.{}", publicName, serverName, originalName);
            log.info("   Response: {} message(s)", messageCount);
            log.info("   Forward: {}ms | Total: {}ms", callDuration, totalDuration);
            log.info("   In-flight: {} active", inFlightRegistry.getActiveCount());
            log.info("═══════════════════════════════════════════════════════════");

            return result;

        } catch (IllegalArgumentException e) {
            log.error("❌ [{}] Server '{}' unavailable: {}", correlationId, serverName, e.getMessage());
            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    McpErrorCode.SERVER_UNAVAILABLE, e.getMessage(),
                    requestId);
            throw new RuntimeException(String.format("[%d] %s: prompt '%s' on server '%s' — %s",
                    McpErrorCode.SERVER_UNAVAILABLE.getCode(),
                    McpErrorCode.SERVER_UNAVAILABLE.getMessage(),
                    publicName, serverName, e.getMessage()), e);

        } catch (Exception e) {
            log.error("❌ [{}] Prompt orchestration failure for '{}' on '{}': {}",
                    correlationId, publicName, serverName, e.getMessage(), e);
            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    McpErrorCode.ORCHESTRATION_FAILURE, e.getMessage(),
                    requestId);
            throw new RuntimeException(String.format("[%d] %s: prompt '%s' on server '%s' — %s",
                    McpErrorCode.ORCHESTRATION_FAILURE.getCode(),
                    McpErrorCode.ORCHESTRATION_FAILURE.getMessage(),
                    publicName, serverName, e.getMessage()), e);
        } finally {
            if (usingAgentToken) {
                HttpMcpTransport.clearRequestOverrideHeaders();
                log.debug("🔑 [{}] Cleared agent token override from ThreadLocal", correlationId);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  RESOURCE ORCHESTRATION
    // ════════════════════════════════════════════════════════════════════

    /**
     * Orchestrate a readResource request from an AI agent to the correct enterprise MCP server.
     *
     * <p>Follows the same audited flow as tool call orchestration:
     * correlation ID → session resolution → registry lookup → forward → audit → return.
     *
     * @param exchange      the MCP SDK exchange
     * @param publicName    the namespaced resource name as registered with the AI agent
     * @param resourceUri   the resource URI from ReadResourceRequest
     * @return ReadResourceResult from the enterprise server
     */
    public McpSchema.ReadResourceResult orchestrateReadResource(McpSyncServerExchange exchange,
                                                                  String publicName,
                                                                  String resourceUri) {
        String correlationId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Object rawJsonRpcId = exchange.transportContext().get("jsonRpcRequestId");
        String requestId = rawJsonRpcId != null ? String.valueOf(rawJsonRpcId) : null;
        String sessionId = resolveSessionId(exchange);
        String clientName = resolveClientName(exchange);

        log.info("═══════════════════════════════════════════════════════════");
        log.info("📖 RESOURCE ORCHESTRATION START [{}]", correlationId);
        log.info("   Resource: {}", publicName);
        log.info("   URI: {}", resourceUri);
        log.info("   Session: {}", sessionId != null ? sessionId : "unknown");
        log.info("   Agent: {}", clientName);
        log.info("   JSON-RPC ID: {}", requestId != null ? requestId : "n/a");
        log.info("═══════════════════════════════════════════════════════════");

        long orchestrationStart = System.currentTimeMillis();

        // Audit — resource extracted
        auditService.auditOrchestrationToolExtracted(correlationId, publicName, sessionId, requestId);

        // Registry lookup by public name
        long lookupStart = System.currentTimeMillis();
        Optional<CapabilityDescriptor> optDescriptor = registryService.lookupByPublicName(publicName);
        long lookupDuration = System.currentTimeMillis() - lookupStart;

        if (optDescriptor.isEmpty()) {
            log.error("❌ [{}] Resource '{}' NOT FOUND in capability registry", correlationId, publicName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    McpErrorCode.CAPABILITY_NOT_FOUND,
                    "Resource '" + publicName + "' not found in capability registry",
                    requestId);
            throw new RuntimeException(String.format("[%d] %s: resource '%s'",
                    McpErrorCode.CAPABILITY_NOT_FOUND.getCode(),
                    McpErrorCode.CAPABILITY_NOT_FOUND.getMessage(), publicName));
        }

        CapabilityDescriptor descriptor = optDescriptor.get();
        String serverName = descriptor.getServerConfigName();
        String originalUri = descriptor.getResourceUri();

        log.info("🔍 [{}] Registry resolved: {} → server='{}', uri='{}'",
                correlationId, publicName, serverName, originalUri);

        auditService.auditOrchestrationRegistryLookup(
                correlationId, sessionId, publicName, serverName, lookupDuration, requestId);

        // Register in-flight
        inFlightRegistry.register(correlationId, publicName, serverName, originalUri, sessionId, requestId);

        // Resolve agent token override
        boolean usingAgentToken = resolveAndApplyAgentToken(correlationId, serverName);

        // Forward to enterprise server
        long callStart = System.currentTimeMillis();
        try {
            log.info("📤 [{}] Forwarding readResource to '{}' → uri '{}' (token: {})",
                    correlationId, serverName, originalUri,
                    usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

            List<McpSchema.ResourceContents> contents =
                    mcpClientService.readResource(serverName, originalUri);

            long callDuration = System.currentTimeMillis() - callStart;
            long totalDuration = System.currentTimeMillis() - orchestrationStart;

            auditService.auditOrchestrationCallForwarded(
                    correlationId, sessionId, serverName, publicName, callDuration, requestId);

            inFlightRegistry.complete(correlationId);

            log.info("═══════════════════════════════════════════════════════════");
            log.info("✅ RESOURCE ORCHESTRATION COMPLETE [{}]", correlationId);
            log.info("   Resource: {} → {}", publicName, originalUri);
            log.info("   Response: {} content item(s)", contents.size());
            log.info("   Forward: {}ms | Total: {}ms", callDuration, totalDuration);
            log.info("   In-flight: {} active", inFlightRegistry.getActiveCount());
            log.info("═══════════════════════════════════════════════════════════");

            return new McpSchema.ReadResourceResult(contents);

        } catch (IllegalArgumentException e) {
            log.error("❌ [{}] Server '{}' unavailable: {}", correlationId, serverName, e.getMessage());
            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    McpErrorCode.SERVER_UNAVAILABLE, e.getMessage(),
                    requestId);
            throw new RuntimeException(String.format("[%d] %s: resource '%s' on server '%s' — %s",
                    McpErrorCode.SERVER_UNAVAILABLE.getCode(),
                    McpErrorCode.SERVER_UNAVAILABLE.getMessage(),
                    publicName, serverName, e.getMessage()), e);

        } catch (Exception e) {
            log.error("❌ [{}] Resource orchestration failure for '{}' on '{}': {}",
                    correlationId, publicName, serverName, e.getMessage(), e);
            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    McpErrorCode.ORCHESTRATION_FAILURE, e.getMessage(),
                    requestId);
            throw new RuntimeException(String.format("[%d] %s: resource '%s' on server '%s' — %s",
                    McpErrorCode.ORCHESTRATION_FAILURE.getCode(),
                    McpErrorCode.ORCHESTRATION_FAILURE.getMessage(),
                    publicName, serverName, e.getMessage()), e);
        } finally {
            if (usingAgentToken) {
                HttpMcpTransport.clearRequestOverrideHeaders();
                log.debug("🔑 [{}] Cleared agent token override from ThreadLocal", correlationId);
            }
        }
    }
}
