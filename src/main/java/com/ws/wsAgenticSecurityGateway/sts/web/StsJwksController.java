package com.ws.wsAgenticSecurityGateway.sts.web;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.sts.service.StsKeyService;
import com.ws.wsAgenticSecurityGateway.sts.service.StsRevocationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Serves the STS's public signing keys (per tenant) so JWKS-trusting parties can verify
 * gateway-minted OBO tokens — the same way the gateway fetches an IdP's JWKS.
 *
 * <p>Public endpoint: not a {@code /mcp} path, so it is handled by the permissive catch-all
 * security chain ({@code GatewaySecurityConfig.permissiveFilterChain}) — no auth required.
 * The tenant is resolved from {@link TenantContext} (populated from the {@code X-WS-Tenant} header).
 */
@RestController
public class StsJwksController {

    private final StsKeyService keyService;
    private final StsRevocationService revocationService;

    public StsJwksController(StsKeyService keyService, StsRevocationService revocationService) {
        this.keyService = keyService;
        this.revocationService = revocationService;
    }

    @GetMapping(value = "/.well-known/sts/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return keyService.jwks(TenantContext.get()).toJSONObject();
    }

    /**
     * Revocation check for a gateway-minted OBO token — a resource server (or the gateway on a later hop) can
     * ask whether a token's {@code jti} has been revoked before honoring it. Public, like the JWKS above;
     * {@code jti} is globally unique so no tenant is needed.
     */
    @GetMapping(value = "/.well-known/sts/revocations/{jti}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> revocationStatus(@PathVariable String jti) {
        return Map.of("jti", jti, "revoked", revocationService.isRevoked(jti));
    }
}
