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

/**
 * Represents a discovered AI agent in the agent registry.
 *
 * <p>Each agent record corresponds to a unique (agent_name, agent_version) pair
 * and is auto-created when the agent first connects via MCP initialize.
 * Analogous to {@code McpServerEntity} for enterprise MCP servers.
 */
@Entity
@Table(name = "gateway_agent", schema = "ws_agentic_security",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_agent_name_version",
                        columnNames = {"agent_name", "agent_version", "ws_tenant_name"})
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

    /** Agent software name from MCP clientInfo (e.g., "claude-desktop", "cursor"). */
    @Column(name = "agent_name", nullable = false, length = 256)
    private String agentName;

    /** Agent software version from MCP clientInfo (e.g., "1.2.3"). */
    @Column(name = "agent_version", length = 128)
    private String agentVersion;

    /** MCP protocol version negotiated during initialize handshake. */
    @Column(name = "protocol_version", length = 20)
    private String protocolVersion;

    /** Agent's declared capabilities from MCP initialize (JSONB). */
    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "capabilities", columnDefinition = "JSONB")
    private JsonNode capabilities;

    /** Agent status — ACTIVE when recently seen, INACTIVE when stale. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    /** Approval status — PENDING for new agents, APPROVED or BLOCKED by admin. */
//    @Column(name = "approval_status", columnDefinition = "varchar(20) not null default 'APPROVED'")
//    @Builder.Default
//    private String approvalStatus = "APPROVED";

    @Column(name = "approval_status", nullable = false, length = 20)
    @Builder.Default
    private String approvalStatus = "PENDING";

    /** First time this agent was discovered. */
    @CreationTimestamp
    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private LocalDateTime firstSeenAt;

    /** Last time this agent connected or made a request. */
    @UpdateTimestamp
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    /** Total number of sessions (connections) from this agent. */
    @Column(name = "total_sessions")
    @Builder.Default
    private Integer totalSessions = 0;

    /** Total number of tool/prompt/resource requests from this agent. */
    @Column(name = "total_requests")
    @Builder.Default
    private Long totalRequests = 0L;

    /** OAuth2 client_id (azp claim) — verified identity from JWT. Primary identity when OAuth2 is active. */
    @Column(name = "auth_client_id", length = 256)
    private String authClientId;

    /** Token classification: "AUTOMATED_AGENT" or "HUMAN_DELEGATED". */
    @Column(name = "token_type", length = 32)
    private String tokenType;
}
