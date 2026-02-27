package com.ws.wsAgenticSecurityGateway.agentRegistry.controller;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentAnalyticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for per-agent usage analytics.
 * Provides aggregated audit data for a specific agent over a configurable time window.
 */
@RestController
@RequestMapping("/api/admin/agents")
@Slf4j
public class AgentAnalyticsController {

    private final AgentAnalyticsService analyticsService;

    public AgentAnalyticsController(AgentAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * Get usage analytics for a specific agent.
     *
     * @param id    agent UUID
     * @param hours lookback window in hours (default 24, max 168 = 7 days)
     */
    @GetMapping("/{id}/analytics")
    public ResponseEntity<Map<String, Object>> getAgentAnalytics(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "24") int hours) {

        hours = Math.min(Math.max(hours, 1), 168); // Clamp 1h to 7d

        try {
            Map<String, Object> analytics = analyticsService.getAgentAnalytics(id, hours);
            return ResponseEntity.ok(analytics);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
