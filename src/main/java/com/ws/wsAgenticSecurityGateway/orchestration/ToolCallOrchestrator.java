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

    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public McpSchema.CallToolResult orchestrate(McpSyncServerExchange exchange,
                                                 String publicName,
                                                 Map<String, Object> arguments) {

        String correlationId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        Object rawJsonRpcId = exchange.transportContext().get("jsonRpcRequestId");
        String requestId = rawJsonRpcId != null ? String.valueOf(rawJsonRpcId) : null;

        String sessionId = resolveSessionId(exchange);
        String clientName = resolveClientName(exchange);

        agentRegistryService.recordRequest(sessionId);

        int seq = 0;

        UUID agentIdForAccess = agentRegistryService.getAgentIdForSession(sessionId);
        if (agentIdForAccess != null
                && !capabilityFilterService.isCapabilityAllowed(agentIdForAccess, publicName, "TOOL")) {
 log.warn("[{}] CAPABILITY ACCESS DENIED: session={}, tool={}, agent={}",
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
        if (agentIdForAccess != null && capabilityFilterService.hasProfiles(agentIdForAccess)) {
            auditService.auditCapabilityAccessGranted(sessionId, correlationId, clientName, publicName, "TOOL",
                    LocalDateTime.now(), ++seq);
        }

 log.info("Tool ORCHESTRATION START [{}]", correlationId);
        log.info("Tool: {}", publicName);
        log.info("Args: {}", arguments != null ? arguments.keySet() : "null");
        log.info("Session: {}", sessionId != null ? sessionId : "unknown");
        log.info("Agent: {}", clientName);
        log.info("JSON-RPC ID: {}", requestId != null ? requestId : "n/a");

        long orchestrationStart = System.currentTimeMillis();

        auditService.auditOrchestrationToolExtracted(correlationId, publicName, sessionId, requestId, clientName,
                LocalDateTime.now(), ++seq);

        long lookupStart = System.currentTimeMillis();
        Optional<CapabilityDescriptor> optDescriptor = registryService.lookupByPublicName(publicName);
        long lookupDuration = System.currentTimeMillis() - lookupStart;

        if (optDescriptor.isEmpty()) {
 log.error("[{}] Tool '{}' NOT FOUND in capability registry", correlationId, publicName);
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

 log.info("[{}] Registry resolved: {} → server='{}', original='{}'",
                correlationId, publicName, serverName, originalName);

        auditService.auditOrchestrationRegistryLookup(
                correlationId, sessionId, publicName, serverName, lookupDuration, requestId, clientName,
                LocalDateTime.now(), ++seq);

        try {
            PolicyEvaluationRequest pdpRequest = policyContextBuilder.buildForToolCall(
                    exchange, publicName, serverName, originalName,
                    arguments, correlationId, sessionId);

            auditService.auditPdpEvaluationRequested(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "toolCall", serverName, pdpRequest, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            PolicyEvaluationResult pdpResult = cedarPolicyEngine.evaluate(pdpRequest);

            auditService.auditPdpDecisionRendered(
                    correlationId, sessionId, pdpRequest.getAgentName(),
                    publicName, "toolCall", pdpResult.getDecision(),
                    serverName, pdpResult, pdpResult.getEvaluationDurationMs(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

            if (pdpResult.isDenied()) {
 log.warn("[{}] PDP DENIED: agent='{}', tool='{}', reason='{}'",
                        correlationId, pdpRequest.getAgentName(), publicName, pdpResult.getReason());
                return buildErrorResult(McpErrorCode.PDP_DENIED,
                        publicName, serverName,
                        "Policy violation: " + pdpResult.getReason());
            }

 log.info("[{}] PDP ALLOWED: agent='{}', tool='{}' ({}ms)",
                    correlationId, pdpRequest.getAgentName(), publicName,
                    pdpResult.getEvaluationDurationMs());

        } catch (Exception e) {
 log.error("[{}] PDP evaluation error (fail-open): {}", correlationId, e.getMessage());
        }

        if (!mcpSessionManager.isConnected(serverName)) {
 log.error("[{}] Server '{}' is not connected — rejecting immediately", correlationId, serverName);
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

        JsonNode argsAsJson;
        try {
            argsAsJson = objectMapper.valueToTree(
                    arguments != null ? arguments : Map.of());
        } catch (Exception e) {
 log.error("[{}] Failed to convert arguments: {}", correlationId, e.getMessage());
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

        String argsStr = argsAsJson.toString();
        inFlightRegistry.updateRequest(correlationId,
                argsStr.length() > 2000 ? argsStr.substring(0, 2000) + "..." : argsStr);

        boolean usingAgentToken = resolveAndApplyAgentToken(correlationId, serverName, exchange);
        inFlightRegistry.updateTokenMode(correlationId, usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

        long callStart = System.currentTimeMillis();
        try {
 log.info("[{}] Forwarding to enterprise server '{}' → tool '{}' (token: {})",
                    correlationId, serverName, originalName,
                    usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

            List<McpSchema.Content> contentList =
                    mcpClientService.callTool(correlationId, serverName, originalName, argsAsJson,
                            LocalDateTime.now(), ++seq);

            long callDuration = System.currentTimeMillis() - callStart;
            long totalDuration = System.currentTimeMillis() - orchestrationStart;

            enrichResponseData(correlationId, contentList);
            inFlightRegistry.updateTimings(correlationId, lookupDuration, callDuration);

            auditService.auditOrchestrationCallForwarded(
                    correlationId, sessionId, serverName, originalName, callDuration, requestId, clientName,
                    LocalDateTime.now(), ++seq);

            inFlightRegistry.complete(correlationId);

            agentRegistryService.updateLastActivity(sessionId);

 log.info("ORCHESTRATION COMPLETE [{}]", correlationId);
            log.info("Tool: {} → {}.{}", publicName, serverName, originalName);
            log.info("Response: {} content item(s)", contentList.size());
            log.info("Forward: {}ms | Total: {}ms", callDuration, totalDuration);
            log.info("Token: {}", usingAgentToken ? "agent-provided" : "config-based");
            log.info("In-flight: {} active", inFlightRegistry.getActiveCount());

            return new McpSchema.CallToolResult(contentList, false);

        } catch (IllegalArgumentException e) {
            long callDuration = System.currentTimeMillis() - callStart;
 log.error("[{}] Server '{}' unavailable: {}", correlationId, serverName, e.getMessage());

            inFlightRegistry.fail(correlationId, e.getMessage());
            auditService.auditOrchestrationError(
                    correlationId, sessionId, serverName, publicName,
                    McpErrorCode.SERVER_UNAVAILABLE, e.getMessage(),
                    requestId, clientName,
                    LocalDateTime.now(), ++seq);

            return buildErrorResult(McpErrorCode.SERVER_UNAVAILABLE,
                    publicName, serverName, e.getMessage());

        } catch (Exception e) {
            long callDuration = System.currentTimeMillis() - callStart;
 log.error("[{}] Orchestration failure for '{}' on '{}': {}",
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
            if (usingAgentToken) {
                HttpMcpTransport.clearRequestOverrideHeaders();
 log.debug("[{}] Cleared agent token override from ThreadLocal", correlationId);
            }
        }
    }

    private String resolveSessionId(McpSyncServerExchange exchange) {
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

    private static final Set<String> AUTH_TOKEN_KEYS = Set.of(
            "token", "accessToken", "access_token",
            "bearerToken", "bearer_token",
            "authToken", "auth_token",
            "authorization", "jwt"
    );

    private static final Set<String> API_KEY_KEYS = Set.of(
            "apiKey", "api_key"
    );

    private boolean resolveAndApplyAgentToken(String correlationId, String serverName,
                                               McpSyncServerExchange exchange) {
        try {
            if (sessionManager != null) {
                ClientSession session = sessionManager.getCurrentSession();
                if (session != null && session.hasTokens()) {
                    Map<String, String> agentTokens = session.getTokens();
                    Map<String, String> overrideHeaders = buildOverrideHeaders(agentTokens, serverName);

                    if (overrideHeaders != null && !overrideHeaders.isEmpty()) {
                        HttpMcpTransport.setRequestOverrideHeaders(overrideHeaders);
 log.info("[{}] Agent token override applied ({} headers, keys: {})",
                                correlationId, overrideHeaders.size(), agentTokens.keySet());
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve agent token from session: {}", e.getMessage());
        }

        return false;
    }

    private Map<String, String> buildOverrideHeaders(Map<String, String> agentTokens,
                                                       String serverName) {
        if (agentTokens == null || agentTokens.isEmpty()) {
            return null;
        }

        Map<String, String> overrideHeaders = new HashMap<>();

        for (Map.Entry<String, String> entry : agentTokens.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value == null || value.isBlank()) {
                continue;
            }

            if ("authorization".equalsIgnoreCase(key)) {
                overrideHeaders.put("Authorization", value);
            } else if (AUTH_TOKEN_KEYS.contains(key)) {
                if (value.toLowerCase().startsWith("bearer ")) {
                    overrideHeaders.put("Authorization", value);
                } else {
                    overrideHeaders.put("Authorization", "Bearer " + value);
                }
            } else if (API_KEY_KEYS.contains(key)) {
                overrideHeaders.put("X-API-Key", value);
            }
        }

        return overrideHeaders.isEmpty() ? null : overrideHeaders;
    }

    private McpSchema.CallToolResult buildErrorResult(McpErrorCode errorCode,
                                                       String publicName) {
        String message = String.format("[%d] %s: tool '%s'",
                errorCode.getCode(), errorCode.getMessage(), publicName);
        return new McpSchema.CallToolResult(message, true);
    }

    private McpSchema.CallToolResult buildErrorResult(McpErrorCode errorCode,
                                                       String publicName,
                                                       String serverName,
                                                       String detail) {
        String message = String.format("[%d] %s: tool '%s' on server '%s' — %s",
                errorCode.getCode(), errorCode.getMessage(),
                publicName, serverName, detail);
        return new McpSchema.CallToolResult(message, true);
    }

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

        UUID promptAgentId = agentRegistryService.getAgentIdForSession(sessionId);
        if (promptAgentId != null
                && !capabilityFilterService.isCapabilityAllowed(promptAgentId, publicName, "PROMPT")) {
 log.warn("[{}] CAPABILITY ACCESS DENIED: session={}, prompt={}, agent={}",
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
        if (promptAgentId != null && capabilityFilterService.hasProfiles(promptAgentId)) {
            auditService.auditCapabilityAccessGranted(sessionId, correlationId, clientName, publicName, "PROMPT",
                    LocalDateTime.now(), ++seq);
        }

 log.info("PROMPT ORCHESTRATION START [{}]", correlationId);
        log.info("Prompt: {}", publicName);
        log.info("Args: {}", arguments != null ? arguments.keySet() : "null");
        log.info("Session: {}", sessionId != null ? sessionId : "unknown");
        log.info("Agent: {}", clientName);
        log.info("JSON-RPC ID: {}", requestId != null ? requestId : "n/a");

        long orchestrationStart = System.currentTimeMillis();

        auditService.auditOrchestrationToolExtracted(correlationId, publicName, sessionId, requestId, clientName,
                LocalDateTime.now(), ++seq);

        long lookupStart = System.currentTimeMillis();
        Optional<CapabilityDescriptor> optDescriptor = registryService.lookupByPublicName(publicName);
        long lookupDuration = System.currentTimeMillis() - lookupStart;

        if (optDescriptor.isEmpty()) {
 log.error("[{}] Prompt '{}' NOT FOUND in capability registry", correlationId, publicName);
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

 log.info("[{}] Registry resolved: {} → server='{}', original='{}'",
                correlationId, publicName, serverName, originalName);

        auditService.auditOrchestrationRegistryLookup(
                correlationId, sessionId, publicName, serverName, lookupDuration, requestId, clientName,
                LocalDateTime.now(), ++seq);

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
 log.warn("[{}] PDP DENIED: agent='{}', prompt='{}', reason='{}'",
                        correlationId, pdpRequest.getAgentName(), publicName, pdpResult.getReason());
                throw new RuntimeException(String.format("[%d] Policy violation: %s",
                        McpErrorCode.PDP_DENIED.getCode(), pdpResult.getReason()));
            }

 log.info("[{}] PDP ALLOWED: agent='{}', prompt='{}' ({}ms)",
                    correlationId, pdpRequest.getAgentName(), publicName,
                    pdpResult.getEvaluationDurationMs());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
 log.error("[{}] PDP evaluation error (fail-open): {}", correlationId, e.getMessage());
        }

        if (!mcpSessionManager.isConnected(serverName)) {
 log.error("[{}] Server '{}' is not connected — rejecting prompt immediately", correlationId, serverName);
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

        String pClientSessionId = null;
        try {
            McpSession pClientSession = mcpSessionManager.getSession(serverName);
            pClientSessionId = pClientSession.getSessionId();
        } catch (Exception ignored) {}

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

        try {
            String pArgsStr = objectMapper.writeValueAsString(arguments != null ? arguments : Map.of());
            inFlightRegistry.updateRequest(correlationId,
                    pArgsStr.length() > 2000 ? pArgsStr.substring(0, 2000) + "..." : pArgsStr);
        } catch (Exception ignored) {}

        boolean usingAgentToken = resolveAndApplyAgentToken(correlationId, serverName, exchange);
        inFlightRegistry.updateTokenMode(correlationId, usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

        long callStart = System.currentTimeMillis();
        try {
 log.info("[{}] Forwarding getPrompt to '{}' → prompt '{}' (token: {})",
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

            agentRegistryService.updateLastActivity(sessionId);

            int messageCount = result.messages() != null ? result.messages().size() : 0;
 log.info("PROMPT ORCHESTRATION COMPLETE [{}]", correlationId);
            log.info("Prompt: {} → {}.{}", publicName, serverName, originalName);
            log.info("Response: {} message(s)", messageCount);
            log.info("Forward: {}ms | Total: {}ms", callDuration, totalDuration);
            log.info("In-flight: {} active", inFlightRegistry.getActiveCount());

            return result;

        } catch (IllegalArgumentException e) {
 log.error("[{}] Server '{}' unavailable: {}", correlationId, serverName, e.getMessage());
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
 log.error("[{}] Prompt orchestration failure for '{}' on '{}': {}",
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
 log.debug("[{}] Cleared agent token override from ThreadLocal", correlationId);
            }
        }
    }

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

        UUID resourceAgentId = agentRegistryService.getAgentIdForSession(sessionId);
        if (resourceAgentId != null
                && !capabilityFilterService.isCapabilityAllowed(resourceAgentId, publicName, "RESOURCE")) {
 log.warn("[{}] CAPABILITY ACCESS DENIED: session={}, resource={}, agent={}",
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
        if (resourceAgentId != null && capabilityFilterService.hasProfiles(resourceAgentId)) {
            auditService.auditCapabilityAccessGranted(sessionId, correlationId, clientName, publicName, "RESOURCE",
                    LocalDateTime.now(), ++seq);
        }

 log.info("RESOURCE ORCHESTRATION START [{}]", correlationId);
        log.info("Resource: {}", publicName);
        log.info("URI: {}", resourceUri);
        log.info("Session: {}", sessionId != null ? sessionId : "unknown");
        log.info("Agent: {}", clientName);
        log.info("JSON-RPC ID: {}", requestId != null ? requestId : "n/a");

        long orchestrationStart = System.currentTimeMillis();

        auditService.auditOrchestrationToolExtracted(correlationId, publicName, sessionId, requestId, clientName,
                LocalDateTime.now(), ++seq);

        long lookupStart = System.currentTimeMillis();
        Optional<CapabilityDescriptor> optDescriptor = registryService.lookupByPublicName(publicName);
        long lookupDuration = System.currentTimeMillis() - lookupStart;

        if (optDescriptor.isEmpty()) {
 log.error("[{}] Resource '{}' NOT FOUND in capability registry", correlationId, publicName);
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

 log.info("[{}] Registry resolved: {} → server='{}', uri='{}'",
                correlationId, publicName, serverName, originalUri);

        auditService.auditOrchestrationRegistryLookup(
                correlationId, sessionId, publicName, serverName, lookupDuration, requestId, clientName,
                LocalDateTime.now(), ++seq);

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
 log.warn("[{}] PDP DENIED: agent='{}', resource='{}', reason='{}'",
                        correlationId, pdpRequest.getAgentName(), publicName, pdpResult.getReason());
                throw new RuntimeException(String.format("[%d] Policy violation: %s",
                        McpErrorCode.PDP_DENIED.getCode(), pdpResult.getReason()));
            }

 log.info("[{}] PDP ALLOWED: agent='{}', resource='{}' ({}ms)",
                    correlationId, pdpRequest.getAgentName(), publicName,
                    pdpResult.getEvaluationDurationMs());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
 log.error("[{}] PDP evaluation error (fail-open): {}", correlationId, e.getMessage());
        }

        if (!mcpSessionManager.isConnected(serverName)) {
 log.error("[{}] Server '{}' is not connected — rejecting resource read immediately", correlationId, serverName);
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

        String rClientSessionId = null;
        try {
            McpSession rClientSession = mcpSessionManager.getSession(serverName);
            rClientSessionId = rClientSession.getSessionId();
        } catch (Exception ignored) {}

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

        inFlightRegistry.updateRequest(correlationId, resourceUri != null ? resourceUri : "");

        boolean usingAgentToken = resolveAndApplyAgentToken(correlationId, serverName, exchange);
        inFlightRegistry.updateTokenMode(correlationId, usingAgentToken ? "AGENT-PROVIDED" : "CONFIG");

        long callStart = System.currentTimeMillis();
        try {
 log.info("[{}] Forwarding readResource to '{}' → uri '{}' (token: {})",
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

            agentRegistryService.updateLastActivity(sessionId);

 log.info("RESOURCE ORCHESTRATION COMPLETE [{}]", correlationId);
            log.info("Resource: {} → {}", publicName, originalUri);
            log.info("Response: {} content item(s)", contents.size());
            log.info("Forward: {}ms | Total: {}ms", callDuration, totalDuration);
            log.info("In-flight: {} active", inFlightRegistry.getActiveCount());

            return new McpSchema.ReadResourceResult(contents);

        } catch (IllegalArgumentException e) {
 log.error("[{}] Server '{}' unavailable: {}", correlationId, serverName, e.getMessage());
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
 log.error("[{}] Resource orchestration failure for '{}' on '{}': {}",
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
 log.debug("[{}] Cleared agent token override from ThreadLocal", correlationId);
            }
        }
    }
}
