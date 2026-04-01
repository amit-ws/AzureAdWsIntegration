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

/**
 * Stores the gateway's authentication configuration (single row, single-tenant).
 *
 * <p>Replaces environment variable-based auth config with DB-driven config
 * that can be managed via the dashboard at runtime without restarts.
 *
 * <p>Resolution order: DB config (if exists and enabled) > environment variables > defaults (mode=none).
 */
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

    /** Auth mode: "oauth2" or "none". */
    @Column(name = "auth_mode", nullable = false, length = 20)
    @Builder.Default
    private String authMode = "none";

    /** Admin-friendly display name for the IdP (e.g., "Company Keycloak"). */
    @Column(name = "idp_display_name", length = 255)
    private String idpDisplayName;

    /** OIDC issuer URI (e.g., https://login.company.com/realms/prod). */
    @Column(name = "issuer_uri", length = 1024)
    private String issuerUri;

    /** JWKS endpoint for JWT signature validation. Auto-discovered or manually set. */
    @Column(name = "jwks_uri", length = 1024)
    private String jwksUri;

    /** OAuth2 authorization endpoint. Auto-discovered or manually set. */
    @Column(name = "authorization_endpoint", length = 1024)
    private String authorizationEndpoint;

    /** OAuth2 token endpoint. Auto-discovered or manually set. */
    @Column(name = "token_endpoint", length = 1024)
    private String tokenEndpoint;

    /** Token introspection endpoint (RFC 7662). Auto-discovered or manually set. */
    @Column(name = "introspection_endpoint", length = 1024)
    private String introspectionEndpoint;

    /** Dynamic client registration endpoint (RFC 7591). Optional. */
    @Column(name = "registration_endpoint", length = 1024)
    private String registrationEndpoint;

    /** Comma-separated OAuth2 scopes to advertise in discovery metadata. */
    @Column(name = "supported_scopes", length = 512)
    @Builder.Default
    private String supportedScopes = "openid,email,profile";

    /** Expected JWT audience claim. Optional — if set, JWTs must include this audience. */
    @Column(name = "audience", length = 512)
    private String audience;

    /** Token classification mode: "introspect", "jwt-signals", or "conservative". */
    @Column(name = "token_classification_mode", length = 30)
    @Builder.Default
    private String tokenClassificationMode = "jwt-signals";

    /** Client ID for token introspection (when introspection requires authentication). */
    @Column(name = "introspection_client_id", length = 255)
    private String introspectionClientId;

    /** Client secret for token introspection — stored AES-256-GCM encrypted. */
    @Column(name = "introspection_client_secret", length = 1024)
    private String introspectionClientSecret;

    /** Grace period in minutes for dual-decoder during IdP change. */
    @Column(name = "grace_period_minutes")
    @Builder.Default
    private Integer gracePeriodMinutes = 30;

    /** Whether this config is active. Disabled config is treated as absent. */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** Optimistic locking version — prevents concurrent admin edits from overwriting. */
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
