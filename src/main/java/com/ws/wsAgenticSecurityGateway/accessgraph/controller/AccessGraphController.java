package com.ws.wsAgenticSecurityGateway.accessgraph.controller;

import com.ws.wsAgenticSecurityGateway.accessgraph.dto.AccessGraph;
import com.ws.wsAgenticSecurityGateway.accessgraph.service.AccessGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity Access Graph — "who can reach what, who did, where's the risk, what to fix" in one read-model.
 * Tenant-scoped, optionally windowed by {@code hours} (omit for ~all-time). Read-only.
 */
@RestController
@RequestMapping("/api/admin/access-graph")
@Slf4j
public class AccessGraphController {

    private final AccessGraphService accessGraphService;

    public AccessGraphController(AccessGraphService accessGraphService) {
        this.accessGraphService = accessGraphService;
    }

    @GetMapping
    public ResponseEntity<AccessGraph> getAccessGraph(@RequestParam(required = false) Integer hours) {
        log.info("GET /api/admin/access-graph hours={}", hours);
        return ResponseEntity.ok(accessGraphService.build(hours));
    }
}
