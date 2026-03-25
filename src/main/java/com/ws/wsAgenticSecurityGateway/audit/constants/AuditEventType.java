package com.ws.wsAgenticSecurityGateway.audit.constants;

/**
 * Every auditable event type across the WS MCP Gateway.
 *
 * <p>
 * Grouped by the five activity areas:
 * <ul>
 * <li>{@code SERVER_*} — AI Client &lt;-&gt; WS Server (Agent-Facing)</li>
 * <li>{@code CLIENT_*} — WS Client &lt;-&gt; Enterprise MCP Server
 * (Server-Facing)</li>
 * <li>{@code REGISTRY_*} — Capability Registry CRUD</li>
 * <li>{@code ORCHESTRATION_*} — Orchestration Layer</li>
 * <li>{@code PDP_*} — Policy Decision Point</li>
 * <li>{@code SYSTEM_*} — System-level events</li>
 * </ul>
 */
public enum AuditEventType {

    // ── Area 0 — Authentication Events ────────────────────────────────
    OAUTH2_AUTH_SUCCESS,            // JWT validated, claims extracted
    OAUTH2_AUTH_FAILURE,            // JWT validation failed (expired, bad sig, wrong issuer)
    OAUTH2_TOKEN_CLASSIFICATION_OVERRIDE, // Tier 1 introspection overrode Tier 2 JWT signal classification

    // ── Area 1 — AI Client <-> WS Server (Agent-Facing) ────────────────
    SERVER_SESSION_INITIALIZED,
    SERVER_SESSION_DISCONNECTED,
    SERVER_SESSION_IDLE_REAPED,
    SERVER_REQUEST_REJECTED,
    SERVER_TOOLS_LIST_REQUESTED,
    SERVER_RESOURCES_LIST_REQUESTED,
    SERVER_PROMPTS_LIST_REQUESTED,
    SERVER_TOOL_INVOCATION,
    SERVER_RESOURCE_READ,
    SERVER_NOTIFICATION_RECEIVED,

    // ── Area 2 — WS Client <-> Enterprise MCP Server (Server-Facing) ────
    CLIENT_SESSION_INITIALIZED,
    CLIENT_SESSION_DISCONNECTED,
    CLIENT_HEALTH_CHECK_FAILED,
    CLIENT_TOOLS_LIST_FETCHED,
    CLIENT_RESOURCES_LIST_FETCHED,
    CLIENT_PROMPTS_LIST_FETCHED,
    CLIENT_TOOL_INVOCATION,
    CLIENT_RESOURCE_READ,
    CLIENT_NOTIFICATION_RECEIVED,

    // ── Area 3 — Capability Registry CRUD ─────────────────────────────
    REGISTRY_CAPABILITY_REGISTERED,
    REGISTRY_CAPABILITY_UPDATED,
    REGISTRY_CAPABILITY_REMOVED,
    REGISTRY_BULK_LOAD,
    REGISTRY_SERVER_REFRESH,
    REGISTRY_NOTIFICATION_BROADCAST,

    // ── Area 4 — Orchestration Layer ──────────────────────────────────
    ORCHESTRATION_TOOL_EXTRACTED,
    ORCHESTRATION_REGISTRY_LOOKUP,
    ORCHESTRATION_RESPONSE_RETURNED,
    ORCHESTRATION_ERROR,

    // ── Area 5 — PDP (Policy Decision Point) ──────────────────────────
    PDP_EVALUATION_REQUESTED,
    PDP_DECISION_RENDERED,

    // ── Area 5b — PDP Policy Management ─────────────────────────────
    PDP_POLICY_CREATED,
    PDP_POLICY_UPDATED,
    PDP_POLICY_DELETED,
    PDP_POLICY_TOGGLED,
    PDP_POLICY_VALIDATED,
    PDP_ENGINE_RELOADED,

    // ── Area 5c — PDP LLM Chat ──────────────────────────────────────
    PDP_LLM_CHAT_REQUESTED,
    PDP_LLM_CHAT_COMPLETED,

    // ── Area 5d — PDP Custom Attributes ─────────────────────────────
    PDP_CUSTOM_ATTR_CREATED,
    PDP_CUSTOM_ATTR_UPDATED,
    PDP_CUSTOM_ATTR_DELETED,
    PDP_CUSTOM_ATTR_TOGGLED,

    // ── Area 6 — Server Configuration Management ─────────────────────
    SERVER_CONFIG_CREATED,
    SERVER_CONFIG_UPDATED,
    SERVER_CONFIG_DELETED,

    // ── Area 7 — Agent Registry & Approval Workflow ──────────────────
    AGENT_APPROVED,
    AGENT_BLOCKED,
    AGENT_CONNECTION_REJECTED,
    SESSION_IDENTITY_MISMATCH,

    // ── Area 7b — Identity Approval & Blocking Events ────────────────
    HUMAN_USER_APPROVED,             // admin action: approve human user (PENDING → ACTIVE)
    HUMAN_USER_BLOCKED,              // admin action: block human user
    HUMAN_USER_UNBLOCKED,            // admin action: unblock human user
    NHI_IDENTITY_APPROVED,           // admin action: approve NHI (PENDING → ACTIVE)
    NHI_IDENTITY_BLOCKED,            // admin action: block NHI
    NHI_IDENTITY_UNBLOCKED,          // admin action: unblock NHI
    HUMAN_CONNECTION_REJECTED,       // runtime: blocked/pending human tried to execute
    NHI_CONNECTION_REJECTED,         // runtime: blocked/pending NHI tried to execute
    AGENT_PENDING_REJECTED,          // runtime: pending agent tried to execute
    BLOCKED_SESSION_TERMINATED,      // proactive: admin block killed active session

    // ── Area 8 — Capability Access Profiles ─────────────────────────
    CAPABILITY_ACCESS_GRANTED,
    CAPABILITY_ACCESS_DENIED,
    CAPABILITY_PROFILE_CREATED,
    CAPABILITY_PROFILE_UPDATED,
    CAPABILITY_PROFILE_DELETED,
    CAPABILITY_PROFILE_ASSIGNED,
    CAPABILITY_PROFILE_UNASSIGNED,

    // ── Area 9 — Auth Configuration Management ──────────────────────
    AUTH_CONFIG_CREATED,
    AUTH_CONFIG_UPDATED,
    AUTH_CONFIG_DELETED,
    AUTH_MODE_CHANGED,
    AUTH_CONFIG_VALIDATED,
    AUTH_CONFIG_VALIDATION_FAILED,
    AUTH_JWKS_REFRESHED,
    AUTH_GRACE_PERIOD_STARTED,
    AUTH_GRACE_PERIOD_ENDED,

    // ── System-level ──────────────────────────────────────────────────
    SYSTEM_STARTUP,
    SYSTEM_SHUTDOWN,
    SYSTEM_ERROR
}
