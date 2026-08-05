package com.ws.wsAgenticSecurityGateway.protocol.a2a.web;

import com.ws.wsAgenticSecurityGateway.protocol.a2a.capability.A2aAgentIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin API for the downstream A2A agents the gateway fronts: ingest an agent by URL (fetch its Agent Card,
 * register its skills), list the registered agents, and remove one. Mirrors the MCP server-config admin API's
 * shape and, like it, sits behind the permissive admin plane (tenant via the {@code X-WS-Tenant} header).
 */
@RestController
@RequestMapping("/api/admin/a2a/agents")
@Slf4j
public class A2aAdminController {

    private final A2aAgentIngestionService ingestionService;

    public A2aAdminController(A2aAgentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /** Ingest (or refresh) a downstream agent. Body: {@code {name, baseUrl}}. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        String baseUrl = str(body.get("baseUrl"));
        if (name == null || baseUrl == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "name and baseUrl are required"));
        }
        log.info("POST /api/admin/a2a/agents (name={}, baseUrl={})", name, baseUrl);
        try {
            A2aAgentIngestionService.IngestResult result = ingestionService.ingest(name, baseUrl);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ingested", true);
            out.put("agentName", result.agentName());
            out.put("cardName", result.cardName());
            out.put("skillsRegistered", result.skillsRegistered());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("A2A ingest failed for '{}' at {}: {}", name, baseUrl, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** The registered downstream agents for the tenant. */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Map<String, Object>> out = ingestionService.list().stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", a.getName());
                    m.put("baseUrl", a.getBaseUrl());
                    m.put("createdAt", a.getCreatedAt());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(out);
    }

    /** Remove a downstream agent (drops its skills, endpoint, and persisted config). */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable String name) {
        log.info("DELETE /api/admin/a2a/agents/{}", name);
        ingestionService.remove(name);
        return ResponseEntity.ok(Map.of("removed", true, "agentName", name));
    }

    private static String str(Object value) {
        return value != null && !String.valueOf(value).isBlank() ? String.valueOf(value) : null;
    }
}
