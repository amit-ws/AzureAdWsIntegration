package com.ws.wsAgenticSecurityGateway.agentRegistry.controller;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayHumanUserEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayNhiEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.dto.AgentDto;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.HumanUserService;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.NhiService;
import com.ws.wsAgenticSecurityGateway.audit.entity.GatewayAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.service.AuditQueryService;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/agents")
@Slf4j
public class AgentController {

    private final AgentRegistryService agentRegistryService;
    private final AuditQueryService auditQueryService;
    private final HumanUserService humanUserService;
    private final NhiService nhiService;

    public AgentController(AgentRegistryService agentRegistryService,
                           AuditQueryService auditQueryService,
                           HumanUserService humanUserService,
                           NhiService nhiService) {
        this.agentRegistryService = agentRegistryService;
        this.auditQueryService = auditQueryService;
        this.humanUserService = humanUserService;
        this.nhiService = nhiService;
    }

    // Unified Agent Model (#2/#3): one canonical row per agent enriched with protocols + identity +
    // capabilities. The response is a superset of the old shape, so existing consumers keep working.
    @GetMapping
    public ResponseEntity<List<AgentDto>> listAgents() {
        return ResponseEntity.ok(agentRegistryService.getAgentViews());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentDto> getAgent(@PathVariable UUID id) {
        return agentRegistryService.getAgentView(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

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
            map.put("tokenType", session.getTokenType());
            map.put("humanUserId", session.getHumanUserId());
            map.put("nhiId", session.getNhiId());
            map.put("ipAddress", session.getIpAddress());

            String calledByName = null;
            String callerType = session.getTokenType();
            if (session.getHumanUserId() != null) {
                humanUserService.findById(session.getHumanUserId())
                        .ifPresent(h -> map.put("calledByName", h.getPreferredUsername() != null
                                ? h.getPreferredUsername() : h.getFullName()));
            } else if (session.getNhiId() != null) {
                nhiService.findById(session.getNhiId())
                        .ifPresent(n -> map.put("calledByName", n.getServiceName() != null
                                ? n.getServiceName() : n.getClientId()));
            }
            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

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

    @GetMapping("/sessions/live")
    public ResponseEntity<Map<String, Object>> getLiveSessions() {
        List<Map<String, Object>> sessions = agentRegistryService.getLiveSessionDetails();

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

    @GetMapping("/sessions/{sessionId}/timeline")
    public ResponseEntity<Map<String, Object>> getSessionTimeline(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Map<String, Object> sessionInfo = new LinkedHashMap<>();
        agentRegistryService.findSessionBySessionId(sessionId).ifPresentOrElse(
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

        Page<GatewayAuditLog> auditPage = auditQueryService.getSessionTimeline(
                sessionId, PageRequest.of(page, size));

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (GatewayAuditLog entry : auditPage.getContent()) {
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

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveAgent(@PathVariable UUID id,
                                                            HttpServletRequest request) {
        try {
            GatewayAgentEntity agent = agentRegistryService.approveAgent(
                    id, resolveAdminActor(request), resolveAdminIp(request));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "Agent '" + agent.getAgentName() + "' approved");
            result.put("approvalStatus", agent.getApprovalStatus());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/block")
    public ResponseEntity<Map<String, Object>> blockAgent(@PathVariable UUID id,
                                                          HttpServletRequest request) {
        try {
            AgentRegistryService.AgentBlockResult blockResult = agentRegistryService.blockAgent(
                    id, resolveAdminActor(request), resolveAdminIp(request));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "Agent '" + blockResult.agent().getAgentName() + "' blocked");
            result.put("approvalStatus", blockResult.agent().getApprovalStatus());
            result.put("sessionsTerminated", blockResult.sessionsTerminated());
            result.put("affectedHumanUsers", blockResult.affectedHumanUsers().size());
            result.put("affectedNhis", blockResult.affectedNhis().size());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/deprovision")
    public ResponseEntity<Map<String, Object>> deprovisionAgent(@PathVariable UUID id,
                                                                HttpServletRequest request) {
        try {
            AgentRegistryService.AgentBlockResult result = agentRegistryService.deprovisionAgent(
                    id, resolveAdminActor(request), resolveAdminIp(request));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("message", "Agent '" + result.agent().getAgentName() + "' deprovisioned");
            body.put("lifecycleStatus", result.agent().getStatus());
            body.put("sessionsTerminated", result.sessionsTerminated());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private String resolveAdminActor(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return auth.getName();
        }

        String headerUser = request.getHeader("X-Admin-User");
        if (headerUser != null && !headerUser.isBlank()) {
            return headerUser;
        }

        return "anonymous";
    }

    private String resolveAdminIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] ips = forwarded.split(",");
            if (ips.length > 0 && !ips[0].trim().isBlank()) {
                return ips[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
