package com.ws.wsAgenticSecurityGateway.orchestration.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.orchestration.model.Hop;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config.HttpMcpTransport;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.service.McpClientService;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.session.ClientSession;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.session.SessionManager;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP implementation of {@link ProtocolAdapter}: performs the downstream MCP call via
 * {@link McpClientService} and brokers the per-target downstream credentials (agent-provided
 * tokens injected onto the outbound request via {@link HttpMcpTransport}'s ThreadLocal).
 *
 * <p>The credential logic here was extracted verbatim from {@code ToolCallOrchestrator}
 * (the {@code resolveAndApplyAgentToken} / {@code buildOverrideHeaders} helpers).
 */
@Service
@Slf4j
public class McpAdapter implements ProtocolAdapter {

    private final McpClientService mcpClientService;

    /** Northbound agent session (source of agent-provided tokens). Wired at startup, mirrors
     *  the previous {@code ToolCallOrchestrator.setSessionManager} pattern. */
    private volatile SessionManager sessionManager;

    public McpAdapter(McpClientService mcpClientService) {
        this.mcpClientService = mcpClientService;
    }

    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public List<McpSchema.Content> callTool(Hop hop, JsonNode argsJson, String correlationId,
                                            LocalDateTime firedAt, int eventSequence) {
        return mcpClientService.callTool(correlationId, hop.serverName(), hop.originalName(),
                argsJson, firedAt, eventSequence);
    }

    @Override
    public McpSchema.GetPromptResult getPrompt(Hop hop) {
        return mcpClientService.getPrompt(hop.serverName(), hop.originalName(), hop.arguments());
    }

    @Override
    public List<McpSchema.ResourceContents> readResource(Hop hop, String correlationId,
                                                         LocalDateTime firedAt, int eventSequence) {
        return mcpClientService.readResource(correlationId, hop.serverName(), hop.originalName(),
                firedAt, eventSequence);
    }

    // ---------------------------------------------------------------------
    // Downstream credential brokering (moved from ToolCallOrchestrator)
    // ---------------------------------------------------------------------

    private static final Set<String> AUTH_TOKEN_KEYS = Set.of(
            "token", "accessToken", "access_token",
            "bearerToken", "bearer_token",
            "authToken", "auth_token",
            "authorization", "jwt"
    );

    private static final Set<String> API_KEY_KEYS = Set.of(
            "apiKey", "api_key"
    );

    @Override
    public boolean applyCredentials(Hop hop, String correlationId) {
        try {
            if (sessionManager != null) {
                ClientSession session = sessionManager.getCurrentSession();
                if (session != null && session.hasTokens()) {
                    Map<String, String> agentTokens = session.getTokens();
                    Map<String, String> overrideHeaders = buildOverrideHeaders(agentTokens);

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

    @Override
    public void clearCredentials(String correlationId) {
        HttpMcpTransport.clearRequestOverrideHeaders();
        log.debug("[{}] Cleared agent token override from ThreadLocal", correlationId);
    }

    private Map<String, String> buildOverrideHeaders(Map<String, String> agentTokens) {
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
}
