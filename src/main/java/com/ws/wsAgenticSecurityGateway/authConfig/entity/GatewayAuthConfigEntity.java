package com.ws.wsAgenticSecurityGateway.authConfig.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "gateway_auth_config", schema = "ws_agentic_security")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayAuthConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    @Column(name = "auth_mode", nullable = false, length = 20)
    @Builder.Default
    private String authMode = "none";

    @Column(name = "idp_display_name", length = 255)
    private String idpDisplayName;

    @Column(name = "issuer_uri", length = 1024)
    private String issuerUri;

    @Column(name = "jwks_uri", length = 1024)
    private String jwksUri;

    @Column(name = "authorization_endpoint", length = 1024)
    private String authorizationEndpoint;

    @Column(name = "token_endpoint", length = 1024)
    private String tokenEndpoint;

    @Column(name = "introspection_endpoint", length = 1024)
    private String introspectionEndpoint;

    @Column(name = "registration_endpoint", length = 1024)
    private String registrationEndpoint;

    @Column(name = "supported_scopes", length = 512)
    @Builder.Default
    private String supportedScopes = "openid,email,profile";

    @Column(name = "audience", length = 512)
    private String audience;

    @Column(name = "token_classification_mode", length = 30)
    @Builder.Default
    private String tokenClassificationMode = "jwt-signals";

    @Column(name = "introspection_client_id", length = 255)
    private String introspectionClientId;

    @Column(name = "introspection_client_secret", length = 1024)
    private String introspectionClientSecret;

    @Column(name = "grace_period_minutes")
    @Builder.Default
    private Integer gracePeriodMinutes = 30;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Version
    @Column(name = "version")
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
