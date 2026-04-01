package com.ws.wsAgenticSecurityGateway.agentRegistry.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

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

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private GatewayAgentEntity agent;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "auth_method", length = 20)
    private String authMethod;

    @Column(name = "auth_identity", length = 256)
    private String authIdentity;

    @CreationTimestamp
    @Column(name = "connected_at", nullable = false, updatable = false)
    private LocalDateTime connectedAt;

    @Column(name = "disconnected_at")
    private LocalDateTime disconnectedAt;

    @Column(name = "request_count")
    @Builder.Default
    private Integer requestCount = 0;

    @Column(name = "last_request_at")
    private LocalDateTime lastRequestAt;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "CONNECTED";

    @Column(name = "token_type", length = 32)
    private String tokenType;

    @Column(name = "human_user_id")
    private UUID humanUserId;

    @Column(name = "nhi_id")
    private UUID nhiId;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;
}
