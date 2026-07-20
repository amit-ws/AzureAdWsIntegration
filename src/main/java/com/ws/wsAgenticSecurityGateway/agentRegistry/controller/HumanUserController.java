package com.ws.wsAgenticSecurityGateway.agentRegistry.controller;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayHumanUserEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.HumanUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/human-users")
@Slf4j
public class HumanUserController {

    private final HumanUserService humanUserService;

    public HumanUserController(HumanUserService humanUserService) {
        this.humanUserService = humanUserService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listHumanUsers() {
        log.info("GET /api/admin/human-users");
        List<GatewayHumanUserEntity> humans = humanUserService.findAll();
        List<Map<String, Object>> result = humans.stream()
                .map(this::toListView)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getHumanUser(@PathVariable UUID id) {
        return humanUserService.findById(id)
                .map(human -> ResponseEntity.ok(toDetailView(human)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/sessions")
    public ResponseEntity<List<Map<String, Object>>> getHumanSessions(@PathVariable UUID id) {
        if (!humanUserService.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<GatewayAgentSessionEntity> sessions = humanUserService.getHumanSessions(id);

        List<Map<String, Object>> result = sessions.stream().map(s -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", s.getId());
            map.put("sessionId", s.getSessionId());
            map.put("agentName", s.getAgent() != null ? s.getAgent().getAgentName() : null);
            map.put("agentVersion", s.getAgent() != null ? s.getAgent().getAgentVersion() : null);
            map.put("authMethod", s.getAuthMethod());
            map.put("tokenType", s.getTokenType());
            map.put("connectedAt", s.getConnectedAt());
            map.put("disconnectedAt", s.getDisconnectedAt());
            map.put("requestCount", s.getRequestCount());
            map.put("lastRequestAt", s.getLastRequestAt());
            map.put("status", s.getStatus());
            map.put("ipAddress", s.getIpAddress());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/agents")
    public ResponseEntity<List<Map<String, Object>>> getHumanAgents(@PathVariable UUID id) {
        if (!humanUserService.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> result = humanUserService.getHumanAgents(id);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        return ResponseEntity.ok(humanUserService.getSummary());
    }

    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> searchHumans(
            @RequestParam("q") String query) {
        List<GatewayHumanUserEntity> results = humanUserService.search(query);
        return ResponseEntity.ok(results.stream()
                .map(this::toListView)
                .collect(Collectors.toList()));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveHuman(@PathVariable UUID id,
                                                              HttpServletRequest request) {
        try {
            GatewayHumanUserEntity human = humanUserService.approveHumanUser(
                    id, resolveAdminActor(request), resolveAdminIp(request));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "Human user '" + human.getPreferredUsername() + "' approved");
            result.put("userStatus", human.getStatus());
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/block")
    public ResponseEntity<Map<String, Object>> blockHuman(
            @PathVariable UUID id, @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        try {
            String reason = body != null ? body.get("reason") : null;
            HumanUserService.BlockResult blockResult = humanUserService.blockHumanUser(
                    id, reason, resolveAdminActor(request), resolveAdminIp(request));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "Human user '" + blockResult.human().getPreferredUsername() + "' blocked");
            result.put("userStatus", blockResult.human().getStatus());
            result.put("sessionsTerminated", blockResult.sessionsTerminated());
            result.put("affectedAgents", blockResult.affectedAgents().size());
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/unblock")
    public ResponseEntity<Map<String, Object>> unblockHuman(@PathVariable UUID id,
                                                             HttpServletRequest request) {
        try {
            GatewayHumanUserEntity human = humanUserService.unblockHumanUser(
                    id, resolveAdminActor(request), resolveAdminIp(request));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "Human user '" + human.getPreferredUsername() + "' unblocked");
            result.put("userStatus", human.getStatus());
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private String resolveAdminActor(HttpServletRequest request) {
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

    @GetMapping("/{id}/lineage")
    public ResponseEntity<Map<String, Object>> getSessionLineage(@PathVariable UUID id) {
        Optional<GatewayHumanUserEntity> opt = humanUserService.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        GatewayHumanUserEntity human = opt.get();
        Map<String, Object> lineage = humanUserService.getSessionLineage(human);
        lineage.put("humanUser", toListView(human));

        return ResponseEntity.ok(lineage);
    }

    @GetMapping("/active-now")
    public ResponseEntity<Map<String, Object>> getActiveNow() {
        return ResponseEntity.ok(humanUserService.getActiveNow());
    }

    @GetMapping("/{id}/risk-assessment")
    public ResponseEntity<Map<String, Object>> getRiskAssessment(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "24") int hours) {

        Optional<GatewayHumanUserEntity> opt = humanUserService.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        GatewayHumanUserEntity human = opt.get();
        Map<String, Object> assessment = humanUserService.getRiskAssessment(human, hours);
        assessment.put("humanUser", toListView(human));

        return ResponseEntity.ok(assessment);
    }

    @GetMapping("/{id}/analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "24") int hours) {

        Optional<GatewayHumanUserEntity> opt = humanUserService.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        GatewayHumanUserEntity human = opt.get();
        Map<String, Object> analytics = humanUserService.getUsageAnalytics(human, hours);
        analytics.put("humanUser", toListView(human));

        return ResponseEntity.ok(analytics);
    }

    private Map<String, Object> toListView(GatewayHumanUserEntity h) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", h.getId());
        map.put("preferredUsername", h.getPreferredUsername());
        map.put("email", h.getEmail());
        map.put("fullName", h.getFullName());
        map.put("idpIssuer", h.getIdpIssuer());
        map.put("emailVerified", h.getEmailVerified());
        map.put("realmRoles", h.getRealmRoles());
        map.put("clientRoles", h.getClientRoles());
        map.put("totalSessions", h.getTotalSessions());
        map.put("totalRequests", h.getTotalRequests());
        map.put("firstSeenAt", h.getFirstSeenAt());
        map.put("lastSeenAt", h.getLastSeenAt());
        map.put("lastIpAddress", h.getLastIpAddress());
        map.put("status", h.getStatus());
        return map;
    }

    private Map<String, Object> toDetailView(GatewayHumanUserEntity h) {
        Map<String, Object> map = toListView(h);
        map.put("idpSubject", h.getIdpSubject());
        map.put("givenName", h.getGivenName());
        map.put("familyName", h.getFamilyName());
        map.put("customClaims", h.getCustomClaims());
        map.put("lastJwtClaims", h.getLastJwtClaims());
        map.put("blockedReason", h.getBlockedReason());
        map.put("blockedAt", h.getBlockedAt());
        return map;
    }
}
