package com.ws.wsAgenticSecurity.audit.constants;

/**
 * Identifies which gateway module produced the audit record.
 */
public enum AuditModule {
    /** AI Agent &lt;-&gt; WS MCP Server (Northbound). */
    WS_SERVER,

    /** WS MCP Client &lt;-&gt; Enterprise MCP Servers (Southbound). */
    WS_CLIENT,

    /** Capability Registry Service (tools / resources / prompts CRUD). */
    CAPABILITY_REGISTRY,

    /** Orchestration Layer (routing, correlation, forwarding). */
    ORCHESTRATION_LAYER,

    /** Policy Decision Point (ABAC evaluation). */
    PDP,

    /** Cross-cutting system events (startup, shutdown, etc.). */
    SYSTEM
}
