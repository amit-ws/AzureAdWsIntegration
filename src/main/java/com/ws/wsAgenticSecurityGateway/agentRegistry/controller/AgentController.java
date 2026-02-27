package com.ws.wsAgenticSecurityGateway.agentRegistry.controller;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentSessionRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.entity.McpAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.repository.McpAuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for the Agent Discovery Registry.
 * Exposes discovered agent profiles and session history to the admin dashboard.
 */
@RestController
@RequestMapping("/api/admin/agents")
@Slf4j
public class AgentController {

    private final AgentRegistryService agentRegistryService;
    private final GatewayAgentSessionRepository sessionRepository;
    private final McpAuditLogRepository auditLogRepository;

    public AgentController(AgentRegistryService agentRegistryService,
                           GatewayAgentSessionRepository sessionRepository,
                           McpAuditLogRepository auditLogRepository) {
        this.agentRegistryService = agentRegistryService;
        this.sessionRepository = sessionRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * List all discovered agents with aggregate stats.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listAgents() {
        List<GatewayAgentEntity> agents = agentRegistryService.getAllAgents();
        List<GatewayAgentSessionEntity> connectedSessions = agentRegistryService.getConnectedSessions();

        // Pre-compute connected session counts per agent
        Map<UUID, Long> connectedCountByAgent = connectedSessions.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getAgent().getId(),
                        Collectors.counting()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (GatewayAgentEntity agent : agents) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", agent.getId());
            map.put("agentName", agent.getAgentName());
            map.put("agentVersion", agent.getAgentVersion());
            map.put("protocolVersion", agent.getProtocolVersion());
            map.put("status", agent.getStatus());
            map.put("approvalStatus", agent.getApprovalStatus());
            map.put("firstSeenAt", agent.getFirstSeenAt());
            map.put("lastSeenAt", agent.getLastSeenAt());
            map.put("totalSessions", agent.getTotalSessions());
            map.put("totalRequests", agent.getTotalRequests());
            map.put("connectedSessions",
                    connectedCountByAgent.getOrDefault(agent.getId(), 0L));
            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get detailed info for a specific agent, including capabilities.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getAgent(@PathVariable UUID id) {
        return agentRegistryService.getAgent(id)
                .map(agent -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", agent.getId());
                    map.put("agentName", agent.getAgentName());
                    map.put("agentVersion", agent.getAgentVersion());
                    map.put("protocolVersion", agent.getProtocolVersion());
                    map.put("capabilities", agent.getCapabilities());
                    map.put("status", agent.getStatus());
                    map.put("approvalStatus", agent.getApprovalStatus());
                    map.put("firstSeenAt", agent.getFirstSeenAt());
                    map.put("lastSeenAt", agent.getLastSeenAt());
                    map.put("totalSessions", agent.getTotalSessions());
                    map.put("totalRequests", agent.getTotalRequests());
                    return ResponseEntity.ok(map);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get session history for a specific agent.
     */
    @GetMapping("/{id}/sessions")
    public ResponseEntity<List<Map<String, Object>>> getAgentSessions(@PathVariable UUID id) {
        List<GatewayAgentSessionEntity> sessions = agentRegistryService.getAgentSessions(id);

        List<Map<String, Object>> result = new ArrayList<>();
        for (GatewayAgentSessionEntity session : sessions) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", session.getId());
            map.put("sessionId", session.getSessionId());
            map.put("authMethod", session.getAuthMethod());
            map.put("authIdentity", session.getAuthIdentity());
            map.put("connectedAt", session.getConnectedAt());
            map.put("disconnectedAt", session.getDisconnectedAt());
            map.put("requestCount", session.getRequestCount());
            map.put("lastRequestAt", session.getLastRequestAt());
            map.put("status", session.getStatus());
            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Summary stats for the dashboard sidebar.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        List<GatewayAgentEntity> agents = agentRegistryService.getAllAgents();
        List<GatewayAgentSessionEntity> connected = agentRegistryService.getConnectedSessions();

        long activeAgents = agents.stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .count();
        long totalRequests = agents.stream()
                .mapToLong(a -> a.getTotalRequests() != null ? a.getTotalRequests() : 0L)
                .sum();
        long pendingAgents = agents.stream()
                .filter(a -> "PENDING".equals(a.getApprovalStatus()))
                .count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAgents", agents.size());
        summary.put("activeAgents", activeAgents);
        summary.put("connectedSessions", connected.size());
        summary.put("totalRequests", totalRequests);
        summary.put("pendingApproval", pendingAgents);

        return ResponseEntity.ok(summary);
    }

    // ════════════════════════════════════════════════════════════════════
    //  LIVE SESSION MONITOR
    // ════════════════════════════════════════════════════════════════════

    /**
     * Real-time view of all currently connected agent sessions.
     * Enriched with agent identity, connected duration, and idle detection.
     */
    @GetMapping("/sessions/live")
    public ResponseEntity<Map<String, Object>> getLiveSessions() {
        List<Map<String, Object>> sessions = agentRegistryService.getLiveSessionDetails();

        // Count distinct agents online
        long distinctAgents = sessions.stream()
                .map(s -> s.get("agentId"))
                .distinct()
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connectedSessions", sessions);
        result.put("totalConnected", sessions.size());
        result.put("totalAgentsOnline", distinctAgents);

        return ResponseEntity.ok(result);
    }

    // ════════════════════════════════════════════════════════════════════
    //  SESSION TIMELINE
    // ════════════════════════════════════════════════════════════════════

    /**
     * Paginated chronological audit trail for a specific session.
     * Used to trace exactly what happened during a session.
     */
    @GetMapping("/sessions/{sessionId}/timeline")
    public ResponseEntity<Map<String, Object>> getSessionTimeline(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        // Get session metadata — search all sessions (connected + disconnected)
        Map<String, Object> sessionInfo = new LinkedHashMap<>();
        sessionRepository.findBySessionId(sessionId).ifPresentOrElse(
                sessionEntity -> {
                    sessionInfo.put("sessionId", sessionEntity.getSessionId());
                    sessionInfo.put("agentName", sessionEntity.getAgent().getAgentName());
                    sessionInfo.put("agentVersion", sessionEntity.getAgent().getAgentVersion());
                    sessionInfo.put("authMethod", sessionEntity.getAuthMethod());
                    sessionInfo.put("authIdentity", sessionEntity.getAuthIdentity());
                    sessionInfo.put("connectedAt", sessionEntity.getConnectedAt());
                    sessionInfo.put("disconnectedAt", sessionEntity.getDisconnectedAt());
                    sessionInfo.put("requestCount", sessionEntity.getRequestCount());
                    sessionInfo.put("status", sessionEntity.getStatus());
                },
                () -> sessionInfo.put("sessionId", sessionId)
        );

        // Query audit logs for this session
        Page<McpAuditLog> auditPage = auditLogRepository.findBySessionIdOrderByTimestampDesc(
                sessionId, PageRequest.of(page, size));

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (McpAuditLog entry : auditPage.getContent()) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", entry.getId());
            event.put("timestamp", entry.getTimestamp());
            event.put("eventType", entry.getEventType() != null ? entry.getEventType().name() : null);
            event.put("module", entry.getModule() != null ? entry.getModule().name() : null);
            event.put("status", entry.getStatus() != null ? entry.getStatus().name() : null);
            event.put("severity", entry.getSeverity() != null ? entry.getSeverity().name() : null);
            event.put("correlationId", entry.getCorrelationId());
            event.put("serverName", entry.getServerName());
            event.put("capabilityName", entry.getCapabilityName());
            event.put("capabilityType", entry.getCapabilityType());
            event.put("durationMs", entry.getDurationMs());
            event.put("errorCode", entry.getErrorCode());
            event.put("errorMessage", entry.getErrorMessage());
            timeline.add(event);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session", sessionInfo);
        result.put("timeline", timeline);
        result.put("totalElements", auditPage.getTotalElements());
        result.put("totalPages", auditPage.getTotalPages());
        result.put("currentPage", page);

        return ResponseEntity.ok(result);
    }

    // ════════════════════════════════════════════════════════════════════
    //  APPROVAL WORKFLOW
    // ════════════════════════════════════════════════════════════════════

    /**
     * Approve an agent — allows it to connect and use tools freely.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveAgent(@PathVariable UUID id) {
        try {
            GatewayAgentEntity agent = agentRegistryService.approveAgent(id);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "Agent '" + agent.getAgentName() + "' approved");
            result.put("approvalStatus", agent.getApprovalStatus());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Block an agent — rejected on next connection attempt.
     */
    @PostMapping("/{id}/block")
    public ResponseEntity<Map<String, Object>> blockAgent(@PathVariable UUID id) {
        try {
            GatewayAgentEntity agent = agentRegistryService.blockAgent(id);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "Agent '" + agent.getAgentName() + "' blocked");
            result.put("approvalStatus", agent.getApprovalStatus());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
