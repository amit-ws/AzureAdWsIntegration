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
 * A per-tenant RSA signing key for the STS. The ACTIVE key signs newly minted OBO tokens;
 * RETIRING keys stay in the JWKS during a rotation grace window so tokens they signed still verify.
 * Once the grace window elapses they become RETIRED — dropped from the JWKS, private material scrubbed,
 * kept only as bounded rotation history. The private key material is stored AES/GCM-encrypted
 * (via SecretCryptoService).
 *
 * <p>Table auto-created by Hibernate {@code ddl-auto: update} in schema {@code ws_agentic_security}.
 */
@Entity
@Table(name = "gateway_sts_key", schema = "ws_agentic_security")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayStsKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    @Column(name = "kid", nullable = false, length = 100)
    private String kid;

    /** Public JWK (RSA), JSON — served in the JWKS endpoint. */
    @Column(name = "public_jwk", nullable = false, columnDefinition = "text")
    private String publicJwk;

    /** Full JWK (RSA, incl. private), JSON — encrypted at rest. */
    @Column(name = "private_key_enc", nullable = false, columnDefinition = "text")
    private String privateKeyEnc;

    /**
     * ACTIVE (signs new tokens), RETIRING (served in JWKS during the rotation grace window), or RETIRED
     * (terminal: dropped from the JWKS, private key material scrubbed, retained only as rotation history).
     */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** When this key was demoted to RETIRING (start of the JWKS grace window); null while ACTIVE. */
    @Column(name = "retired_at")
    private LocalDateTime retiredAt;
}
