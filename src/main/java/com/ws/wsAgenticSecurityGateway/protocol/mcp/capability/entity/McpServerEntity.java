package com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.entity;

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
@Table(name = "mcp_server", schema = "ws_agentic_security",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_mcp_server_config_name",
                        columnNames = {"server_config_name", "ws_tenant_name"})
        },
        indexes = {
                @Index(name = "idx_mcp_server_config_name", columnList = "server_config_name"),
                @Index(name = "idx_mcp_server_status", columnList = "status")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpServerEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    @Column(name = "server_config_name", nullable = false, length = 128)
    private String serverConfigName;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(name = "version", length = 256)
    private String version;

    @Column(name = "protocol_version", length = 20)
    private String protocolVersion;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Convert(converter = JsonNodeColumnConverter.class)
    @Column(name = "capabilities", columnDefinition = "JSONB")
    private JsonNode capabilities;

    @CreationTimestamp
    @Column(name = "registered_at", nullable = false, updatable = false)
    private LocalDateTime registeredAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
