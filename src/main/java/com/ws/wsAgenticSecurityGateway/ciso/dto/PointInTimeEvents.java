package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The forensic drill-down that complements the point-in-time overview: the actual authorization decisions within a
 * window, newest first, paginated and optionally filtered by agent and/or outcome (ALLOW/DENY). This is the
 * line-by-line "what actually happened" evidence behind the overview's counts. Read-only, tenant-scoped.
 */
public record PointInTimeEvents(
        String tenant,
        LocalDateTime generatedAt,
        LocalDate windowFrom,
        LocalDate windowTo,
        String agentFilter,
        String decisionFilter,
        int page,
        int size,
        long totalEvents,
        int returned,
        boolean hasNext,
        List<DecisionEvent> events,
        List<String> notes) {}
