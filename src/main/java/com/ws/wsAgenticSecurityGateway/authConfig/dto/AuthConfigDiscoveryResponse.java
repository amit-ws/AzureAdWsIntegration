package com.ws.wsAgenticSecurityGateway.authConfig.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response from OIDC endpoint auto-discovery.
 * Populated by fetching the IdP's .well-known/openid-configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthConfigDiscoveryResponse {

    private String issuerUri;
    private String jwksUri;
    private String authorizationEndpoint;
    private String tokenEndpoint;
    private String introspectionEndpoint;
    private String registrationEndpoint;
    private String supportedScopes;
    private LocalDateTime discoveredAt;
    private String error;
}
