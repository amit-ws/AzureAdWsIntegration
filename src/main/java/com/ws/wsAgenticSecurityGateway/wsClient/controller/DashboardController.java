package com.ws.wsAgenticSecurityGateway.wsClient.controller;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.HumanUserService;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.service.AuditQueryService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.orchestration.InFlightRequestRegistry;
import com.ws.wsAgenticSecurityGateway.pdp.service.CustomAttributeService;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyLlmService;
import com.ws.wsAgenticSecurityGateway.pdp.service.PolicyService;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSession;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Dashboard overview controller for the Admin UI.
 *
 * <p>Provides gateway-wide summary statistics, health overview,
 * and in-flight request monitoring for the admin dashboard.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@Slf4j
public class DashboardController {

    private final McpSessionManager sessionManager;
    private final CapabilityRegistryService registryService;
    private final AuditQueryService auditQueryService;
    private final InFlightRequestRegistry inFlightRegistry;
    private final AgentRegistryService agentRegistryService;
    private final PolicyService policyService;
    private final CustomAttributeService customAttributeService;
    private final PolicyLlmService policyLlmService;
    private final HumanUserService humanUserService;

    public DashboardController(McpSessionManager sessionManager,
                               CapabilityRegistryService registryService,
                               AuditQueryService auditQueryService,
                               InFlightRequestRegistry inFlightRegistry,
                               AgentRegistryService agentRegistryService,
                               PolicyService policyService,
                               CustomAttributeService customAttributeService,
                               PolicyLlmService policyLlmService,
                               HumanUserService humanUserService) {
        this.sessionManager = sessionManager;
        this.registryService = registryService;
        this.auditQueryService = auditQueryService;
        this.inFlightRegistry = inFlightRegistry;
        this.agentRegistryService = agentRegistryService;
        this.policyService = policyService;
        this.customAttributeService = customAttributeService;
        this.policyLlmService = policyLlmService;
        this.humanUserService = humanUserService;
    }

    /**
     * GET /api/admin/dashboard/summary
     * Gateway-wide overview for the dashboard sidebar.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        log.info("📊 GET /api/admin/dashboard/summary");

        Map<String, Object> summary = new LinkedHashMap<>();

        // Server counts
        var allSessions = sessionManager.getAllSessions();
        long activeServers = allSessions.values().stream()
                .filter(McpSession::isActive)
                .count();

        summary.put("totalServers", allSessions.size());
        summary.put("activeServers", activeServers);
        summary.put("serverNames", sessionManager.getServerNames());

        // Capability counts
        summary.put("totalTools", registryService.getToolDescriptors().size());
        summary.put("totalResources", registryService.getResourceDescriptors().size());
        summary.put("totalPrompts", registryService.getPromptDescriptors().size());
        summary.put("totalCapabilities", registryService.getTotalCapabilityCount());

        // Recent audit activity (last 24 hours)
        LocalDateTime last24h = LocalDateTime.now().minusHours(24);
        summary.put("recentEventCount", auditQueryService.countRecentEvents(last24h));
        summary.put("recentErrorCount",
                auditQueryService.countRecentErrors(last24h));

        // In-flight count for sidebar badge
        summary.put("inFlightCount", inFlightRegistry.getActiveCount());

        // Agent registry stats
        summary.put("totalAgents", agentRegistryService.getAllAgents().size());
        summary.put("connectedAgentSessions", agentRegistryService.getConnectedSessions().size());

        // Human user stats
        summary.put("totalHumanUsers", humanUserService.count());

        return ResponseEntity.ok(summary);
    }

    /**
     * GET /api/admin/dashboard/in-flight
     * Live in-flight request monitoring for the admin dashboard.
     */
    @GetMapping("/in-flight")
    public ResponseEntity<Map<String, Object>> getInFlight() {
        Collection<InFlightRequestRegistry.InFlightEntry> entries = inFlightRegistry.getActiveEntries();
        Instant now = Instant.now();

        List<Map<String, Object>> entryList = new ArrayList<>();
        for (InFlightRequestRegistry.InFlightEntry entry : entries) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("correlationId", entry.getCorrelationId());
            map.put("publicName", entry.getPublicName());
            map.put("serverName", entry.getServerName());
            map.put("originalToolName", entry.getOriginalToolName());
            map.put("agentSessionId", entry.getAgentSessionId());
            map.put("clientSessionId", entry.getClientSessionId());
            map.put("requestId", entry.getRequestId());
            map.put("startedAt", entry.getStartedAt().toString());
            map.put("elapsedMs", now.toEpochMilli() - entry.getStartedAt().toEpochMilli());
            map.put("agentName", entry.getAgentName());
            map.put("agentVersion", entry.getAgentVersion());
            map.put("tokenMode", entry.getTokenMode());
            map.put("requestArgs", entry.getRequestArgs());
            map.put("responseSummary", entry.getResponseSummary());
            map.put("responseContentCount", entry.getResponseContentCount());
            map.put("responseContentTypes", entry.getResponseContentTypes());
            map.put("registryLookupMs", entry.getRegistryLookupMs());
            map.put("forwardCallMs", entry.getForwardCallMs());
            entryList.add(map);
        }

