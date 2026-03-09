package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationRequest;
import com.ws.wsAgenticSecurityGateway.wsServer.session.ClientSession;
import com.ws.wsAgenticSecurityGateway.wsServer.session.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context Fetcher — builds the structured {@link PolicyEvaluationRequest}
 * from the current MCP request context for Cedar policy evaluation.
 *
 * <p>Gathers metadata from:
 * <ul>
 *   <li>MCP SDK exchange (agent identity, session, transport context)</li>
 *   <li>Agent Registry DB (approval status, total requests, etc.)</li>
 *   <li>Capability Registry (tool/resource/prompt metadata)</li>
 *   <li>System environment (time, day of week, business hours)</li>
 * </ul>
 */
@Component
@Slf4j
public class PolicyContextBuilder {

    private final AgentRegistryService agentRegistryService;

    /** Set at runtime from McpServerApplication — same pattern as ToolCallOrchestrator. */
    private volatile SessionManager sessionManager;

    public PolicyContextBuilder(AgentRegistryService agentRegistryService) {
        this.agentRegistryService = agentRegistryService;
    }

    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Build a policy evaluation request for a tool call.
     *
     * @param exchange      MCP SDK exchange
     * @param publicName    the namespaced tool name
     * @param serverName    the enterprise MCP server name
     * @param originalName  the tool's original name on the server
     * @param arguments     tool call arguments
     * @param correlationId orchestration correlation ID
     * @param sessionId     agent session ID
     * @return structured evaluation request for Cedar
     */
    public PolicyEvaluationRequest buildForToolCall(
            McpSyncServerExchange exchange,
            String publicName,
            String serverName,
            String originalName,
            Map<String, Object> arguments,
            String correlationId,
            String sessionId) {

        return buildRequest(exchange, "toolCall", publicName, serverName,
                originalName, "TOOL", arguments, correlationId, sessionId);
    }

    /**
     * Build a policy evaluation request for a prompt get.
     */
    public PolicyEvaluationRequest buildForPromptGet(
            McpSyncServerExchange exchange,
            String publicName,
            String serverName,
            String originalName,
            String correlationId,
            String sessionId) {

        return buildRequest(exchange, "promptGet", publicName, serverName,
                originalName, "PROMPT", null, correlationId, sessionId);
    }

    /**
     * Build a policy evaluation request for a resource read.
     */
    public PolicyEvaluationRequest buildForResourceRead(
            McpSyncServerExchange exchange,
            String publicName,
            String serverName,
            String originalName,
            String correlationId,
            String sessionId) {

        return buildRequest(exchange, "resourceRead", publicName, serverName,
                originalName, "RESOURCE", null, correlationId, sessionId);
    }

    // ════════════════════════════════════════════════════════════════════
    //  PRIVATE
    // ════════════════════════════════════════════════════════════════════

    private PolicyEvaluationRequest buildRequest(
            McpSyncServerExchange exchange,
            String action,
            String publicName,
            String serverName,
            String originalName,
            String resourceType,
            Map<String, Object> arguments,
            String correlationId,
            String sessionId) {

        // ── Agent identity ────────────────────────────────────────────
        String agentName = "unknown";
        String agentVersion = null;
        try {
            if (exchange != null && exchange.getClientInfo() != null) {
                McpSchema.Implementation ci = exchange.getClientInfo();
                agentName = ci.name() != null ? ci.name() : "unknown";
                agentVersion = ci.version();
            }
        } catch (Exception e) {
            log.debug("Could not extract agent info from exchange: {}", e.getMessage());
        }

        // ── Agent approval status from DB ─────────────────────────────
        String approvalStatus = "UNKNOWN";
        try {
            List<GatewayAgentEntity> agents = agentRegistryService.findAgentsByName(agentName);
            if (agents != null && !agents.isEmpty()) {
                approvalStatus = agents.get(0).getApprovalStatus();
            }
        } catch (Exception e) {
            log.debug("Could not fetch agent approval status: {}", e.getMessage());
        }

        // ── Source IP from transport context ──────────────────────────
        String sourceIp = null;
        try {
            if (exchange != null) {
                Object ip = exchange.transportContext().get("clientIp");
                if (ip != null) {
                    sourceIp = String.valueOf(ip);
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract source IP from transport context: {}", e.getMessage());
        }

        // ── Build request ─────────────────────────────────────────────
        return PolicyEvaluationRequest.builder()
                .agentName(agentName)
                .agentVersion(agentVersion)
                .agentApprovalStatus(approvalStatus)
                .agentSessionId(sessionId)
                .action(action)
                .resourceName(publicName)
                .serverName(serverName)
                .originalName(originalName)
                .resourceType(resourceType)
                .arguments(arguments != null ? sanitizeArguments(arguments) : null)
                .correlationId(correlationId)
                .sourceIp(sourceIp)
                .build();
    }

    /**
     * Sanitize arguments — truncate large values, remove binary data.
     * We include arguments in policy evaluation but not raw bytes.
     */
    private Map<String, Object> sanitizeArguments(Map<String, Object> args) {
        Map<String, Object> sanitized = new HashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.length() > 2000) {
                sanitized.put(entry.getKey(), s.substring(0, 2000) + "...[truncated]");
            } else if (value instanceof byte[]) {
                sanitized.put(entry.getKey(), "[binary data]");
            } else {
                sanitized.put(entry.getKey(), value);
            }
        }
        return sanitized;
    }
}
