package com.ws.wsAgenticSecurityGateway.audit.error;

import lombok.Getter;

/**
 * Standard JSON-RPC / MCP error codes.
 *
 * <p>Codes are defined in the JSON-RPC 2.0 specification and extended
 * by the MCP protocol for domain-specific failures.
 *
 * @see <a href="https://www.jsonrpc.org/specification#error_object">JSON-RPC 2.0 §5.1</a>
 */
@Getter
public enum McpErrorCode {

    // ── JSON-RPC standard ─────────────────────────────────────────────
    PARSE_ERROR(-32700, "Parse error"),
    INVALID_REQUEST(-32600, "Invalid request"),
    METHOD_NOT_FOUND(-32601, "Method not found"),
    INVALID_PARAMS(-32602, "Invalid params"),
    INTERNAL_ERROR(-32603, "Internal error"),

    // ── Server error range (-32000 to -32099) ─────────────────────────
    SERVER_NOT_INITIALIZED(-32002, "Server not initialized"),
    REQUEST_TIMEOUT(-32001, "Request timed out"),

    // ── Gateway-specific (custom range) ───────────────────────────────
    CAPABILITY_NOT_FOUND(-33001, "Capability not found in registry"),
    SERVER_UNAVAILABLE(-33002, "Enterprise MCP server unavailable"),
    PDP_DENIED(-33003, "Policy decision: access denied"),
    ORCHESTRATION_FAILURE(-33004, "Orchestration routing failure"),
    REGISTRY_ERROR(-33005, "Capability registry error"),
    TRANSPORT_ERROR(-33006, "Transport layer error"),
    AGENT_BLOCKED(-33007, "Agent is blocked by admin");

    private final int code;
    private final String message;

    McpErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
