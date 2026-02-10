package com.ws.wsAgenticSecurity.audit.constants;

/**
 * Every auditable event type across the WS MCP Gateway.
 *
 * <p>Grouped by the five activity areas:
 * <ul>
 *   <li>{@code SERVER_*}         — AI Client &lt;-&gt; WS Server (Northbound)</li>
 *   <li>{@code CLIENT_*}         — WS Client &lt;-&gt; Enterprise MCP Server (Southbound)</li>
 *   <li>{@code REGISTRY_*}       — Capability Registry CRUD</li>
 *   <li>{@code ORCHESTRATION_*}  — Orchestration Layer</li>
 *   <li>{@code PDP_*}            — Policy Decision Point</li>
 *   <li>{@code SYSTEM_*}         — System-level events</li>
 * </ul>
 */
public enum AuditEventType {

    // ── Area 1 — AI Client <-> WS Server (Northbound) ────────────────
    SERVER_SESSION_INITIALIZED,
    SERVER_SESSION_DISCONNECTED,
    SERVER_TOOLS_LIST_REQUESTED,
    SERVER_RESOURCES_LIST_REQUESTED,
    SERVER_PROMPTS_LIST_REQUESTED,
    SERVER_TOOL_INVOCATION,
    SERVER_RESOURCE_READ,
    SERVER_NOTIFICATION_RECEIVED,

    // ── Area 2 — WS Client <-> Enterprise MCP Server (Southbound) ────
    CLIENT_SESSION_INITIALIZED,
    CLIENT_SESSION_DISCONNECTED,
    CLIENT_TOOLS_LIST_FETCHED,
    CLIENT_RESOURCES_LIST_FETCHED,
    CLIENT_PROMPTS_LIST_FETCHED,
    CLIENT_TOOL_INVOCATION,
    CLIENT_RESOURCE_READ,

    // ── Area 3 — Capability Registry CRUD ─────────────────────────────
    REGISTRY_CAPABILITY_REGISTERED,
    REGISTRY_CAPABILITY_UPDATED,
    REGISTRY_CAPABILITY_REMOVED,
    REGISTRY_BULK_LOAD,
    REGISTRY_SERVER_REFRESH,

    // ── Area 4 — Orchestration Layer ──────────────────────────────────
    ORCHESTRATION_TOOL_EXTRACTED,
    ORCHESTRATION_REGISTRY_LOOKUP,
    ORCHESTRATION_CALL_FORWARDED,
    ORCHESTRATION_ERROR,

    // ── Area 5 — PDP (Policy Decision Point) ──────────────────────────
    PDP_EVALUATION_REQUESTED,
    PDP_DECISION_RENDERED,

    // ── System-level ──────────────────────────────────────────────────
    SYSTEM_STARTUP,
    SYSTEM_SHUTDOWN,
    SYSTEM_ERROR
}
