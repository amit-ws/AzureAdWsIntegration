package com.ws.wsAgenticSecurityGateway.wsServer.transport;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Extracts authentication and agent identity context from HTTP request headers
 * into {@link McpTransportContext} for the MCP SDK's Reactor pipeline.
 *
 * <p>The extracted context is available in tool/prompt/resource handlers via
 * {@code exchange.transportContext().get("key")}, enabling:
 * <ul>
 *   <li>Agent token forwarding to southbound MCP servers</li>
 *   <li>Agent identification for audit logging</li>
 *   <li>Correlation ID propagation across the request chain</li>
 * </ul>
 *
 * <p>This extractor is used by {@code HttpServletStreamableServerTransportProvider}
 * in HTTP mode. In stdio mode, context is injected differently (via JSON-RPC id
 * in {@link ServerTransportProvider}).
 */
@Component
@Slf4j
public class McpGatewayContextExtractor implements McpTransportContextExtractor<HttpServletRequest> {

    @Override
    public McpTransportContext extract(HttpServletRequest request) {
        Map<String, Object> ctx = new HashMap<>();

        // ── Auth token (for agent token forwarding to southbound servers) ────
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isBlank()) {
            ctx.put("authorization", auth);
        }

        // ── Agent identity headers ──────────────────────────────────────────
        String agentName = request.getHeader("X-Agent-Name");
        if (agentName != null && !agentName.isBlank()) {
            ctx.put("agentName", agentName);
        }

        // ── Correlation ID (if client sends one for distributed tracing) ────
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId != null && !correlationId.isBlank()) {
            ctx.put("correlationId", correlationId);
        }

        // ── Client IP for audit ─────────────────────────────────────────────
        String clientIp = request.getRemoteAddr();
        if (clientIp != null) {
            ctx.put("clientIp", clientIp);
        }

        if (!ctx.isEmpty()) {
            log.debug("Extracted MCP transport context: keys={}", ctx.keySet());
        }

        return ctx.isEmpty() ? McpTransportContext.EMPTY : McpTransportContext.create(ctx);
    }
}
