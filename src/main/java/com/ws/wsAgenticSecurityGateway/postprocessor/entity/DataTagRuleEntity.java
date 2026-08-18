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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
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

    /** For CUSTOM: how the rule matches — {@code REGEX} (uses {@code pattern}) or {@code KEYWORDS} (uses the
     *  {@code keywords} list). Null/blank = REGEX, for back-compat with rules created before keyword matching. */
    @Column(name = "match_type", length = 16)
    private String matchType;

    /** For CUSTOM + REGEX: the regex whose matches are tagged. */
    @Column(name = "pattern", length = 1024)
    private String pattern;

    /** For CUSTOM + KEYWORDS: the literal words/terms to flag — each matched whole-word and case-insensitively.
     *  Stored as a JSON array; the engine compiles it to a safe escaped regex so a non-technical admin never writes
     *  pattern syntax and can't produce a broken or over-matching rule. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "keywords", columnDefinition = "jsonb")
    private List<String> keywords;

    /** For CUSTOM / OVERRIDE: the data categories to tag (e.g. {@code PII}, {@code FINANCIAL}, or custom labels).
     *  Multiple are allowed — a match is tagged with every category listed. Stored as a JSON array, mirroring the
     *  classification table's {@code data_categories} (the {@code @JdbcTypeCode} binds it as jsonb, not varchar[]). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_categories", columnDefinition = "jsonb")
    private List<String> dataCategories;

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
