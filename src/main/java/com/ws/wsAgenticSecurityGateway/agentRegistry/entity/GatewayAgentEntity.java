package com.ws.wsAgenticSecurityGateway.agentRegistry.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.audit.converter.JsonNodeColumnConverter;
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
@Table(name = "gateway_agent", schema = "ws_agentic_security",
        // Unified Agent Model (#2): identity is (tenant, name) — one canonical agent per name per tenant.
        // Version is demoted to a plain attribute (latest seen), so an agent that reconnects as a new
        // version/transport (e.g. claude-desktop 1.0.0 vs stateless) is ONE agent, not two rows.
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_gateway_agent_tenant_name",
                        columnNames = {"ws_tenant_name", "agent_name"}),
                // One A2A endpoint maps to exactly one agent per tenant — you can't register the same base URL
                // under two different names. (a2a_base_url is null for MCP-only agents; Postgres allows many NULLs.)
                @UniqueConstraint(name = "uq_gateway_agent_tenant_a2a_url",
                        columnNames = {"ws_tenant_name", "a2a_base_url"})
        },
        indexes = {
                @Index(name = "idx_gateway_agent_name", columnList = "agent_name"),
                @Index(name = "idx_gateway_agent_status", columnList = "status"),
                @Index(name = "idx_gateway_agent_approval", columnList = "approval_status")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayAgentEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    @Column(name = "agent_name", nullable = false, length = 256)
    private String agentName;

    @Column(name = "agent_version", length = 128)
    private String agentVersion;

    @Column(name = "protocol_version", length = 20)
    private String protocolVersion;

    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "capabilities", columnDefinition = "JSONB")
    private JsonNode capabilities;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "approval_status", nullable = false, length = 20)
    @Builder.Default
    private String approvalStatus = "PENDING";

    @CreationTimestamp
    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private LocalDateTime firstSeenAt;

    @UpdateTimestamp
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "total_sessions")
    @Builder.Default
    private Integer totalSessions = 0;

    @Column(name = "total_requests")
    @Builder.Default
    private Long totalRequests = 0L;

    @Column(name = "auth_client_id", length = 256)
    private String authClientId;

    @Column(name = "token_type", length = 32)
    private String tokenType;

    // HOW this agent proves its identity — the WorkloadIdentitySource method. "KEYCLOAK" today (OIDC
    // client-credentials); "SPIFFE" once SVID/mTLS is deployed. Lets the registry carry multiple identity
    // roots side by side and makes the SPIFFE swap a data change, not a schema change.
    @Column(name = "identity_source", length = 32)
    private String identitySource;

    // The source-specific verified identifier: the Keycloak client_id today; the SPIFFE ID
    // (spiffe://<trust-domain>/...) once SPIFFE is the source. This is the id we bind a sender-constrained
    // OBO to (its `cnf`), and the id the honor-time check matches the presenter's credential against.
    @Column(name = "workload_id", length = 512)
    private String workloadId;

    // ── Unified Agent Model (#2) facets ──────────────────────────────────────
    // An agent is one identity that may speak more than one protocol. These flags are set explicitly by the
    // write paths (MCP discovery sets speaks_mcp; A2A ingestion sets speaks_a2a + a2a_base_url) rather than
    // inferred, so the dashboard can render protocol badges deterministically.

    /** A2A endpoint base URL (folded in from the former gateway_a2a_agent); null for MCP-only agents. */
    @Column(name = "a2a_base_url", length = 1024)
    private String a2aBaseUrl;

    /** True if this agent connects as an MCP client (auto-discovered on connect). */
    @Column(name = "speaks_mcp", nullable = false)
    @Builder.Default
    private Boolean speaksMcp = false;

    /** True if this agent is registered as an A2A endpoint (has an Agent Card / base URL). */
    @Column(name = "speaks_a2a", nullable = false)
    @Builder.Default
    private Boolean speaksA2a = false;
}
