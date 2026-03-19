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

/**
 * Persistent identity record for Non-Human Identities (NHIs) — service accounts, CI bots,
 * monitoring agents — that interact with the gateway via Client Credentials flow.
 *
 * <p>Mirrors {@link GatewayHumanUserEntity} for the automated side. Populated on first
 * AUTOMATED_AGENT token seen, upserted on every subsequent request.
 *
 * <p>Provides a queryable identity store:
 * <ul>
 *   <li>Admin can list/search all service accounts using the gateway</li>
 *   <li>Admin can block an NHI across all agents</li>
 *   <li>Sessions link to NHI via FK — "Which agents does this bot use?"</li>
 *   <li>PDP can reference NHI attributes for Cedar policies</li>
 * </ul>
 */
@Entity
@Table(name = "gateway_nhi_registry", schema = "ws_agentic_security",
        uniqueConstraints = @UniqueConstraint(name = "uq_nhi_idp_subject",
                columnNames = {"idp_subject"}),
        indexes = {
                @Index(name = "idx_nhi_service_name", columnList = "service_name"),
                @Index(name = "idx_nhi_client_id", columnList = "client_id"),
                @Index(name = "idx_nhi_status", columnList = "status")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayNhiEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    // ── Core Identity (from JWT) ────────────────────────────────────────

    /** JWT "sub" — stable service account UUID from IdP. Immutable. */
    @Column(name = "idp_subject", nullable = false, length = 256)
    private String idpSubject;

    /** Display name for admin — derived from clientId or sub (whichever is human-readable). */
    @Column(name = "service_name", length = 256)
    private String serviceName;

    /** JWT "azp" — OAuth2 client_id that authenticated. */
    @Column(name = "client_id", length = 256)
    private String clientId;

    // ── IdP Metadata ────────────────────────────────────────────────────

    /** JWT "iss" — which IdP issued this identity. */
    @Column(name = "idp_issuer", length = 512)
    private String idpIssuer;

    // ── Roles (snapshot from latest JWT — always up to date) ────────────

    /** Latest realm_access.roles from JWT. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "realm_roles", columnDefinition = "jsonb")
    private List<String> realmRoles;

    /** Latest resource_access.<client>.roles from JWT. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "client_roles", columnDefinition = "jsonb")
    private List<String> clientRoles;

    // ── Custom Claims (Standard 2 — ws_gateway_* prefixed) ──────────────

    /** All ws_gateway_* claims from JWT. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_claims", columnDefinition = "jsonb")
    private Map<String, Object> customClaims;

    // ── Activity Tracking ───────────────────────────────────────────────

    /** First time this NHI was seen via the gateway. */
    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    /** Last time this NHI made a request through the gateway. */
    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    /** How many MCP sessions this NHI has initiated. */
    @Column(name = "total_sessions", nullable = false)
    @Builder.Default
    private Integer totalSessions = 0;

    /** Total MCP requests from this NHI across all sessions. */
    @Column(name = "total_requests", nullable = false)
    @Builder.Default
    private Long totalRequests = 0L;

    // ── Status & Admin Controls ─────────────────────────────────────────

    /** ACTIVE or BLOCKED. Admin can block an NHI across all agents. */
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    /** Reason for blocking (set by admin). */
    @Column(name = "blocked_reason", length = 512)
    private String blockedReason;

    /** When the NHI was blocked. */
    @Column(name = "blocked_at")
    private LocalDateTime blockedAt;

    /** Admin-set description (e.g., "CI/CD pipeline for staging deployments"). */
    @Column(name = "description", length = 512)
    private String description;

    // ── Network Context ────────────────────────────────────────────────

    /** IP address from the most recent request (X-Forwarded-For aware). */
    @Column(name = "last_ip_address", length = 64)
    private String lastIpAddress;

    // ── Raw JWT Snapshot ────────────────────────────────────────────────

    /** Full raw JWT claims from the latest token (for debugging). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_jwt_claims", columnDefinition = "jsonb")
    private Map<String, Object> lastJwtClaims;
}
