package com.ws.wsAgenticSecurityGateway.wsClient.entity;

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
@Table(name = "gateway_server_config", schema = "ws_agentic_security",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_server_config_name",
                        columnNames = {"server_name", "ws_tenant_name"})
        },
        indexes = {
                @Index(name = "idx_server_config_name", columnList = "server_name"),
                @Index(name = "idx_server_config_enabled", columnList = "enabled")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayServerConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    @Column(name = "server_name", nullable = false, length = 128)
    private String serverName;

    @Column(name = "type", nullable = false, length = 20)
    @Builder.Default
    private String type = "http";

    @Column(name = "url", nullable = false, length = 1024)
    private String url;

    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "headers", columnDefinition = "JSONB")
    private JsonNode headers;

    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "server_config", columnDefinition = "JSONB")
    private JsonNode serverConfig;

    @Column(name = "timeout_seconds")
    @Builder.Default
    private Integer timeoutSeconds = 30;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "auto_connect", nullable = false)
    @Builder.Default
    private Boolean autoConnect = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
