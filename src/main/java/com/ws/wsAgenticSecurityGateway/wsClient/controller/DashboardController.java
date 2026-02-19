package com.ws.wsAgenticSecurityGateway.wsClient.controller;

import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.repository.McpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dashboard overview controller for the Admin UI.
 *
 * <p>Provides gateway-wide summary statistics displayed
 * in the sidebar and header of the dashboard.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@Slf4j
public class DashboardController {

    private final McpSessionManager sessionManager;
    private final CapabilityRegistryService registryService;
    private final McpAuditLogRepository auditRepo;

    public DashboardController(McpSessionManager sessionManager,
                               CapabilityRegistryService registryService,
                               McpAuditLogRepository auditRepo) {
        this.sessionManager = sessionManager;
        this.registryService = registryService;
        this.auditRepo = auditRepo;
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
                .filter(s -> s.isActive())
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
        summary.put("recentEventCount", auditRepo.countByTimestampAfter(last24h));
        summary.put("recentErrorCount",
                auditRepo.countByStatusAndTimestampAfter(AuditStatus.ERROR, last24h)
                        + auditRepo.countByStatusAndTimestampAfter(AuditStatus.FAILURE, last24h));

        return ResponseEntity.ok(summary);
    }
}