        // Recent completed/failed history
        List<InFlightRequestRegistry.CompletedEntry> history = inFlightRegistry.getRecentHistory();
        List<Map<String, Object>> historyList = new ArrayList<>();
        for (InFlightRequestRegistry.CompletedEntry h : history) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("correlationId", h.getCorrelationId());
            map.put("publicName", h.getPublicName());
            map.put("serverName", h.getServerName());
            map.put("originalToolName", h.getOriginalToolName());
            map.put("agentSessionId", h.getAgentSessionId());
            map.put("clientSessionId", h.getClientSessionId());
            map.put("requestId", h.getRequestId());
            map.put("startedAt", h.getStartedAt().toString());
            map.put("completedAt", h.getCompletedAt().toString());
            map.put("durationMs", h.getDurationMs());
            map.put("status", h.getStatus());
            map.put("errorMessage", h.getErrorMessage());
            map.put("agentName", h.getAgentName());
            map.put("agentVersion", h.getAgentVersion());
            map.put("tokenMode", h.getTokenMode());
            map.put("requestArgs", h.getRequestArgs());
            map.put("responseSummary", h.getResponseSummary());
            map.put("responseContentCount", h.getResponseContentCount());
            map.put("responseContentTypes", h.getResponseContentTypes());
            map.put("registryLookupMs", h.getRegistryLookupMs());
            map.put("forwardCallMs", h.getForwardCallMs());
            historyList.add(map);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeCount", inFlightRegistry.getActiveCount());
        result.put("entries", entryList);
        result.put("recentCount", historyList.size());
        result.put("recentHistory", historyList);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/admin/dashboard/health
     * Comprehensive health overview for the admin dashboard landing page.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        log.info("📊 GET /api/admin/dashboard/health");

        Map<String, Object> health = new LinkedHashMap<>();
        LocalDateTime last24h = LocalDateTime.now().minusHours(24);
        LocalDateTime last5min = LocalDateTime.now().minusMinutes(5);

        // ── Server status ────────────────────────────────────────────
        var allSessions = sessionManager.getAllSessions();
        long activeCount = allSessions.values().stream().filter(McpSession::isActive).count();
        health.put("totalServers", allSessions.size());
        health.put("activeServers", activeCount);

