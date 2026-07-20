package com.ws.wsAgenticSecurityGateway.agentRegistry.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "gateway_human_users", schema = "ws_agentic_security",
        uniqueConstraints = @UniqueConstraint(name = "uq_human_idp_subject",
                columnNames = {"idp_subject", "ws_tenant_name"}),
        indexes = {
                @Index(name = "idx_human_username", columnList = "preferred_username"),
                @Index(name = "idx_human_email", columnList = "email"),
                @Index(name = "idx_human_status", columnList = "status")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayHumanUserEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    @Column(name = "idp_subject", nullable = false, length = 256)
    private String idpSubject;

    @Column(name = "preferred_username", length = 256)
    private String preferredUsername;

    @Column(name = "email", length = 256)
    private String email;

    @Column(name = "full_name", length = 256)
    private String fullName;

    @Column(name = "given_name", length = 128)
    private String givenName;

    @Column(name = "family_name", length = 128)
    private String familyName;

    @Column(name = "idp_issuer", length = 512)
    private String idpIssuer;

    @Column(name = "email_verified")
    private Boolean emailVerified;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "realm_roles", columnDefinition = "jsonb")
    private List<String> realmRoles;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "client_roles", columnDefinition = "jsonb")
    private List<String> clientRoles;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_claims", columnDefinition = "jsonb")
    private Map<String, Object> customClaims;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "total_sessions", nullable = false)
    @Builder.Default
    private Integer totalSessions = 0;

    @Column(name = "total_requests", nullable = false)
    @Builder.Default
    private Long totalRequests = 0L;

    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "blocked_reason", length = 512)
    private String blockedReason;

    @Column(name = "blocked_at")
    private LocalDateTime blockedAt;

    @Column(name = "last_ip_address", length = 64)
    private String lastIpAddress;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_jwt_claims", columnDefinition = "jsonb")
    private Map<String, Object> lastJwtClaims;
}
