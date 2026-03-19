package com.ws.wsAgenticSecurityGateway.wsServer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for 3-tier token classification (Human vs Automated).
 *
 * <p>Controls how the gateway determines whether a JWT was issued via
 * Authorization Code (human-delegated) or Client Credentials (automated agent).
 *
 * <p>Three modes:
 * <ul>
 *   <li>{@code introspect} — Tier 1 (IdP introspection) → Tier 2 (JWT signals) → Tier 3 (conservative default)</li>
 *   <li>{@code jwt-signals} — Tier 2 → Tier 3 only (no IdP call, zero latency)</li>
 *   <li>{@code conservative} — Everything classified as HUMAN_DELEGATED</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "ws.gateway.auth.token-classification")
@Data
public class TokenClassificationProperties {

    /** Classification mode: "introspect", "jwt-signals", "conservative". Default: jwt-signals */
    private String mode = "jwt-signals";

    /** TTL for introspection cache entries in seconds. Default: 3600 (1 hour). */
    private int cacheTtl = 3600;

    /** OAuth2 token introspection endpoint URI. If blank, auto-discovered from OIDC metadata. */
    private String introspectionUri = "";

    /** Client ID for introspection endpoint authentication (Basic Auth). */
    private String introspectionClientId = "";

    /** Client secret for introspection endpoint authentication (Basic Auth). */
    private String introspectionClientSecret = "";

    /** Timeout in milliseconds for introspection HTTP call. Default: 3000. */
    private int introspectionTimeoutMs = 3000;

    public boolean isIntrospectMode() {
        return "introspect".equalsIgnoreCase(mode);
    }

    public boolean isJwtSignalsMode() {
        return "jwt-signals".equalsIgnoreCase(mode);
    }

    public boolean isConservativeMode() {
        return "conservative".equalsIgnoreCase(mode);
    }

    public boolean isIntrospectionConfigured() {
        return introspectionClientId != null && !introspectionClientId.isBlank()
                && introspectionClientSecret != null && !introspectionClientSecret.isBlank();
    }
}