        // Per-server details with last activity
        Map<String, Timestamp> lastActivityMap = new HashMap<>();
        try {
            List<Object[]> lastActivityRows = auditQueryService.findLastActivityPerServer();
            for (Object[] row : lastActivityRows) {
                String serverName = (String) row[0];
                Timestamp lastActivity = (Timestamp) row[1];
                lastActivityMap.put(serverName, lastActivity);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch last activity per server: {}", e.getMessage());
        }

        List<Map<String, Object>> serverDetails = new ArrayList<>();
        for (Map.Entry<String, McpSession> entry : allSessions.entrySet()) {
            Map<String, Object> server = new LinkedHashMap<>();
            McpSession session = entry.getValue();
            server.put("name", entry.getKey());
            server.put("active", session.isActive());
            server.put("connectedAt", session.getConnectedAt() != null ? session.getConnectedAt().toString() : null);
            server.put("toolCount", session.getTools() != null ? session.getTools().size() : 0);
            server.put("resourceCount", session.getResources() != null ? session.getResources().size() : 0);
            server.put("promptCount", session.getPrompts() != null ? session.getPrompts().size() : 0);
            Timestamp lastActivity = lastActivityMap.get(entry.getKey());
            server.put("lastActivity", lastActivity != null ? lastActivity.toInstant().toString() : null);
            serverDetails.add(server);
        }
        health.put("servers", serverDetails);

        // ── Capability counts ────────────────────────────────────────
        health.put("totalTools", registryService.getToolDescriptors().size());
        health.put("totalResources", registryService.getResourceDescriptors().size());
        health.put("totalPrompts", registryService.getPromptDescriptors().size());

        // ── Traffic metrics ──────────────────────────────────────────
        long recentEvents = auditQueryService.countRecentEvents(last24h);
        long recentErrors = auditQueryService.countRecentErrors(last24h);
        double successRate = recentEvents > 0 ? Math.round((1.0 - (double) recentErrors / recentEvents) * 10000.0) / 100.0 : 100.0;
        long last5minCount = auditQueryService.countRecentEvents(last5min);
        double requestsPerMinute = Math.round(last5minCount / 5.0 * 100.0) / 100.0;

        health.put("recentEventCount", recentEvents);
        health.put("recentErrorCount", recentErrors);
        health.put("successRate", successRate);
        health.put("requestsPerMinute", requestsPerMinute);

        // ── Latency percentiles ──────────────────────────────────────
        Double avgDuration = auditQueryService.findAverageDurationSince(last24h);
        health.put("avgDurationMs", avgDuration != null ? Math.round(avgDuration * 100.0) / 100.0 : 0);

        Map<String, Object> latency = new LinkedHashMap<>();
        try {
            List<Object[]> percentiles = auditQueryService.findLatencyPercentilesSince(last24h);
            if (percentiles != null && !percentiles.isEmpty()) {
                Object[] row = percentiles.get(0);
                latency.put("p50", row[0] != null ? Math.round(((Number) row[0]).doubleValue() * 100.0) / 100.0 : 0);
                latency.put("p95", row[1] != null ? Math.round(((Number) row[1]).doubleValue() * 100.0) / 100.0 : 0);
                latency.put("p99", row[2] != null ? Math.round(((Number) row[2]).doubleValue() * 100.0) / 100.0 : 0);
            } else {
                latency.put("p50", 0);
                latency.put("p95", 0);
                latency.put("p99", 0);
            }
        } catch (Exception e) {
            log.warn("Failed to compute latency percentiles: {}", e.getMessage());
            latency.put("p50", 0);
            latency.put("p95", 0);
            latency.put("p99", 0);
        }
        health.put("latency", latency);

        // ── In-flight ────────────────────────────────────────────────
        health.put("inFlightCount", inFlightRegistry.getActiveCount());

        return ResponseEntity.ok(health);
    }

    /**
     * GET /api/admin/dashboard/pdp
     * PDP (Policy Decision Point) analytics for the admin dashboard.
     *
     * <p>Returns policy stats, custom attribute stats, LLM availability,
     * and PDP-related audit event count for the last 24 hours.
     */
    @GetMapping("/pdp")
    public ResponseEntity<Map<String, Object>> getPdpDashboard() {
        log.info("📊 GET /api/admin/dashboard/pdp");

        Map<String, Object> pdp = new LinkedHashMap<>();

        // ── Policy stats ─────────────────────────────────────────────
        pdp.put("policies", policyService.getStats());

        // ── Custom attribute stats ───────────────────────────────────
        pdp.put("customAttributes", customAttributeService.getStats());

        // ── Activity & LLM availability ──────────────────────────────
        Map<String, Object> activity = new LinkedHashMap<>();
        LocalDateTime last24h = LocalDateTime.now().minusHours(24);
        activity.put("pdpEventsLast24h",
                auditQueryService.countByModuleAndTimestampAfter(AuditModule.PDP, last24h));
        activity.put("llmAvailable", policyLlmService.isLlmAvailable());
        pdp.put("activity", activity);

        return ResponseEntity.ok(pdp);
    }
}
