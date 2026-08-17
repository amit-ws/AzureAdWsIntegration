package com.ws.wsAgenticSecurityGateway.postprocessor.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Egress-governance classification of a single governed response hop (the post-processor's output). Written
 * asynchronously in the response path — right after a hop's {@code callTool(...)} returns — as the gateway observes
 * and tags what data flowed back, without mutating it (V1: observe/tag/propagate/learn, no enforcement).
 *
 * <p><b>Metadata only — never the raw value.</b> This records <em>what kind</em> of data a response carried
 * (categories, sensitivity, volume) and which detectors fired, so the sensitivity overlay, per-tool/agent
 * fingerprints, and (later) the response-side PDP can reason about content without the store becoming a
 * data honeypot. The raw payload is redacted before audit — the ordering is post-process → then audit.
 *
 * <p>This belongs to the gateway's egress layer, not the CISO dashboard: the CISO Track-2 views (sensitive-data
 * exposure, GDPR/EU AI Act) <em>read</em> from this table; they do not own it.
 */
@Entity
@Table(name = "gateway_response_classification", schema = "ws_agentic_security",
        uniqueConstraints = @UniqueConstraint(name = "uq_response_classification_source",
                columnNames = {"source_event_id"}),
        indexes = {
                @Index(name = "idx_response_classification_tenant_time", columnList = "ws_tenant_name, classified_at"),
                @Index(name = "idx_response_classification_producer", columnList = "ws_tenant_name, producer"),
                @Index(name = "idx_response_classification_correlation", columnList = "correlation_id"),
                @Index(name = "idx_response_classification_sensitivity", columnList = "ws_tenant_name, sensitivity")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayResponseClassificationEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    /** Ties this classification to the request flow it belongs to (rides the existing trace plumbing). */
    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    /** The audit event whose response was classified (one classification per response event). Nullable if the
     *  hop is classified before its audit row exists. */
    @Column(name = "source_event_id")
    private UUID sourceEventId;

    /** MCP or A2A — the leg the response came back on. */
    @Column(name = "protocol", length = 16)
    private String protocol;

    /** TOOL or SKILL — what produced the response. */
    @Column(name = "capability_type", length = 24)
    private String capabilityType;

    @Column(name = "capability_name", length = 256)
    private String capabilityName;

    /** The agent/server that produced the response (data source). */
    @Column(name = "producer", length = 256)
    private String producer;

    /** The recipient the response is headed to (the next hop / consumer). */
    @Column(name = "consumer", length = 256)
    private String consumer;

    // ── Exact identities (stamped at the hook) so per-agent / per-tool rollups join deterministically, no
    //    name-guessing. Display names above stay for the UI; these are the stable keys. ──────────────────────

    /** The calling agent's registry id (the consumer) — joins to the agents table. */
    @Column(name = "consumer_agent_id")
    private UUID consumerAgentId;

    /** What produced the response: {@code SERVER} (tool/prompt/resource) or {@code AGENT} (skill). */
    @Column(name = "producer_kind", length = 16)
    private String producerKind;

    /** The MCP server's id (producer of a tool/prompt/resource) — joins to the server table. */
    @Column(name = "producer_server_id")
    private UUID producerServerId;

    /** The downstream agent's registry id (producer of a skill) — joins to the agents table. */
    @Column(name = "producer_agent_id")
    private UUID producerAgentId;

    /** RESPONSE today; leaves room for request-side arg inspection (Phase-3) without a schema change. */
    @Column(name = "direction", length = 16, nullable = false)
    @Builder.Default
    private String direction = "RESPONSE";

    /** Detected data categories, e.g. ["PII","SECRET","FINANCIAL"]. Value-driven, name-agnostic. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_categories", columnDefinition = "jsonb")
    private List<String> dataCategories;

    /** Overall sensitivity label: PUBLIC / INTERNAL / CONFIDENTIAL / RESTRICTED. */
    @Column(name = "sensitivity", length = 24)
    private String sensitivity;

    /** Payload size — a large payload is itself a mass-exfiltration signal. */
    @Column(name = "volume_bytes")
    private Long volumeBytes;

    /** Row/record count when the response is structured (null for free text). */
    @Column(name = "record_count")
    private Integer recordCount;

    /** A response is an attack vector — flags detected prompt-injection (neutralization is a later, V2, action). */
    @Column(name = "injection_detected", nullable = false)
    @Builder.Default
    private boolean injectionDetected = false;

    /** Which detectors fired and their non-sensitive evidence (e.g. {"ssn":{"count":1,"matcher":"luhn"}}) —
     *  for auditability. Never the raw matched value. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detectors", columnDefinition = "jsonb")
    private Map<String, Object> detectors;

    /** True when the raw payload was redacted before being persisted to the audit trail (honeypot fix). */
    @Column(name = "redacted", nullable = false)
    @Builder.Default
    private boolean redacted = false;

    /** The classifier ruleset version that produced this row — lets tags evolve without ambiguity. */
    @Column(name = "classifier_version", length = 32)
    private String classifierVersion;

    /**
     * Outcome of the classification pass — so a "clean" result is distinguishable from a classifier that was
     * SKIPPED or ERRORED (fail-open policy: we never break a call for a tagging failure, but we must record that
     * coverage was missed). CLASSIFIED / SKIPPED / ERROR.
     */
    @Column(name = "status", length = 16, nullable = false)
    @Builder.Default
    private String status = "CLASSIFIED";

    /** Classifier latency for this hop — ops/observability (the async path must stay off the hot path). */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** Row-format version — forward-compatibility, consistent with the other audit/registry tables. */
    @Column(name = "schema_version", length = 16)
    private String schemaVersion;

    // ── Enforcement outcome — reserved for V2, so the row is ready without a later migration ─────────────────────
    // These are per-response ENFORCEMENT results and belong on this row. V2's genuinely-separate entities — the
    // egress-entitlement rule catalog and the tokenization vault — are their own tables, not columns here.

    /** True when this hop is the terminal egress (the exit to the consumer app) — the one place V2 enforces
     *  synchronously. Known already in V1 (the overlay marks the exit); V2 acts on it. */
    @Column(name = "is_terminal_egress", nullable = false)
    @Builder.Default
    private boolean terminalEgress = false;

    /** Lifecycle of enforcement for this hop: OBSERVE (V1 — tag only) / SHADOW (V2 dry-run "would act") / LIVE. */
    @Column(name = "enforcement_mode", length = 16, nullable = false)
    @Builder.Default
    private String enforcementMode = "OBSERVE";

    /** What was done (or, in SHADOW, would be done) to the payload:
     *  OBSERVED / ALLOW_RAW / REDACTED / TOKENIZED / TRUNCATED / BLOCKED / NEUTRALIZED. V1 = OBSERVED. */
    @Column(name = "action_taken", length = 24, nullable = false)
    @Builder.Default
    private String actionTaken = "OBSERVED";

    /** The egress policy that decided the action + its reason — mirrors the request-side pdp_policy_id/pdp_reason
     *  so attribution is symmetric. Null in V1. */
    @Column(name = "egress_policy_id", length = 256)
    private String egressPolicyId;

    @Column(name = "enforcement_reason", length = 512)
    private String enforcementReason;

    /** Categories inherited from upstream hops (propagated/derived taint) — distinct from {@code dataCategories},
     *  which are literally present in THIS response. Powers "this answer is derived from PII" at the terminal edge.
     *  Populated as propagation matures; nullable. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provenance_categories", columnDefinition = "jsonb")
    private List<String> provenanceCategories;

    /** Open extension bag — anything a later version needs that we didn't foresee lands here with no migration
     *  (the never-regret escape hatch). Consistent with the codebase's other jsonb attribute columns. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", columnDefinition = "jsonb")
    private Map<String, Object> attributes;

    @Column(name = "classified_at", nullable = false)
    private LocalDateTime classifiedAt;
}
