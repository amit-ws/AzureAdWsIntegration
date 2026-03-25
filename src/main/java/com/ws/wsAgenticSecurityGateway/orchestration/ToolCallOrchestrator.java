package com.ws.wsAgenticSecurityGateway.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.audit.error.McpErrorCode;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.wsClient.service.McpClientService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentCapabilityFilterService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationRequest;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationResult;
import com.ws.wsAgenticSecurityGateway.pdp.service.CedarPolicyEngine;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyContextBuilder;
import com.ws.wsAgenticSecurityGateway.wsClient.config.HttpMcpTransport;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSession;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSessionManager;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.wsServer.session.ClientSession;
import com.ws.wsAgenticSecurityGateway.wsServer.session.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    private final McpSessionManager mcpSessionManager;
    private final AgentRegistryService agentRegistryService;
    private final AgentCapabilityFilterService capabilityFilterService;
    private final CedarPolicyEngine cedarPolicyEngine;
    private final PolicyContextBuilder policyContextBuilder;

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
                                 ObjectMapper objectMapper,
                                 McpSessionManager mcpSessionManager,
                                 AgentRegistryService agentRegistryService,
                                 AgentCapabilityFilterService capabilityFilterService,
                                 CedarPolicyEngine cedarPolicyEngine,
                                 PolicyContextBuilder policyContextBuilder) {
        this.registryService = registryService;
        this.mcpClientService = mcpClientService;
        this.auditService = auditService;
        this.inFlightRegistry = inFlightRegistry;
        this.objectMapper = objectMapper;
        this.mcpSessionManager = mcpSessionManager;
        this.agentRegistryService = agentRegistryService;
        this.capabilityFilterService = capabilityFilterService;
        this.cedarPolicyEngine = cedarPolicyEngine;
        this.policyContextBuilder = policyContextBuilder;
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

        // ── Step 2b: Track agent request (async, non-blocking) ───────
        agentRegistryService.recordRequest(sessionId);

        // Monotonic event sequence counter for deterministic audit ordering within this correlation chain
        int seq = 0;

        // NOTE: Agent/Human/NHI block checks are enforced at the HTTP filter layer
        // (HttpMcpAuditFilter) which runs BEFORE the request reaches the SDK/orchestrator.
        // The filter uses authoritative DB status checks that survive disconnectSession() cleanup.
        // The filter uses blockedSessionIds (O(1) HashSet) + fallback DB check via authIdentity.
        // No need to duplicate DB queries on the hot path.

        // ── Step 2d: Capability access check (default deny) ──────────
        // If agent has no capability profiles → no access (default deny).
        // If agent has profiles → check if this tool is in their allowed set.
        UUID agentIdForAccess = agentRegistryService.getAgentIdForSession(sessionId);
        if (agentIdForAccess != null
                && !capabilityFilterService.isCapabilityAllowed(agentIdForAccess, publicName, "TOOL")) {
            log.warn("🚫 [{}] CAPABILITY ACCESS DENIED: session={}, tool={}, agent={}",
                    correlationId, sessionId, publicName, clientName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    McpErrorCode.CAPABILITY_NOT_ALLOWED,
                    "Tool '" + publicName + "' is not permitted for this agent. "
                            + "Contact your gateway administrator to assign a capability profile.",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            auditService.auditCapabilityAccessDenied(sessionId, correlationId, clientName, publicName, "TOOL",
                    LocalDateTime.now(), ++seq);
            return buildErrorResult(McpErrorCode.CAPABILITY_NOT_ALLOWED, publicName);
        }
        // Audit capability access granted (only for managed agents with profiles)
        if (agentIdForAccess != null && capabilityFilterService.hasProfiles(agentIdForAccess)) {
            auditService.auditCapabilityAccessGranted(sessionId, correlationId, clientName, publicName, "TOOL",
                    LocalDateTime.now(), ++seq);
        }

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
        auditService.auditOrchestrationToolExtracted(correlationId, publicName, sessionId, requestId, clientName,
                LocalDateTime.now(), ++seq);

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
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            return buildErrorResult(McpErrorCode.CAPABILITY_NOT_FOUND, publicName);
        }

        CapabilityDescriptor descriptor = optDescriptor.get();
        String serverName = descriptor.getServerConfigName();
        String originalName = descriptor.getOriginalName();

        log.info("🔍 [{}] Registry resolved: {} → server='{}', original='{}'",
                correlationId, publicName, serverName, originalName);

        // ── Step 5: Audit — registry lookup success ─────────────────────
        auditService.auditOrchestrationRegistryLookup(
                correlationId, sessionId, publicName, serverName, lookupDuration, requestId, clientName,
                LocalDateTime.now(), ++seq);

        // ── Step 5c: PDP — Cedar policy evaluation ─────────────────────
        // Evaluate the request against all active Cedar policies.
        // This is the Policy Decision Point (PDP) — the core security gate.
        try {
            PolicyEvaluationRequest pdpRequest = policyContextBuilder.buildForToolCall(
                    exchange, publicName, serverName, originalName,
                    arguments, correlationId, sessionId);

            // Audit: PDP evaluation requested
            auditService.auditPdpEvaluationRequested(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "toolCall", serverName, pdpRequest, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            PolicyEvaluationResult pdpResult = cedarPolicyEngine.evaluate(pdpRequest);

            // Audit: PDP decision rendered
            auditService.auditPdpDecisionRendered(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "toolCall", pdpResult.getDecision(),
                    serverName, pdpResult, pdpResult.getEvaluationDurationMs(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

            if (pdpResult.isDenied()) {
                log.warn("🏛️ [{}] PDP DENIED: agent='{}', tool='{}', reason='{}'",
                        correlationId, pdpRequest.getAgentName(), publicName, pdpResult.getReason());
                return buildErrorResult(McpErrorCode.PDP_DENIED,
                        publicName, serverName,
                        "Policy violation: " + pdpResult.getReason());
            }

            log.info("🏛️ [{}] PDP ALLOWED: agent='{}', tool='{}' ({}ms)",
                    correlationId, pdpRequest.getAgentName(), publicName,
                    pdpResult.getEvaluationDurationMs());

        } catch (Exception e) {
            // PDP evaluation failure — fail-open (don't block gateway on engine errors)
            log.error("🏛️ [{}] PDP evaluation error (fail-open): {}", correlationId, e.getMessage());
        }

        // ── Step 5b: Pre-check — is the server connected? ────────────────
        // Fail fast if the enterprise MCP server is disconnected instead of
        // waiting for an HTTP timeout (~75s) during callTool().
        if (!mcpSessionManager.isConnected(serverName)) {
            log.error("❌ [{}] Server '{}' is not connected — rejecting immediately", correlationId, serverName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    McpErrorCode.SERVER_UNAVAILABLE,
                    "Server '" + serverName + "' is not connected",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            return buildErrorResult(McpErrorCode.SERVER_UNAVAILABLE,
                    publicName, serverName, "Server '" + serverName + "' is not connected");
        }

        String clientSessionId = null;
        try {
            McpSession clientSession = mcpSessionManager.getSession(serverName);
            clientSessionId = clientSession.getSessionId();
        } catch (Exception ignored) {}

        // ── Step 6: Register in-flight ──────────────────────────────────
        String agentName = "unknown";
        String agentVersion = "";
        try {
            if (exchange != null && exchange.getClientInfo() != null) {
                McpSchema.Implementation ci = exchange.getClientInfo();
                agentName = ci.name() != null ? ci.name() : "unknown";
                agentVersion = ci.version() != null ? ci.version() : "";
            }
        } catch (Exception ignored) {}
        inFlightRegistry.register(correlationId, publicName, serverName, originalName,
                sessionId, requestId, agentName, agentVersion);
        if (clientSessionId != null) {
            inFlightRegistry.updateClientSession(correlationId, clientSessionId);
        }

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
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            return buildErrorResult(McpErrorCode.ORCHESTRATION_FAILURE,
                    publicName, serverName, "Argument conversion failed: " + e.getMessage());
        }

        // ── Step 7b: Capture request args for in-flight visibility ────────
        String argsStr = argsAsJson.toString();
        inFlightRegistry.updateRequest(correlationId,
                argsStr.length() > 2000 ? argsStr.substring(0, 2000) + "..." : argsStr);

        // ── Step 7.5: Resolve agent token override ─────────────────────
        boolean usingAgentToken = resolveAndApplyAgentToken(correlationId, serverName, exchange);
        inFlightRegistry.updateTokenMode(correlationId, usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

        // ── Step 8: Forward call via McpClientService ───────────────────
        long callStart = System.currentTimeMillis();
        try {
            log.info("📤 [{}] Forwarding to enterprise server '{}' → tool '{}' (token: {})",
                    correlationId, serverName, originalName,
                    usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

            List<McpSchema.Content> contentList =
                    mcpClientService.callTool(correlationId, serverName, originalName, argsAsJson,
                            LocalDateTime.now(), ++seq);

            long callDuration = System.currentTimeMillis() - callStart;
            long totalDuration = System.currentTimeMillis() - orchestrationStart;

            // ── Step 8b: Capture response summary for in-flight visibility ──
            enrichResponseData(correlationId, contentList);
            inFlightRegistry.updateTimings(correlationId, lookupDuration, callDuration);

            // ── Step 9: Audit — call forwarded successfully ─────────────
            auditService.auditOrchestrationCallForwarded(
                    correlationId, sessionId, serverName, originalName, callDuration, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            // ── Step 10: Deregister in-flight, return result ────────────
            inFlightRegistry.complete(correlationId);

            // ── Keep session alive during long-running tools ─────────
            // Update activity timestamp on response completion so the idle
            // reaper doesn't kill sessions with long-running tool calls.
            agentRegistryService.updateLastActivity(sessionId);

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
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

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
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

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
        // Primary: Our own ClientSession (consistent with initialization audit + session registry).
        // This ensures orchestration events share the same sessionId as initialization events,
        // so timelines, analytics, and agent blocking all work with one consistent ID.
        try {
            if (sessionManager != null) {
                ClientSession cs = sessionManager.getCurrentSession();
                if (cs != null) {
                    String id = cs.getSessionId();
                    if (id != null && !id.isBlank()) {
                        return id;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve session ID from SessionManager: {}", e.getMessage());
        }

        // Fallback: SDK exchange
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
    //  IN-FLIGHT ENRICHMENT HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Extract response summary from a tool call result content list.
     * Captures content types and first text content (truncated to 2000 chars).
     */
    private void enrichResponseData(String correlationId, List<McpSchema.Content> contentList) {
        try {
            String summary = "";
            StringBuilder types = new StringBuilder();
            for (McpSchema.Content c : contentList) {
                if (c instanceof McpSchema.TextContent t) {
                    if (summary.isEmpty()) {
                        summary = t.text().length() > 2000 ? t.text().substring(0, 2000) + "..." : t.text();
                    }
                    types.append(types.length() > 0 ? "," : "").append("TEXT");
                } else if (c instanceof McpSchema.ImageContent) {
                    types.append(types.length() > 0 ? "," : "").append("IMAGE");
                } else if (c instanceof McpSchema.AudioContent) {
                    types.append(types.length() > 0 ? "," : "").append("AUDIO");
                } else if (c instanceof McpSchema.EmbeddedResource) {
                    types.append(types.length() > 0 ? "," : "").append("RESOURCE");
                }
            }
            inFlightRegistry.updateResponse(correlationId, summary, contentList.size(), types.toString());
        } catch (Exception e) {
            log.debug("Could not enrich response data for {}: {}", correlationId, e.getMessage());
        }
    }

    /**
     * Extract response summary from a prompt result's messages.
     */
    private void enrichPromptResponseData(String correlationId, McpSchema.GetPromptResult result) {
        try {
            int messageCount = result.messages() != null ? result.messages().size() : 0;
            String summary = "";
            if (result.description() != null && !result.description().isBlank()) {
                summary = result.description().length() > 2000
                        ? result.description().substring(0, 2000) + "..." : result.description();
            }
            inFlightRegistry.updateResponse(correlationId, summary, messageCount, "PROMPT");
        } catch (Exception e) {
            log.debug("Could not enrich prompt response data for {}: {}", correlationId, e.getMessage());
        }
    }

    /**
     * Extract response summary from a resource read result.
     */
    private void enrichResourceResponseData(String correlationId, List<McpSchema.ResourceContents> contents) {
        try {
            String summary = "";
            StringBuilder types = new StringBuilder();
            for (McpSchema.ResourceContents rc : contents) {
                if (rc instanceof McpSchema.TextResourceContents t) {
                    if (summary.isEmpty()) {
                        summary = t.text().length() > 2000 ? t.text().substring(0, 2000) + "..." : t.text();
                    }
                    types.append(types.length() > 0 ? "," : "").append("TEXT");
                } else if (rc instanceof McpSchema.BlobResourceContents) {
                    types.append(types.length() > 0 ? "," : "").append("BLOB");
                }
            }
            inFlightRegistry.updateResponse(correlationId, summary, contents.size(), types.toString());
        } catch (Exception e) {
            log.debug("Could not enrich resource response data for {}: {}", correlationId, e.getMessage());
        }
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
     * @param exchange      the MCP exchange (carries HTTP transport context in HTTP mode)
     * @return true if agent token was applied, false if using config token
     */
    private boolean resolveAndApplyAgentToken(String correlationId, String serverName,
                                               McpSyncServerExchange exchange) {
        // ── (1) Stdio mode: tokens from ClientSession ─────────────────
        try {
            if (sessionManager != null) {
                ClientSession session = sessionManager.getCurrentSession();
                if (session != null && session.hasTokens()) {
                    Map<String, String> agentTokens = session.getTokens();
                    Map<String, String> overrideHeaders = buildOverrideHeaders(agentTokens, serverName);

                    if (overrideHeaders != null && !overrideHeaders.isEmpty()) {
                        HttpMcpTransport.setRequestOverrideHeaders(overrideHeaders);
                        log.info("🔑 [{}] Agent token override applied ({} headers, keys: {})",
                                correlationId, overrideHeaders.size(), agentTokens.keySet());
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve agent token from session: {}", e.getMessage());
        }

        // ── (2) HTTP mode fallback: token from transport context ──────
        // NOTE: In OAuth2 mode, the transport context "authorization" contains the
        // agent's Keycloak JWT (used to authenticate to the gateway). This MUST NOT
        // be forwarded to downstream servers — they have their own auth configured
        // in the server config headers. Only forward if the agent explicitly provides
        // a separate downstream token (e.g., via a custom "x-downstream-token" header).
        // Agent token forwarding in HTTP mode is disabled to prevent leaking the
        // gateway auth JWT to downstream servers.

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
        agentRegistryService.recordRequest(sessionId);
        int seq = 0;

        // NOTE: Agent/Human/NHI block checks enforced at HTTP filter layer (authoritative DB checks).

        // Capability access check (default deny)
        UUID promptAgentId = agentRegistryService.getAgentIdForSession(sessionId);
        if (promptAgentId != null
                && !capabilityFilterService.isCapabilityAllowed(promptAgentId, publicName, "PROMPT")) {
            log.warn("🚫 [{}] CAPABILITY ACCESS DENIED: session={}, prompt={}, agent={}",
                    correlationId, sessionId, publicName, clientName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    McpErrorCode.CAPABILITY_NOT_ALLOWED,
                    "Prompt '" + publicName + "' is not permitted for this agent.",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            auditService.auditCapabilityAccessDenied(sessionId, correlationId, clientName, publicName, "PROMPT",
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: prompt '%s'",
                    McpErrorCode.CAPABILITY_NOT_ALLOWED.getCode(),
                    McpErrorCode.CAPABILITY_NOT_ALLOWED.getMessage(), publicName));
        }
        // Audit capability access granted (only for managed agents with profiles)
        if (promptAgentId != null && capabilityFilterService.hasProfiles(promptAgentId)) {
            auditService.auditCapabilityAccessGranted(sessionId, correlationId, clientName, publicName, "PROMPT",
                    LocalDateTime.now(), ++seq);
        }

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
        auditService.auditOrchestrationToolExtracted(correlationId, publicName, sessionId, requestId, clientName,
                LocalDateTime.now(), ++seq);

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
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
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
                correlationId, sessionId, publicName, serverName, lookupDuration, requestId, clientName,
                LocalDateTime.now(), ++seq);

        // ── PDP — Cedar policy evaluation for prompt ────────────────────
        try {
            PolicyEvaluationRequest pdpRequest = policyContextBuilder.buildForPromptGet(
                    exchange, publicName, serverName, originalName,
                    correlationId, sessionId);

            auditService.auditPdpEvaluationRequested(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "promptGet", serverName, pdpRequest, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            PolicyEvaluationResult pdpResult = cedarPolicyEngine.evaluate(pdpRequest);

            auditService.auditPdpDecisionRendered(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "promptGet", pdpResult.getDecision(),
                    serverName, pdpResult, pdpResult.getEvaluationDurationMs(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

            if (pdpResult.isDenied()) {
                log.warn("🏛️ [{}] PDP DENIED: agent='{}', prompt='{}', reason='{}'",
                        correlationId, pdpRequest.getAgentName(), publicName, pdpResult.getReason());
                throw new RuntimeException(String.format("[%d] Policy violation: %s",
                        McpErrorCode.PDP_DENIED.getCode(), pdpResult.getReason()));
            }

            log.info("🏛️ [{}] PDP ALLOWED: agent='{}', prompt='{}' ({}ms)",
                    correlationId, pdpRequest.getAgentName(), publicName,
                    pdpResult.getEvaluationDurationMs());

        } catch (RuntimeException e) {
            throw e; // re-throw PDP denial
        } catch (Exception e) {
            log.error("🏛️ [{}] PDP evaluation error (fail-open): {}", correlationId, e.getMessage());
        }

        // Pre-check — is the server connected?
        if (!mcpSessionManager.isConnected(serverName)) {
            log.error("❌ [{}] Server '{}' is not connected — rejecting prompt immediately", correlationId, serverName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    McpErrorCode.SERVER_UNAVAILABLE,
                    "Server '" + serverName + "' is not connected",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: server '%s' is not connected",
                    McpErrorCode.SERVER_UNAVAILABLE.getCode(),
                    McpErrorCode.SERVER_UNAVAILABLE.getMessage(), serverName));
        }

        // Resolve client (WS Client-side) session ID
        String pClientSessionId = null;
        try {
            McpSession pClientSession = mcpSessionManager.getSession(serverName);
            pClientSessionId = pClientSession.getSessionId();
        } catch (Exception ignored) {}

        // Register in-flight
        String pAgentName = "unknown";
        String pAgentVersion = "";
        try {
            if (exchange != null && exchange.getClientInfo() != null) {
                McpSchema.Implementation ci = exchange.getClientInfo();
                pAgentName = ci.name() != null ? ci.name() : "unknown";
                pAgentVersion = ci.version() != null ? ci.version() : "";
            }
        } catch (Exception ignored) {}
        inFlightRegistry.register(correlationId, publicName, serverName, originalName,
                sessionId, requestId, pAgentName, pAgentVersion);
        if (pClientSessionId != null) {
            inFlightRegistry.updateClientSession(correlationId, pClientSessionId);
        }

        // Capture request args
        try {
            String pArgsStr = objectMapper.writeValueAsString(arguments != null ? arguments : Map.of());
            inFlightRegistry.updateRequest(correlationId,
                    pArgsStr.length() > 2000 ? pArgsStr.substring(0, 2000) + "..." : pArgsStr);
        } catch (Exception ignored) {}

        // Resolve agent token override
        boolean usingAgentToken = resolveAndApplyAgentToken(correlationId, serverName, exchange);
        inFlightRegistry.updateTokenMode(correlationId, usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

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

            enrichPromptResponseData(correlationId, result);
            inFlightRegistry.updateTimings(correlationId, lookupDuration, callDuration);

            auditService.auditOrchestrationCallForwarded(
                    correlationId, sessionId, serverName, originalName, callDuration, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            inFlightRegistry.complete(correlationId);

            // Keep session alive during long-running prompts
            agentRegistryService.updateLastActivity(sessionId);

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
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
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
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
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
        agentRegistryService.recordRequest(sessionId);
        int seq = 0;

        // NOTE: Agent/Human/NHI block checks enforced at HTTP filter layer (authoritative DB checks).

        // Capability access check (default deny)
        UUID resourceAgentId = agentRegistryService.getAgentIdForSession(sessionId);
        if (resourceAgentId != null
                && !capabilityFilterService.isCapabilityAllowed(resourceAgentId, publicName, "RESOURCE")) {
            log.warn("🚫 [{}] CAPABILITY ACCESS DENIED: session={}, resource={}, agent={}",
                    correlationId, sessionId, publicName, clientName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, null, publicName,
                    McpErrorCode.CAPABILITY_NOT_ALLOWED,
                    "Resource '" + publicName + "' is not permitted for this agent.",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            auditService.auditCapabilityAccessDenied(sessionId, correlationId, clientName, publicName, "RESOURCE",
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: resource '%s'",
                    McpErrorCode.CAPABILITY_NOT_ALLOWED.getCode(),
                    McpErrorCode.CAPABILITY_NOT_ALLOWED.getMessage(), publicName));
        }
        // Audit capability access granted (only for managed agents with profiles)
        if (resourceAgentId != null && capabilityFilterService.hasProfiles(resourceAgentId)) {
            auditService.auditCapabilityAccessGranted(sessionId, correlationId, clientName, publicName, "RESOURCE",
                    LocalDateTime.now(), ++seq);
        }

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
        auditService.auditOrchestrationToolExtracted(correlationId, publicName, sessionId, requestId, clientName,
                LocalDateTime.now(), ++seq);

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
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
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
                correlationId, sessionId, publicName, serverName, lookupDuration, requestId, clientName,
                LocalDateTime.now(), ++seq);

        // ── PDP — Cedar policy evaluation for resource read ─────────────
        try {
            PolicyEvaluationRequest pdpRequest = policyContextBuilder.buildForResourceRead(
                    exchange, publicName, serverName, descriptor.getOriginalName(),
                    correlationId, sessionId);

            auditService.auditPdpEvaluationRequested(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "resourceRead", serverName, pdpRequest, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            PolicyEvaluationResult pdpResult = cedarPolicyEngine.evaluate(pdpRequest);

            auditService.auditPdpDecisionRendered(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "resourceRead", pdpResult.getDecision(),
                    serverName, pdpResult, pdpResult.getEvaluationDurationMs(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

            if (pdpResult.isDenied()) {
                log.warn("🏛️ [{}] PDP DENIED: agent='{}', resource='{}', reason='{}'",
                        correlationId, pdpRequest.getAgentName(), publicName, pdpResult.getReason());
                throw new RuntimeException(String.format("[%d] Policy violation: %s",
                        McpErrorCode.PDP_DENIED.getCode(), pdpResult.getReason()));
            }

            log.info("🏛️ [{}] PDP ALLOWED: agent='{}', resource='{}' ({}ms)",
                    correlationId, pdpRequest.getAgentName(), publicName,
                    pdpResult.getEvaluationDurationMs());

        } catch (RuntimeException e) {
            throw e; // re-throw PDP denial
        } catch (Exception e) {
            log.error("🏛️ [{}] PDP evaluation error (fail-open): {}", correlationId, e.getMessage());
        }

        // Pre-check — is the server connected?
        if (!mcpSessionManager.isConnected(serverName)) {
            log.error("❌ [{}] Server '{}' is not connected — rejecting resource read immediately", correlationId, serverName);
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    McpErrorCode.SERVER_UNAVAILABLE,
                    "Server '" + serverName + "' is not connected",
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
            throw new RuntimeException(String.format("[%d] %s: server '%s' is not connected",
                    McpErrorCode.SERVER_UNAVAILABLE.getCode(),
                    McpErrorCode.SERVER_UNAVAILABLE.getMessage(), serverName));
        }

        // Resolve client (WS Client-side) session ID
        String rClientSessionId = null;
        try {
            McpSession rClientSession = mcpSessionManager.getSession(serverName);
            rClientSessionId = rClientSession.getSessionId();
        } catch (Exception ignored) {}

        // Register in-flight
        String rAgentName = "unknown";
        String rAgentVersion = "";
        try {
            if (exchange != null && exchange.getClientInfo() != null) {
                McpSchema.Implementation ci = exchange.getClientInfo();
                rAgentName = ci.name() != null ? ci.name() : "unknown";
                rAgentVersion = ci.version() != null ? ci.version() : "";
            }
        } catch (Exception ignored) {}
        inFlightRegistry.register(correlationId, publicName, serverName, originalUri,
                sessionId, requestId, rAgentName, rAgentVersion);
        if (rClientSessionId != null) {
            inFlightRegistry.updateClientSession(correlationId, rClientSessionId);
        }

        // Capture request args (resource URI as the "request")
        inFlightRegistry.updateRequest(correlationId, resourceUri != null ? resourceUri : "");

        // Resolve agent token override
        boolean usingAgentToken = resolveAndApplyAgentToken(correlationId, serverName, exchange);
        inFlightRegistry.updateTokenMode(correlationId, usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

        // Forward to enterprise server
        long callStart = System.currentTimeMillis();
        try {
            log.info("📤 [{}] Forwarding readResource to '{}' → uri '{}' (token: {})",
                    correlationId, serverName, originalUri,
                    usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

            List<McpSchema.ResourceContents> contents =
                    mcpClientService.readResource(correlationId, serverName, originalUri,
                            LocalDateTime.now(), ++seq);

            long callDuration = System.currentTimeMillis() - callStart;
            long totalDuration = System.currentTimeMillis() - orchestrationStart;

            enrichResourceResponseData(correlationId, contents);
            inFlightRegistry.updateTimings(correlationId, lookupDuration, callDuration);

            auditService.auditOrchestrationCallForwarded(
                    correlationId, sessionId, serverName, publicName, callDuration, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            inFlightRegistry.complete(correlationId);

            // Keep session alive during long-running resource reads
            agentRegistryService.updateLastActivity(sessionId);

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
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
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
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);
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
