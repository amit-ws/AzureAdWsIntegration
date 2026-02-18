package com.ws.mcpAccessMgmt.constants;

public enum LogLevel {
    ERROR,     // Critical failures (e.g., policy engine crash)
    WARN,      // Non-critical issues (e.g., slow policy evaluation)
    INFO,      // Standard operational logs (e.g., allowed access)
    DEBUG      // Detailed tracing (disabled in production)
}
