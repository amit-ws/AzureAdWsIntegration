package com.ws.wsAgenticSecurityGateway.agentRegistry.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents an individual agent session (connection) in the agent registry.
 *
 * <p>Each row tracks a single MCP session from an AI agent to the gateway,
 * including auth context, request counts, and connection timestamps.
 * Multiple sessions can belong to the same {@link GatewayAgentEntity}.
 */
@Entity
@Table(name = "gateway_agent_session", schema = "ws_agentic_security",
        indexes = {
                @Index(name = "idx_agent_session_agent_id", columnList = "agent_id"),
                @Index(name = "idx_agent_session_session_id", columnList = "session_id"),
                @Index(name = "idx_agent_session_status", columnList = "status")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayAgentSessionEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    /** The parent agent profile this session belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private GatewayAgentEntity agent;

    /** The MCP session UUID (Agent ↔ Gateway). */
    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    /** Authentication method used: JWT, API_KEY, or null. */
    @Column(name = "auth_method", length = 20)
    private String authMethod;

    /** Authentication identity: JWT subject or API key name. */
    @Column(name = "auth_identity", length = 256)
    private String authIdentity;

    /** When this session was established. */
    @CreationTimestamp
    @Column(name = "connected_at", nullable = false, updatable = false)
    private LocalDateTime connectedAt;

    /** When this session was terminated (null if still connected). */
    @Column(name = "disconnected_at")
    private LocalDateTime disconnectedAt;

    /** Number of tool/prompt/resource requests in this session. */
    @Column(name = "request_count")
    @Builder.Default
    private Integer requestCount = 0;

    /** Timestamp of the last request in this session. */
    @Column(name = "last_request_at")
    private LocalDateTime lastRequestAt;

    /** Session status: CONNECTED or DISCONNECTED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "CONNECTED";
}
