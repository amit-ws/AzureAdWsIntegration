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
@Table(name = "gateway_policy", schema = "ws_agentic_security",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_policy_name",
                        columnNames = {"policy_name", "ws_tenant_name"})
        },
        indexes = {
                @Index(name = "idx_policy_enabled", columnList = "enabled"),
                @Index(name = "idx_policy_effect", columnList = "effect"),
                @Index(name = "idx_policy_created", columnList = "created_at")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayPolicyEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    @Column(name = "policy_name", nullable = false, length = 256)
    private String policyName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "cedar_policy_id", nullable = false, length = 256)
    private String cedarPolicyId;

    @Column(name = "policy_text", nullable = false, columnDefinition = "TEXT")
    private String policyText;

    @Column(name = "effect", nullable = false, length = 10)
    private String effect;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 100;

    @Column(name = "tags", length = 512)
    private String tags;

    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private String source = "MANUAL";

    @Column(name = "original_prompt", columnDefinition = "TEXT")
    private String originalPrompt;

    @Column(name = "created_by", length = 256)
    @Builder.Default
    private String createdBy = "system";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
