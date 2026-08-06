package com.ws.wsAgenticSecurityGateway.audit.error;

import lombok.Getter;

@Getter
public enum GatewayErrorCode {

    PARSE_ERROR(-32700, "Parse error"),
    INVALID_REQUEST(-32600, "Invalid request"),
    METHOD_NOT_FOUND(-32601, "Method not found"),
    INVALID_PARAMS(-32602, "Invalid params"),
    INTERNAL_ERROR(-32603, "Internal error"),

    SERVER_NOT_INITIALIZED(-32002, "Server not initialized"),
    REQUEST_TIMEOUT(-32001, "Request timed out"),

    CAPABILITY_NOT_FOUND(-33001, "Capability not found in registry"),
    SERVER_UNAVAILABLE(-33002, "Enterprise MCP server unavailable"),
    PDP_DENIED(-33003, "Policy decision: access denied"),
    ORCHESTRATION_FAILURE(-33004, "Orchestration routing failure"),
    REGISTRY_ERROR(-33005, "Capability registry error"),
    TRANSPORT_ERROR(-33006, "Transport layer error"),
    AGENT_BLOCKED(-33007, "Agent is blocked by admin"),
    CAPABILITY_NOT_ALLOWED(-33008, "Capability not permitted for this agent"),
    HUMAN_BLOCKED(-33009, "Human user is blocked by admin"),
    NHI_BLOCKED(-33010, "Service identity is blocked by admin"),
    AGENT_PENDING_APPROVAL(-33011, "Agent is pending admin approval"),
    AGENT_DEPROVISIONED(-33012, "Agent has been deprovisioned by admin"),
    HUMAN_PENDING_APPROVAL(-33013, "Human user is pending admin approval"),
    NHI_PENDING_APPROVAL(-33014, "Service identity is pending admin approval"),
    TOKEN_REVOKED(-33015, "Delegation token or session has been revoked by admin"),
    SENDER_CONSTRAINT_VIOLATION(-33016, "Delegation token is bound to a different agent — presenter identity does not match its cnf");

    private final int code;
    private final String message;

    GatewayErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
