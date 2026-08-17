package com.ws.wsAgenticSecurityGateway.postprocessor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An admin-defined rule that adjusts the built-in egress classifier for one tenant. Rules layer ON TOP of the
 * built-in recognizers: {@code CUSTOM} adds a regex detector, {@code DISABLE} turns a built-in off, {@code OVERRIDE}
 * remaps a built-in's category/sensitivity. The rule engine reads the enabled rows here into a
 * {@code RulePolicy} the classifier applies per response.
 *
 * <p>Tenant-scoped; nothing here is sensitive data — it is detector configuration, not payloads.
 */
@Entity
@Table(name = "data_tag_rule", schema = "ws_agentic_security",
        indexes = {
                @Index(name = "idx_data_tag_rule_tenant", columnList = "ws_tenant_name"),
                @Index(name = "idx_data_tag_rule_tenant_enabled", columnList = "ws_tenant_name, enabled")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataTagRuleEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    /** Human label for the rule (shown in the admin UI). */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 16)
    private DataTagRuleType ruleType;

    /** For DISABLE / OVERRIDE: the built-in detector id this rule targets (e.g. {@code email}, {@code ip_address}). */
    @Column(name = "builtin_matcher", length = 64)
    private String builtinMatcher;

    /** For CUSTOM: the regex whose matches are tagged. */
    @Column(name = "pattern", length = 1024)
    private String pattern;

    /** For CUSTOM / OVERRIDE: the data category to tag (e.g. {@code PII}, {@code FINANCIAL}, or a custom label). */
    @Column(name = "data_category", length = 64)
    private String dataCategory;

    /** For CUSTOM / OVERRIDE: PUBLIC / INTERNAL / CONFIDENTIAL / RESTRICTED. */
    @Column(name = "sensitivity", length = 24)
    private String sensitivity;

    /** For CUSTOM: optional context key ({@code ssn|card|secret|phone|iban}) enabling the proximity boost. */
    @Column(name = "context_key", length = 24)
    private String contextKey;

    /** For CUSTOM: base confidence in [0,1] (default 0.9 — a labelled admin rule is high-trust). */
    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
