package com.ws.wsAgenticSecurityGateway.protocol.a2a;

import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.authConfig.service.AuthConfigService;
import com.ws.wsAgenticSecurityGateway.security.GatewayOAuth2Filter;
import com.ws.wsAgenticSecurityGateway.security.ProtocolRouteRegistry;
import com.ws.wsAgenticSecurityGateway.security.TokenClassificationService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the A2A data plane into the gateway's security posture — mirroring how the MCP transport wires
 * {@code /mcp} and {@code /stateless/mcp}:
 * <ol>
 *   <li>Registers {@code /a2a} as a protected prefix with the {@link ProtocolRouteRegistry} so the
 *       resource-server filter chain authenticates it (the Agent Card at {@code /.well-known/agent-card.json}
 *       stays public for discovery).</li>
 *   <li>Runs {@link GatewayOAuth2Filter} for {@code /a2a} so an authenticated call carries a full identity —
 *       agent principal, subject, roles, token type, raw claims, tenant — into the governance spine. This is
 *       the same claim-extraction the MCP data plane uses; without it {@code /a2a} validates the JWT but never
 *       stamps the {@code jwt.*} request attributes the spine, act_chain, and PDP read, so every A2A call would
 *       be governed as an anonymous {@code unknown} principal.</li>
 * </ol>
 */
@Configuration
@Slf4j
public class A2aTransportConfig {

    /** The A2A JSON-RPC data-plane prefix; agent skill invocations arrive here and are authenticated. */
    public static final String A2A_DATA_PLANE_PREFIX = "/a2a";

    private final ProtocolRouteRegistry protocolRoutes;

    public A2aTransportConfig(ProtocolRouteRegistry protocolRoutes) {
        this.protocolRoutes = protocolRoutes;
    }

    @PostConstruct
    void registerRoutes() {
        protocolRoutes.registerProtectedPrefix(A2A_DATA_PLANE_PREFIX);
        log.info("A2A data-plane route registered as protected: {} "
                + "(Agent Card at /.well-known/agent-card.json stays public for discovery)",
                A2A_DATA_PLANE_PREFIX);
    }

    /**
     * Extract JWT claims into request attributes for {@code /a2a}, the same way the MCP data plane does. Runs
     * nested inside the Spring Security chain (order after {@code springSecurityFilterChain}), so the validated
     * JWT is already in the {@code SecurityContext} when the filter reads it. No-ops in {@code auth-mode=none}.
     */
    @Bean
    public FilterRegistrationBean<GatewayOAuth2Filter> a2aOAuth2FilterRegistration(
            GatewayAuditService auditService,
            TokenClassificationService tokenClassificationService,
            AuthConfigService authConfigService) {
        FilterRegistrationBean<GatewayOAuth2Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new GatewayOAuth2Filter(auditService, tokenClassificationService, authConfigService));
        registration.addUrlPatterns(A2A_DATA_PLANE_PREFIX);
        registration.setOrder(1);
        registration.setName("a2aOAuth2Filter");

        log.info("A2A OAuth2 filter registered for {} (JWT claim extraction enabled)", A2A_DATA_PLANE_PREFIX);
        return registration;
    }
}
