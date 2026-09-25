package com.ws.wsAgenticSecurityGateway.sts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A revoked OBO token, keyed by its {@code jti}. Revocation invalidates a minted delegation token before its
 * natural expiry; once {@link #expiresAt} passes the token would be rejected on TTL anyway, so the row is
 * purged then. {@code jti} is globally unique, so revocation checks are tenant-independent.
 *
 * <p>Table auto-created by Hibernate {@code ddl-auto: update} in schema {@code ws_agentic_security}.
 */
@Entity
@Table(name = "gateway_sts_revocation", schema = "ws_agentic_security")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayStsRevocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    /** The revoked token's {@code jti} (globally unique). */
    @Column(name = "jti", nullable = false, unique = true, length = 100)
    private String jti;

    /** The revoked token's natural expiry — after this the row is purged (TTL already rejects it). */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "reason", length = 512)
    private String reason;

    @CreationTimestamp
    @Column(name = "revoked_at", nullable = false, updatable = false)
    private LocalDateTime revokedAt;
}
