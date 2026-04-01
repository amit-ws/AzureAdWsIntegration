package com.ws.wsAgenticSecurityGateway.authConfig.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthConfigRequest {

    @NotBlank(message = "Auth mode is required")
    @Pattern(regexp = "^(oauth2|none)$", message = "Auth mode must be 'oauth2' or 'none'")
    private String authMode;

    @Size(max = 255, message = "IdP display name must be 255 characters or less")
    private String idpDisplayName;

    @Size(max = 1024, message = "Issuer URI must be 1024 characters or less")
    private String issuerUri;

    @Size(max = 1024)
    private String jwksUri;

    @Size(max = 1024)
    private String authorizationEndpoint;

    @Size(max = 1024)
    private String tokenEndpoint;

    @Size(max = 1024)
    private String introspectionEndpoint;

    @Size(max = 1024)
    private String registrationEndpoint;

    @Size(max = 512)
    @Builder.Default
    private String supportedScopes = "openid,email,profile";

    @Size(max = 512)
    private String audience;

    @Pattern(regexp = "^(introspect|jwt-signals|conservative)$",
            message = "Classification mode must be 'introspect', 'jwt-signals', or 'conservative'")
    @Builder.Default
    private String tokenClassificationMode = "jwt-signals";

    @Size(max = 255)
    private String introspectionClientId;

    @Size(max = 1024)
    private String introspectionClientSecret;

    @Min(value = 0, message = "Grace period cannot be negative")
    @Max(value = 1440, message = "Grace period cannot exceed 24 hours (1440 minutes)")
    @Builder.Default
    private Integer gracePeriodMinutes = 30;

    @Builder.Default
    private Boolean enabled = true;
}
