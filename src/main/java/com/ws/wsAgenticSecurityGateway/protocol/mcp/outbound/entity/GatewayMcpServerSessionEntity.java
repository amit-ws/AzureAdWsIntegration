package com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "gateway_mcp_server_session", schema = "ws_agentic_security",
        indexes = {
                @Index(name = "idx_server_session_name", columnList = "server_name"),
                @Index(name = "idx_server_session_status", columnList = "status"),
                @Index(name = "idx_server_session_id", columnList = "session_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayMcpServerSessionEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    @Column(name = "server_name", nullable = false, length = 128)
    private String serverName;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "server_display_name", length = 256)
    private String serverDisplayName;

    @Column(name = "server_version", length = 128)
    private String serverVersion;

    @Column(name = "server_url", length = 1024)
    private String serverUrl;

    @CreationTimestamp
    @Column(name = "connected_at", nullable = false, updatable = false)
    private LocalDateTime connectedAt;

    @Column(name = "disconnected_at")
    private LocalDateTime disconnectedAt;

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    @Column(name = "tool_count")
    @Builder.Default
    private Integer toolCount = 0;

    @Column(name = "resource_count")
    @Builder.Default
    private Integer resourceCount = 0;

    @Column(name = "prompt_count")
    @Builder.Default
    private Integer promptCount = 0;

    @Column(name = "total_requests")
    @Builder.Default
    private Long totalRequests = 0L;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "CONNECTED";

    @Column(name = "last_health_check_at")
    private LocalDateTime lastHealthCheckAt;

    @Column(name = "health_check_status", length = 20)
    private String healthCheckStatus;

    @Column(name = "consecutive_failures")
    @Builder.Default
    private Integer consecutiveFailures = 0;
}
