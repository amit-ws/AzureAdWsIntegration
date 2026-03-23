package com.ws.wsAgenticSecurityGateway.agentRegistry.controller;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayNhiEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.NhiService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for Non-Human Identity (NHI) management and enterprise analytics.
 *
 * <p>Provides:
 * <ul>
 *   <li>Core CRUD — list, detail, search, block/unblock</li>
 *   <li>Session Lineage — "Which NHI authorized which AI actions?"</li>
 *   <li>Who's Active Now — real-time NHI activity for SOC teams</li>
 *   <li>Risk Assessment — behavioral anomaly scoring</li>
 *   <li>Usage Analytics — per-NHI consumption breakdown</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/nhis")
@Slf4j
public class NhiController {

    private final NhiService nhiService;

    public NhiController(NhiService nhiService) {
        this.nhiService = nhiService;
    }

    // ════════════════════════════════════════════════════════════════════
    //  CORE CRUD
    // ════════════════════════════════════════════════════════════════════

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listNhis() {
        log.info("GET /api/admin/nhis");
        List<GatewayNhiEntity> nhis = nhiService.findAll();
        List<Map<String, Object>> result = nhis.stream()
                .map(this::toListView)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getNhi(@PathVariable UUID id) {
        return nhiService.findById(id)
                .map(nhi -> ResponseEntity.ok(toDetailView(nhi)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/sessions")
    public ResponseEntity<List<Map<String, Object>>> getNhiSessions(@PathVariable UUID id) {
        if (!nhiService.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<GatewayAgentSessionEntity> sessions = nhiService.getNhiSessions(id);

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
    public ResponseEntity<List<Map<String, Object>>> getNhiAgents(@PathVariable UUID id) {
        if (!nhiService.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> result = nhiService.getNhiAgents(id);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        return ResponseEntity.ok(nhiService.getSummary());
    }

    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> searchNhis(
            @RequestParam("q") String query) {
        List<GatewayNhiEntity> results = nhiService.search(query);
        return ResponseEntity.ok(results.stream()
                .map(this::toListView)
                .collect(Collectors.toList()));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveNhi(@PathVariable UUID id,
                                                            HttpServletRequest request) {
        try {
            GatewayNhiEntity nhi = nhiService.approveNhi(
                    id, resolveAdminActor(request), resolveAdminIp(request));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "NHI '" + nhi.getServiceName() + "' approved");
            result.put("nhiStatus", nhi.getStatus());
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/block")
    public ResponseEntity<Map<String, Object>> blockNhi(
            @PathVariable UUID id, @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        try {
            String reason = body != null ? body.get("reason") : null;
            NhiService.BlockResult blockResult = nhiService.blockNhi(
                    id, reason, resolveAdminActor(request), resolveAdminIp(request));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "NHI '" + blockResult.nhi().getServiceName() + "' blocked");
            result.put("nhiStatus", blockResult.nhi().getStatus());
            result.put("sessionsTerminated", blockResult.sessionsTerminated());
            result.put("affectedAgents", blockResult.affectedAgents().size());
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/unblock")
    public ResponseEntity<Map<String, Object>> unblockNhi(@PathVariable UUID id,
                                                           HttpServletRequest request) {
        try {
            GatewayNhiEntity nhi = nhiService.unblockNhi(
                    id, resolveAdminActor(request), resolveAdminIp(request));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "NHI '" + nhi.getServiceName() + "' unblocked");
            result.put("nhiStatus", nhi.getStatus());
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

    // ════════════════════════════════════════════════════════════════════
    //  NHI-AGENT SESSION LINEAGE
    // ════════════════════════════════════════════════════════════════════

    @GetMapping("/{id}/lineage")
    public ResponseEntity<Map<String, Object>> getSessionLineage(@PathVariable UUID id) {
        Optional<GatewayNhiEntity> opt = nhiService.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        GatewayNhiEntity nhi = opt.get();
        Map<String, Object> lineage = nhiService.getSessionLineage(nhi);
        lineage.put("nhi", toListView(nhi));

        return ResponseEntity.ok(lineage);
    }

    // ════════════════════════════════════════════════════════════════════
    //  WHO'S ACTIVE NOW
    // ════════════════════════════════════════════════════════════════════

    @GetMapping("/active-now")
    public ResponseEntity<Map<String, Object>> getActiveNow() {
        return ResponseEntity.ok(nhiService.getActiveNow());
    }

    // ════════════════════════════════════════════════════════════════════
    //  NHI RISK ASSESSMENT
    // ════════════════════════════════════════════════════════════════════

    @GetMapping("/{id}/risk-assessment")
    public ResponseEntity<Map<String, Object>> getRiskAssessment(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "24") int hours) {

        Optional<GatewayNhiEntity> opt = nhiService.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        GatewayNhiEntity nhi = opt.get();
        Map<String, Object> assessment = nhiService.getRiskAssessment(nhi, hours);
        assessment.put("nhi", toListView(nhi));

        return ResponseEntity.ok(assessment);
    }

    // ════════════════════════════════════════════════════════════════════
    //  USAGE ANALYTICS PER NHI
    // ════════════════════════════════════════════════════════════════════

    @GetMapping("/{id}/analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "24") int hours) {

        Optional<GatewayNhiEntity> opt = nhiService.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        GatewayNhiEntity nhi = opt.get();
        Map<String, Object> analytics = nhiService.getUsageAnalytics(nhi, hours);
        analytics.put("nhi", toListView(nhi));

        return ResponseEntity.ok(analytics);
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    private Map<String, Object> toListView(GatewayNhiEntity n) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", n.getId());
        map.put("serviceName", n.getServiceName());
        map.put("clientId", n.getClientId());
        map.put("idpIssuer", n.getIdpIssuer());
        map.put("realmRoles", n.getRealmRoles());
        map.put("clientRoles", n.getClientRoles());
        map.put("totalSessions", n.getTotalSessions());
        map.put("totalRequests", n.getTotalRequests());
        map.put("firstSeenAt", n.getFirstSeenAt());
        map.put("lastSeenAt", n.getLastSeenAt());
        map.put("lastIpAddress", n.getLastIpAddress());
        map.put("status", n.getStatus());
        return map;
    }

    private Map<String, Object> toDetailView(GatewayNhiEntity n) {
        Map<String, Object> map = toListView(n);
        map.put("idpSubject", n.getIdpSubject());
        map.put("customClaims", n.getCustomClaims());
        map.put("lastJwtClaims", n.getLastJwtClaims());
        map.put("blockedReason", n.getBlockedReason());
        map.put("blockedAt", n.getBlockedAt());
        map.put("description", n.getDescription());
        return map;
    }
}
