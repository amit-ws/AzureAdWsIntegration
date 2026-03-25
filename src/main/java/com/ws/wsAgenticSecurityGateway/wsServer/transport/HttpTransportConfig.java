package com.ws.wsAgenticSecurityGateway.wsServer.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentCapabilityFilterService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.authConfig.service.AuthConfigService;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
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
 *   <li>Persistent WS Client MCP server connections across agent reconnects</li>
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

    @Value("${ws.gateway.auth.mode:none}")
    private String authMode;

    /**
     * Creates the SDK's HTTP Streamable transport provider.
     * This is a servlet that handles GET (SSE listener), POST (messages),
     * and DELETE (session termination) at the {@code /mcp} endpoint.
     */
    @Bean
    public HttpServletStreamableServerTransportProvider mcpStreamableTransport(
            ObjectMapper objectMapper,
            McpGatewayContextExtractor contextExtractor) {

        // Use a tolerant mapper for MCP wire payloads so newer/agent-specific
        // capability fields do not break initialize deserialization.
        ObjectMapper mcpObjectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .objectMapper(mcpObjectMapper)
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
     * OAuth2 JWT claim extraction filter for MCP requests.
     * Only active when {@code ws.gateway.auth.mode=oauth2}.
     *
     * <p>Runs AFTER Spring Security's JWT validation (which rejects invalid tokens
     * with 401 before our code runs). This filter extracts JWT claims from the
     * already-validated token and stores them as request attributes for downstream
     * filters (HttpMcpAuditFilter) and the context extractor.
     */
    @Bean
    public FilterRegistrationBean<GatewayOAuth2Filter> mcpOAuth2FilterRegistration(
            McpAuditService auditService,
            TokenClassificationService tokenClassificationService,
            AuthConfigService authConfigService) {
        FilterRegistrationBean<GatewayOAuth2Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new GatewayOAuth2Filter(auditService, tokenClassificationService, authConfigService));
        registration.addUrlPatterns("/mcp/*");
        registration.setOrder(1);
        registration.setName("mcpOAuth2Filter");

        log.info("🔐 MCP OAuth2 filter registered for /mcp/* (JWT claim extraction enabled)");
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
            AgentCapabilityFilterService capabilityFilterService,
            McpAuditService auditService,
            CapabilityRegistryService registryService,
            ObjectMapper objectMapper,
            TokenClassificationService tokenClassificationService) {

        FilterRegistrationBean<HttpMcpAuditFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new HttpMcpAuditFilter(
                agentRegistryService, capabilityFilterService, auditService, registryService,
                objectMapper, tokenClassificationService));
        registration.addUrlPatterns("/mcp/*");
        registration.setOrder(2);
        registration.setName("mcpAuditFilter");

        log.info("📡 MCP audit filter registered for /mcp/*");
        return registration;
    }
}
