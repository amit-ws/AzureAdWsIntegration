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

@Configuration
@ConditionalOnProperty(name = "ws.gateway.transport", havingValue = "http", matchIfMissing = true)
@Slf4j
public class HttpTransportConfig {

    @Value("${ws.gateway.auth.mode:none}")
    private String authMode;

    @Bean
    public HttpServletStreamableServerTransportProvider mcpStreamableTransport(
            ObjectMapper objectMapper,
            McpGatewayContextExtractor contextExtractor) {

        ObjectMapper mcpObjectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .objectMapper(mcpObjectMapper)
                        .mcpEndpoint("/mcp")
                        .contextExtractor(contextExtractor)
                        .build();

        log.info("MCP HTTP Streamable transport provider created (endpoint: /mcp)");
        return transport;
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
            HttpServletStreamableServerTransportProvider transport) {

        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, "/mcp/*");
        registration.setName("mcpStreamableServlet");
        registration.setLoadOnStartup(1);

        log.info("MCP servlet registered at /mcp/*");
        return registration;
    }

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

        log.info("MCP OAuth2 filter registered for /mcp/* (JWT claim extraction enabled)");
        return registration;
    }

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

        log.info("MCP audit filter registered for /mcp/*");
        return registration;
    }
}
