package com.ws.wsAgenticSecurityGateway.authConfig.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Output DTO for auth configuration responses.
 * Includes runtime status fields beyond what's stored in DB.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthConfigResponse {

    // ── Stored Config ──
    private UUID id;
    private String authMode;
    private String idpDisplayName;
    private String issuerUri;
    private String jwksUri;
    private String authorizationEndpoint;
    private String tokenEndpoint;
    private String introspectionEndpoint;
    private String registrationEndpoint;
    private String supportedScopes;
    private String audience;
    private String tokenClassificationMode;
    private String introspectionClientId;
    private String introspectionClientSecret; // masked as "***REDACTED***"
    private Integer gracePeriodMinutes;
    private Boolean enabled;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── Runtime Status (enriched at response time) ──

    /** What is actually active right now: "oauth2" or "none". */
    private String effectiveMode;

    /** Where the config came from: "database", "environment", or "default". */
    private String configSource;

    /** Whether the JwtDecoder is currently active and validating tokens. */
    private Boolean jwtDecoderActive;

    /** Whether the dual-decoder grace period is currently active. */
    private Boolean gracePeriodActive;

    /** Minutes remaining in grace period (null if not active). */
    private Integer gracePeriodRemainingMinutes;
}
