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
 * Admin-registered custom attribute for dynamic ABAC policy evaluation.
 *
 * <p>Each row defines a named attribute that becomes available in Cedar policies
 * as {@code context.<attributeName>}. At evaluation time, the attribute's value
 * is resolved from its configured {@code valueSource}:
 * <ul>
 *   <li>{@code STATIC}  — fixed value set by the admin (e.g., environment=production)</li>
 *   <li>{@code HEADER}  — resolved from an HTTP header the agent sends</li>
 *   <li>{@code AGENT_FIELD} — resolved from the agent's DB record (totalRequests, status, etc.)</li>
 * </ul>
 *
 * <p>This enables fully self-service ABAC — admins register any attribute they need,
 * then reference it in Cedar policies, with zero code changes.
 *
 * <p>Table: {@code ws_agentic_security.gateway_custom_attribute}
 */
@Entity
@Table(name = "gateway_custom_attribute", schema = "ws_agentic_security",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_custom_attr_name",
                        columnNames = {"attribute_name"})
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

    /** Attribute name used in Cedar policies (e.g., "riskScore" → context.riskScore). */
    @Column(name = "attribute_name", length = 128, nullable = false)
    private String attributeName;

    /** Human-readable display name for dashboard UI (e.g., "Agent Risk Score"). */
    @Column(name = "display_name", length = 256)
    private String displayName;

    /** Admin-facing description of what this attribute represents. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Expected data type for value coercion.
     * <ul>
     *   <li>{@code STRING}  — compared as text (EQ, NEQ, LIKE, CONTAINS)</li>
     *   <li>{@code INTEGER} — compared as number (GT, LT, GTE, LTE, EQ, NEQ)</li>
     *   <li>{@code BOOLEAN} — compared as true/false (EQ, NEQ)</li>
     * </ul>
     */
    @Column(name = "data_type", length = 20, nullable = false)
    private String dataType;

    /**
     * Where the attribute's value comes from at evaluation time.
     * <ul>
     *   <li>{@code STATIC}      — uses {@code defaultValue} directly</li>
     *   <li>{@code HEADER}      — reads from HTTP request header ({@code sourceKey} = header name)</li>
     *   <li>{@code AGENT_FIELD} — reads from agent registry DB ({@code sourceKey} = field name)</li>
     * </ul>
     */
    @Column(name = "value_source", length = 30, nullable = false)
    private String valueSource;

    /**
     * The key to resolve the value from the source.
     * <ul>
     *   <li>For {@code HEADER}: HTTP header name (e.g., "X-Risk-Score")</li>
     *   <li>For {@code AGENT_FIELD}: agent entity field name (e.g., "totalRequests")</li>
     *   <li>For {@code STATIC}: not used (value comes from {@code defaultValue})</li>
     * </ul>
     */
    @Column(name = "source_key", length = 256)
    private String sourceKey;

    /** Fallback value when the source returns null, or the fixed value for STATIC source. */
    @Column(name = "default_value", length = 512)
    private String defaultValue;

    /** Whether this attribute is active in policy evaluation. */
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