package com.ws.wsAgenticSecurityGateway.capabilityRegistry.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "mcp_resource", schema = "ws_agentic_security",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_mcp_resource_public_name",
                        columnNames = {"public_name", "ws_tenant_name"})
        },
        indexes = {
                @Index(name = "idx_mcp_resource_public_name", columnList = "public_name"),
                @Index(name = "idx_mcp_resource_server_id", columnList = "server_id"),
                @Index(name = "idx_mcp_resource_uri", columnList = "resource_uri")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpResourceEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", nullable = false)
    private McpServerEntity server;

    @Column(name = "resource_uri", nullable = false, length = 1024)
    private String resourceUri;

    @Column(name = "public_name", nullable = false, length = 512)
    private String publicName;

    @Column(name = "name", length = 256)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @CreationTimestamp
    @Column(name = "registered_at", nullable = false, updatable = false)
    private LocalDateTime registeredAt;
}
