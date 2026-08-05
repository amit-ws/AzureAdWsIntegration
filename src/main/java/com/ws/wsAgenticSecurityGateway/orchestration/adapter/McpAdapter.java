package com.ws.wsAgenticSecurityGateway.orchestration.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.orchestration.model.CapabilityResult;
import com.ws.wsAgenticSecurityGateway.orchestration.model.Hop;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config.HttpMcpTransport;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config.McpSession;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config.McpSessionManager;
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

    /** Southbound session manager (gateway → downstream MCP servers) — source of the connection status
     *  and downstream session id the spine used to read directly. */
    private final McpSessionManager mcpSessionManager;

    /** Northbound agent session (source of agent-provided tokens). Wired at startup, mirrors
     *  the previous {@code ToolCallOrchestrator.setSessionManager} pattern. */
    private volatile SessionManager sessionManager;

    public McpAdapter(McpClientService mcpClientService, McpSessionManager mcpSessionManager) {
        this.mcpClientService = mcpClientService;
        this.mcpSessionManager = mcpSessionManager;
    }

    @Override
    public String protocol() {
        return "MCP";
    }

    @Override
    public boolean isTargetConnected(String targetName) {
        return mcpSessionManager != null && mcpSessionManager.isConnected(targetName);
    }

    @Override
    public String downstreamSessionId(String targetName) {
        try {
            McpSession session = mcpSessionManager != null ? mcpSessionManager.getSession(targetName) : null;
            return session != null ? session.getSessionId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public CapabilityResult callTool(Hop hop, JsonNode argsJson, String correlationId,
                                     LocalDateTime firedAt, int eventSequence) {
        List<McpSchema.Content> content = mcpClientService.callTool(correlationId, hop.serverName(),
                hop.originalName(), argsJson, firedAt, eventSequence);
        return CapabilityResult.ok(content, summarize(content),
                content != null ? content.size() : 0, typesOf(content));
    }

    @Override
    public CapabilityResult invokeSkill(Hop hop, JsonNode argsJson, String correlationId,
                                        LocalDateTime firedAt, int eventSequence) {
        throw new UnsupportedOperationException(
                "MCP adapter does not support agent SKILL invocation — skills are an A2A capability");
    }

    @Override
    public CapabilityResult getPrompt(Hop hop) {
        McpSchema.GetPromptResult result =
                mcpClientService.getPrompt(hop.serverName(), hop.originalName(), hop.arguments());
        int messageCount = result != null && result.messages() != null ? result.messages().size() : 0;
        return CapabilityResult.ok(result, summarizePrompt(result), messageCount, "PROMPT");
    }

    /** The prompt description, blank-checked and truncated — mirrors the spine's former {@code enrichPromptResponseData}. */
    private static String summarizePrompt(McpSchema.GetPromptResult result) {
        if (result == null || result.description() == null || result.description().isBlank()) {
            return "";
        }
        String description = result.description();
        return description.length() > 2000 ? description.substring(0, 2000) + "..." : description;
    }

    @Override
    public CapabilityResult readResource(Hop hop, String correlationId,
                                         LocalDateTime firedAt, int eventSequence) {
        List<McpSchema.ResourceContents> contents = mcpClientService.readResource(correlationId,
                hop.serverName(), hop.originalName(), firedAt, eventSequence);
        return CapabilityResult.ok(contents, summarizeResource(contents),
                contents != null ? contents.size() : 0, typesOfResource(contents));
    }

    /**
     * The first text content, truncated — the neutral audit / in-flight summary the spine records.
     * Moved verbatim from the spine's former {@code enrichResponseData}: reading MCP content is the
     * adapter's job, so the spine only ever sees the neutral summary string.
     */
    private static String summarize(List<McpSchema.Content> content) {
        if (content == null) {
            return "";
        }
        for (McpSchema.Content c : content) {
            if (c instanceof McpSchema.TextContent t) {
                String text = t.text() != null ? t.text() : "";
                return text.length() > 2000 ? text.substring(0, 2000) + "..." : text;
            }
        }
        return "";
    }

    /** Comma-joined content kinds (e.g. {@code "TEXT,IMAGE"}) for the in-flight display. */
    private static String typesOf(List<McpSchema.Content> content) {
        if (content == null) {
            return "";
        }
        StringBuilder types = new StringBuilder();
        for (McpSchema.Content c : content) {
            String kind = c instanceof McpSchema.TextContent ? "TEXT"
                    : c instanceof McpSchema.ImageContent ? "IMAGE"
                    : c instanceof McpSchema.AudioContent ? "AUDIO"
                    : c instanceof McpSchema.EmbeddedResource ? "RESOURCE"
                    : null;
            if (kind != null) {
                types.append(types.length() > 0 ? "," : "").append(kind);
            }
        }
        return types.toString();
    }

    /** The first text resource content, truncated — mirrors the spine's former {@code enrichResourceResponseData}. */
    private static String summarizeResource(List<McpSchema.ResourceContents> contents) {
        if (contents == null) {
            return "";
        }
        for (McpSchema.ResourceContents rc : contents) {
            if (rc instanceof McpSchema.TextResourceContents t) {
                String text = t.text() != null ? t.text() : "";
                return text.length() > 2000 ? text.substring(0, 2000) + "..." : text;
            }
        }
        return "";
    }

    /** Comma-joined resource content kinds (e.g. {@code "TEXT,BLOB"}) for the in-flight display. */
    private static String typesOfResource(List<McpSchema.ResourceContents> contents) {
        if (contents == null) {
            return "";
        }
        StringBuilder types = new StringBuilder();
        for (McpSchema.ResourceContents rc : contents) {
            String kind = rc instanceof McpSchema.TextResourceContents ? "TEXT"
                    : rc instanceof McpSchema.BlobResourceContents ? "BLOB"
                    : null;
            if (kind != null) {
                types.append(types.length() > 0 ? "," : "").append(kind);
            }
        }
        return types.toString();
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
