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
 * A revoked agent SESSION — kills a whole in-flight delegation chain, not just one token. Where {@link
 * GatewayStsRevocationEntity} is surgical (one minted OBO {@code jti}), this is the "stop everything this
 * session is doing" switch: once a {@code sessionId} is here, the gateway refuses to mint any further OBO
 * token for it (mint-time, fail-closed) and rejects its inbound requests at the door (honor-time).
 *
 * <p>Sessions outlive individual tokens, so {@link #expiresAt} uses a longer default window than a token
 * revocation; the row is purged once it passes. Table auto-created by Hibernate {@code ddl-auto: update}.
 */
@Entity
@Table(name = "gateway_sts_session_revocation", schema = "ws_agentic_security")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayStsSessionRevocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    /** The revoked agent session id (matches the mint-path sessionId and the in-flight agentSessionId). */
    @Column(name = "session_id", nullable = false, unique = true, length = 200)
    private String sessionId;

    /** When the revocation self-purges — after this the row is dropped. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "reason", length = 512)
    private String reason;

    @CreationTimestamp
    @Column(name = "revoked_at", nullable = false, updatable = false)
    private LocalDateTime revokedAt;
}
