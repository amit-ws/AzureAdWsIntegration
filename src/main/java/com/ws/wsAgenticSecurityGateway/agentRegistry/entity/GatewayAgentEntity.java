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
}
