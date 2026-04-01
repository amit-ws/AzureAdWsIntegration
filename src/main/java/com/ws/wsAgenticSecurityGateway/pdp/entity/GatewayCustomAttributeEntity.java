package com.ws.wsAgenticSecurityGateway.pdp.entity;

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
@Table(name = "gateway_custom_attribute", schema = "ws_agentic_security",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_custom_attr_name",
                        columnNames = {"attribute_name", "ws_tenant_name"})
        },
        indexes = {
                @Index(name = "idx_custom_attr_enabled", columnList = "enabled"),
                @Index(name = "idx_custom_attr_source", columnList = "value_source")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayCustomAttributeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    @Column(name = "attribute_name", length = 128, nullable = false)
    private String attributeName;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "data_type", length = 20, nullable = false)
    private String dataType;

    @Column(name = "value_source", length = 30, nullable = false)
    private String valueSource;

    @Column(name = "source_key", length = 256)
    private String sourceKey;

    @Column(name = "default_value", length = 512)
    private String defaultValue;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}