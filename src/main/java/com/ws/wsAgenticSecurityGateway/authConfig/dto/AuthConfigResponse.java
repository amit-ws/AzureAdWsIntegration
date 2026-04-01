package com.ws.wsAgenticSecurityGateway.authConfig.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthConfigResponse {

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
    private String introspectionClientSecret;
    private Integer gracePeriodMinutes;
    private Boolean enabled;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private String effectiveMode;

    private String configSource;

    private Boolean jwtDecoderActive;

    private Boolean gracePeriodActive;

    private Integer gracePeriodRemainingMinutes;
}
