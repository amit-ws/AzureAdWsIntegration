package com.ws.mcpAccessMgmt.constants;

public enum LogType {
    AUTHZ,          // Authorization decisions
    DATA_ACCESS,    // Data read/write operations
    SYSTEM,         // Infrastructure events (e.g., policy engine downtime)
    POLICY_CHANGE   // Policy creation/modification
}
