package com.ws.wsAgenticSecurity.audit.constants;

/**
 * Severity level attached to every audit record.
 * Used for filtering, alerting, and retention policies.
 */
public enum AuditSeverity {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    CRITICAL
}
