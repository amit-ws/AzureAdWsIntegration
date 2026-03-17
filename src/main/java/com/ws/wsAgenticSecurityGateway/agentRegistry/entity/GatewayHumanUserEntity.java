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
 * Persistent identity record for human users who interact with the gateway through AI agents.
 *
 * <p>Populated on first HUMAN_DELEGATED token seen, upserted on every subsequent request.
 * Stores ALL identity claims from the JWT — roles, custom claims, and raw snapshot.
 *
 * <p>Provides a queryable identity store separate from audit logs:
 * <ul>
 *   <li>Admin can list/search all human users</li>
 *   <li>Admin can block a human across all agents</li>
 *   <li>Sessions link to human via FK — "Which agents did this human use?"</li>
 *   <li>PDP can reference human attributes without JWT parsing at eval time</li>
 * </ul>
 */
@Entity
@Table(name = "gateway_human_users", schema = "ws_agentic_security",
        uniqueConstraints = @UniqueConstraint(name = "uq_human_idp_subject",
                columnNames = {"idp_subject"}),
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

    // ── Core Identity (from JWT) ────────────────────────────────────────

    /** JWT "sub" — stable UUID from IdP. Immutable per user per IdP. */
    @Column(name = "idp_subject", nullable = false, length = 256)
    private String idpSubject;

    /** JWT "preferred_username" (e.g., "amit-prakash"). */
    @Column(name = "preferred_username", length = 256)
    private String preferredUsername;

    /** JWT "email". */
    @Column(name = "email", length = 256)
    private String email;

    /** JWT "name" (display name). */
    @Column(name = "full_name", length = 256)
    private String fullName;

    /** JWT "given_name". */
    @Column(name = "given_name", length = 128)
    private String givenName;

    /** JWT "family_name". */
    @Column(name = "family_name", length = 128)
    private String familyName;

    // ── IdP Metadata ────────────────────────────────────────────────────

    /** JWT "iss" — which IdP issued this identity. */
    @Column(name = "idp_issuer", length = 512)
    private String idpIssuer;

    /** JWT "email_verified". */
    @Column(name = "email_verified")
    private Boolean emailVerified;

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

    /** First time this human was seen via the gateway. */
    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    /** Last time this human made a request through the gateway. */
    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    /** How many MCP sessions this human has initiated. */
    @Column(name = "total_sessions", nullable = false)
    @Builder.Default
    private Integer totalSessions = 0;

    /** Total MCP requests from this human across all sessions. */
    @Column(name = "total_requests", nullable = false)
    @Builder.Default
    private Long totalRequests = 0L;

    // ── Status & Admin Controls ─────────────────────────────────────────

    /** ACTIVE or BLOCKED. Admin can block a human across all agents. */
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    /** Reason for blocking (set by admin). */
    @Column(name = "blocked_reason", length = 512)
    private String blockedReason;

    /** When the user was blocked. */
    @Column(name = "blocked_at")
    private LocalDateTime blockedAt;

    // ── Raw JWT Snapshot ────────────────────────────────────────────────

    /** Full raw JWT claims from the latest token (for debugging + discovering new data). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_jwt_claims", columnDefinition = "jsonb")
    private Map<String, Object> lastJwtClaims;
}
