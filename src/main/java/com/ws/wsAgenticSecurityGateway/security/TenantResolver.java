package com.ws.wsAgenticSecurityGateway.security;

import com.ws.wsAgenticSecurityGateway.authConfig.repository.GatewayAuthConfigRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Resolves the WhiteSwan tenant for an authenticated request, reusing the gateway's existing mechanism:
 * an explicit {@code X-WS-Tenant} header wins; otherwise the validated token's issuer is looked up in the
 * gateway's auth-config table ({@code issuerUri → wsTenantName}); failing both, {@code "default"}.
 *
 * <p>This is the same resolution the MCP data plane performs on {@code initialize}
 * ({@code HttpMcpAuditFilter}); factored out here so the session-less A2A data plane resolves tenants
 * identically — from the token, backed by the DB — rather than from a per-session cache it never populates.
 */
@Component
@Slf4j
public class TenantResolver {

    public static final String DEFAULT_TENANT = "default";
    private static final String TENANT_HEADER = "X-WS-Tenant";

    private final GatewayAuthConfigRepository authConfigRepository;

    public TenantResolver(GatewayAuthConfigRepository authConfigRepository) {
        this.authConfigRepository = authConfigRepository;
    }

    /** Resolve the tenant for this request: header → token issuer (via DB) → {@code "default"}. */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return DEFAULT_TENANT;
        }
        String header = request.getHeader(TENANT_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        // A gateway-minted OBO (multi-hop return leg) carries its tenant explicitly as the ws_tenant claim —
        // its issuer is the gateway STS, not an IdP in the auth-config table, so resolve it from the claim.
        String claimTenant = tenantFromClaim(request);
        if (claimTenant != null) {
            return claimTenant;
        }
        Object issuer = request.getAttribute(GatewayOAuth2Filter.ATTR_ISSUER);
        if (issuer instanceof String iss && !iss.isBlank()) {
            String tenant = authConfigRepository.findFirstByIssuerUri(iss)
                    .map(config -> config.getWsTenantName())
                    .filter(name -> name != null && !name.isBlank())
                    .orElse(DEFAULT_TENANT);
            log.debug("A2A tenant resolved from JWT issuer: {} -> {}", iss, tenant);
            return tenant;
        }
        return DEFAULT_TENANT;
    }

    /** The {@code ws_tenant} claim from the validated token's raw claims (a gateway-minted OBO carries it). */
    private static String tenantFromClaim(HttpServletRequest request) {
        Object raw = request.getAttribute(GatewayOAuth2Filter.ATTR_RAW_CLAIMS);
        if (raw instanceof Map<?, ?> claims) {
            Object tenant = claims.get("ws_tenant");
            if (tenant instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
