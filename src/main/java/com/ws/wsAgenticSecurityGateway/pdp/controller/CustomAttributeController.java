package com.ws.wsAgenticSecurityGateway.pdp.controller;

import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayCustomAttributeEntity;
import com.ws.wsAgenticSecurityGateway.pdp.service.CustomAttributeService;
import com.ws.wsAgenticSecurityGateway.pdp.service.CustomAttributeService.AttributeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/custom-attributes")
@Slf4j
public class CustomAttributeController {

    private final CustomAttributeService customAttributeService;

    public CustomAttributeController(CustomAttributeService customAttributeService) {
        this.customAttributeService = customAttributeService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listAll() {
        List<GatewayCustomAttributeEntity> attrs = customAttributeService.getAll();
        List<Map<String, Object>> result = attrs.stream()
                .map(this::toMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable UUID id) {
        return customAttributeService.getById(id)
                .map(a -> ResponseEntity.ok(toMap(a)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody GatewayCustomAttributeEntity attr) {
        AttributeResult result = customAttributeService.create(attr);
        if (result.success()) {
            return ResponseEntity.ok(toMap(result.attribute()));
        }
        return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable UUID id,
                                                        @RequestBody GatewayCustomAttributeEntity updates) {
        AttributeResult result = customAttributeService.update(id, updates);
        if (result.success()) {
            return ResponseEntity.ok(toMap(result.attribute()));
        }
        return ResponseEntity.badRequest().body(Map.of("error", result.error()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
        if (customAttributeService.delete(id)) {
            return ResponseEntity.ok(Map.of("deleted", true, "id", id.toString()));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable UUID id) {
        return customAttributeService.toggleEnabled(id)
                .map(a -> ResponseEntity.ok(toMap(a)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(customAttributeService.getStats());
    }

    @GetMapping("/sources")
    public ResponseEntity<Map<String, Object>> getSources() {
        Map<String, Object> info = new LinkedHashMap<>();

        info.put("valueSources", List.of(
                Map.of("name", "STATIC",
                        "description", "Fixed value set by admin (uses defaultValue)"),
                Map.of("name", "HEADER",
                        "description", "Resolved from HTTP request header sent by agent (sourceKey = header name, e.g., X-Risk-Score)"),
                Map.of("name", "AGENT_FIELD",
                        "description", "Resolved from the agent's DB record (sourceKey = field name)")
        ));

        info.put("dataTypes", List.of(
                Map.of("name", "STRING", "operators", "EQ, NEQ, LIKE, CONTAINS"),
                Map.of("name", "INTEGER", "operators", "EQ, NEQ, GT, LT, GTE, LTE"),
                Map.of("name", "BOOLEAN", "operators", "EQ, NEQ")
        ));

        info.put("agentFields", List.of(
                Map.of("name", "totalRequests", "type", "INTEGER",
                        "description", "Total tool/prompt/resource requests made by this agent"),
                Map.of("name", "totalSessions", "type", "INTEGER",
                        "description", "Total MCP sessions established by this agent"),
                Map.of("name", "status", "type", "STRING",
                        "description", "Agent status (ACTIVE, INACTIVE)"),
                Map.of("name", "approvalStatus", "type", "STRING",
                        "description", "Approval gate status (APPROVED, PENDING, BLOCKED)"),
                Map.of("name", "protocolVersion", "type", "STRING",
                        "description", "MCP protocol version reported by agent"),
                Map.of("name", "agentVersion", "type", "STRING",
                        "description", "Agent software version"),
                Map.of("name", "firstSeenAt", "type", "STRING",
                        "description", "ISO timestamp of first connection"),
                Map.of("name", "lastSeenAt", "type", "STRING",
                        "description", "ISO timestamp of most recent activity")
        ));

        info.put("usage", "In Cedar policies, registered attributes are accessed as context.<attributeName>");
        info.put("example", "Register riskScore (HEADER, sourceKey=X-Risk-Score, INTEGER) → policy: context.riskScore > 80");

        return ResponseEntity.ok(info);
    }

    private Map<String, Object> toMap(GatewayCustomAttributeEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("attributeName", entity.getAttributeName());
        map.put("displayName", entity.getDisplayName());
        map.put("description", entity.getDescription());
        map.put("dataType", entity.getDataType());
        map.put("valueSource", entity.getValueSource());
        map.put("sourceKey", entity.getSourceKey());
        map.put("defaultValue", entity.getDefaultValue());
        map.put("enabled", entity.getEnabled());
        map.put("createdAt", entity.getCreatedAt());
        map.put("updatedAt", entity.getUpdatedAt());
        return map;
    }
}
