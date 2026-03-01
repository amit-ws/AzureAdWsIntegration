package com.ws.wsAgenticSecurityGateway.wsServer.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.Filter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HTTP Streamable transport configuration for the WS MCP Gateway.
 *
 * <p>Active when {@code ws.gateway.transport=http} (the default).
 * Registers the MCP SDK's {@link HttpServletStreamableServerTransportProvider}
 * as a servlet at {@code /mcp/*}, along with an auth filter.
 *
 * <p>This enables AI agents (Claude Desktop, OpenClaw, etc.) to connect to
 * the gateway over HTTP instead of stdio, decoupling agent lifecycle from
 * the gateway process and enabling:
 * <ul>
 *   <li>Persistent southbound MCP server connections across agent reconnects</li>
 *   <li>Multiple concurrent agent sessions</li>
 *   <li>Deployment as a standalone service (Docker, K8s, systemd)</li>
 * </ul>
 *
 * <p>In HTTP mode, the SDK manages agent sessions internally via
 * {@code ConcurrentHashMap<String, McpStreamableServerSession>}.
 * Tool/prompt/resource handlers receive the same {@code McpSyncServerExchange}
 * interface as stdio mode.
 */
@Configuration
@ConditionalOnProperty(name = "ws.gateway.transport", havingValue = "http", matchIfMissing = true)
@Slf4j
public class HttpTransportConfig {

    @Value("${ws.gateway.auth.enabled:false}")
    private boolean authEnabled;

    @Value("${ws.gateway.auth.api-key:}")
    private String apiKey;

    /**
     * Creates the SDK's HTTP Streamable transport provider.
     * This is a servlet that handles GET (SSE listener), POST (messages),
     * and DELETE (session termination) at the {@code /mcp} endpoint.
     */
    @Bean
    public HttpServletStreamableServerTransportProvider mcpStreamableTransport(
            ObjectMapper objectMapper,
            McpGatewayContextExtractor contextExtractor) {

        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .objectMapper(objectMapper)
                        .mcpEndpoint("/mcp")
                        .contextExtractor(contextExtractor)
                        .build();

        log.info("📡 MCP HTTP Streamable transport provider created (endpoint: /mcp)");
        return transport;
    }

    /**
     * Registers the MCP transport as a servlet at {@code /mcp/*}.
     */
    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
            HttpServletStreamableServerTransportProvider transport) {

        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, "/mcp/*");
        registration.setName("mcpStreamableServlet");
        registration.setLoadOnStartup(1);

        log.info("📡 MCP servlet registered at /mcp/*");
        return registration;
    }

    /**
     * Auth filter for MCP requests. Only active when {@code ws.gateway.auth.enabled=true}.
     *
     * <p><strong>Design rationale:</strong> In production enterprise environments,
     * agents and MCP servers already authenticate using existing strategies (OAuth2,
     * JWT, SAML, mTLS). The gateway should be transparent — passing through the
     * agent's existing credentials to southbound servers via the context extractor
     * and token forwarding. A gateway-specific API key is only for standalone
     * deployments without an existing auth ecosystem.
     *
     * <p>Next upgrade: integrate with enterprise auth (validate existing JWT/SAML
     * tokens, extract agent identity from them, without requiring a separate key).
     */
    @Bean
    @ConditionalOnProperty(name = "ws.gateway.auth.enabled", havingValue = "true")
    public FilterRegistrationBean<Filter> mcpAuthFilterRegistration() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new GatewayMcpAuthFilter(apiKey));
        registration.addUrlPatterns("/mcp/*");
        registration.setOrder(1);
        registration.setName("mcpAuthFilter");

        log.info("🔐 MCP auth filter registered for /mcp/* (API key validation enabled)");
        return registration;
    }

    /**
     * Full MCP audit filter — intercepts ALL MCP requests to provide audit parity
     * with stdio mode. Handles: initialize (agent registration + probe filtering),
     * tools/list, prompts/list, resources/list (Area 1 audit), DELETE (disconnect audit).
     *
     * <p>Ordered after auth (order=2) so unauthorized requests are rejected first.
     */
    @Bean
    public FilterRegistrationBean<HttpMcpAuditFilter> mcpAuditFilterRegistration(
            AgentRegistryService agentRegistryService,
            McpAuditService auditService,
            CapabilityRegistryService registryService,
            ObjectMapper objectMapper) {

        FilterRegistrationBean<HttpMcpAuditFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new HttpMcpAuditFilter(
                agentRegistryService, auditService, registryService, objectMapper));
        registration.addUrlPatterns("/mcp/*");
        registration.setOrder(2);
        registration.setName("mcpAuditFilter");

        log.info("📡 MCP audit filter registered for /mcp/*");
        return registration;
    }
}
