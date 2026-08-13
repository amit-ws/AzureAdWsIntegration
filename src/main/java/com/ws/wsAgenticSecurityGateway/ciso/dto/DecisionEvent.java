package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.time.LocalDateTime;

/**
 * One recorded authorization decision — the atomic unit of forensic evidence: at {@code at}, {@code agent} asked
 * to perform {@code action} on {@code resource} → {@code decision} (ALLOW/DENY), decided by {@code policy}, on
 * behalf of the verified {@code human} (null for a system/anonymous caller). Shared by the point-in-time overview
 * sample and the paginated events drill-down.
 */
public record DecisionEvent(
        LocalDateTime at,
        String agent,
        String human,
        String action,
        String resource,
        String decision,
        String policy,
        String correlationId) {}
