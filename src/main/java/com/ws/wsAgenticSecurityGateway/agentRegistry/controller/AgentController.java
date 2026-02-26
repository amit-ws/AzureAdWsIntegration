package com.ws.wsAgenticSecurityGateway.agentRegistry.controller;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import lombok.extern.slf4j.Slf4j;
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

    public AgentController(AgentRegistryService agentRegistryService) {
        this.agentRegistryService = agentRegistryService;
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

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAgents", agents.size());
        summary.put("activeAgents", activeAgents);
        summary.put("connectedSessions", connected.size());
        summary.put("totalRequests", totalRequests);

        return ResponseEntity.ok(summary);
    }
}
