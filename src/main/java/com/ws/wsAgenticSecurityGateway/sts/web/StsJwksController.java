package com.ws.wsAgenticSecurityGateway.sts.web;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.sts.service.StsKeyService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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

    public StsJwksController(StsKeyService keyService) {
        this.keyService = keyService;
    }

    @GetMapping(value = "/.well-known/sts/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return keyService.jwks(TenantContext.get()).toJSONObject();
    }
}
