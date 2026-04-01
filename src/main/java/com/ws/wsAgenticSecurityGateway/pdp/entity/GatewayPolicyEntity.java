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

/**
 * Persisted Cedar policy managed by the gateway admin.
 *
 * <p>Each row holds one Cedar policy statement (permit or forbid).
 * At runtime, all enabled policies are aggregated into a single
 * {@code PolicySet} and evaluated by the Cedar engine.
 *
 * <p>Table: {@code ws_agentic_security.gateway_policy}
 */
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

    /** Human-readable policy name (unique). */
    @Column(name = "policy_name", nullable = false, length = 256)
    private String policyName;

    /** Admin-facing description of what this policy does. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * The Cedar policy ID used in the {@code @id("...")} annotation.
     * Auto-generated from policyName if not provided.
     */
    @Column(name = "cedar_policy_id", nullable = false, length = 256)
    private String cedarPolicyId;

    /**
     * The full Cedar policy text (permit/forbid statement).
     * This is the raw Cedar language that the engine evaluates.
     */
    @Column(name = "policy_text", nullable = false, columnDefinition = "TEXT")
    private String policyText;

    /** Effect: PERMIT or FORBID. Denormalized for fast queries/display. */
    @Column(name = "effect", nullable = false, length = 10)
    private String effect;

    /** Whether this policy is active in the evaluation set. */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** Priority for display ordering (lower = evaluated first). */
    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 100;

    /** Comma-separated tags for categorization (e.g., "security,read-only"). */
    @Column(name = "tags", length = 512)
    private String tags;

    /** How this policy was created: MANUAL, LLM_GENERATED, TEMPLATE. */
    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private String source = "MANUAL";

    /** The original natural-language prompt (if created via LLM chatbot). */
    @Column(name = "original_prompt", columnDefinition = "TEXT")
    private String originalPrompt;

    /** Who created this policy (admin name or "system"). */
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
