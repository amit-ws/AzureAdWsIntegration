package com.ws.wsAgenticSecurityGateway.audit.constants;

/**
 * Identifies which gateway module produced the audit record.
 */
public enum AuditModule {
    /** AI Agent &lt;-&gt; WS MCP Server (Agent-Facing). */
    WS_SERVER,

    /** WS MCP Client &lt;-&gt; Enterprise MCP Servers (Server-Facing). */
    WS_CLIENT,

    /** Capability Registry Service (tools / resources / prompts CRUD). */
    CAPABILITY_REGISTRY,

    /** Orchestration Layer (routing, correlation, forwarding). */
    ORCHESTRATION_LAYER,

    /** Policy Decision Point (ABAC evaluation). */
    PDP,

    /** MCP Server Configuration management (CRUD via admin API). */
    SERVER_CONFIG,

    /** Agent discovery/approval/blocking workflow (admin + policy gates). */
    AGENT_REGISTRY,

    /** Capability Access Profile management (CRUD, assignment, access control). */
    CAPABILITY_PROFILES,

    /** Cross-cutting system events (startup, shutdown, etc.). */
    SYSTEM
}
