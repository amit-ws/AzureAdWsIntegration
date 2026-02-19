package com.ws.wsAgenticSecurityGateway.wsClient.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.wsClient.config.ConfigVariableResolver;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpConfigFile;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpConfigLoader;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpServerConfig;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for MCP server configuration management.
 *
 * <p>Allows the admin dashboard to view current configuration
 * (with secrets masked), upload new configurations, and
 * manage individual server connections.
 */
@RestController
@RequestMapping("/api/admin/config")
@Slf4j
public class ConfigController {

    private final McpConfigLoader configLoader;
    private final McpSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    /** Tracks the current loaded config for display purposes. */
    private volatile McpConfigFile currentConfig;

    public ConfigController(McpConfigLoader configLoader,
                            McpSessionManager sessionManager) {
        this.configLoader = configLoader;
        this.sessionManager = sessionManager;
        this.objectMapper = new ObjectMapper();

        // Load initial config for display
        try {
            this.currentConfig = configLoader.loadConfig();
        } catch (Exception e) {
            log.warn("Could not load initial config for dashboard: {}", e.getMessage());
        }
    }

    /**
     * GET /api/admin/config
     * Returns current configuration with secrets masked.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getCurrentConfig() {
        log.info("📋 GET /api/admin/config");

        Map<String, Object> result = new LinkedHashMap<>();

        if (currentConfig == null) {
            result.put("status", "no_config");
            result.put("message", "No configuration loaded");
            return ResponseEntity.ok(result);
        }

        result.put("status", "loaded");

        // Build sanitized server list
        Map<String, Object> servers = new LinkedHashMap<>();
        if (currentConfig.getMcpServers() != null) {
            currentConfig.getMcpServers().forEach((name, config) -> {
                Map<String, Object> sanitized = new LinkedHashMap<>();
                sanitized.put("type", config.getType());
                sanitized.put("url", config.getUrl());
                sanitized.put("timeout", config.getTimeout());

                // Mask headers
                if (config.getHeaders() != null) {
                    Map<String, String> maskedHeaders = new LinkedHashMap<>();
                    config.getHeaders().forEach((key, value) -> {
                        maskedHeaders.put(key, maskSecret(value));
                    });
                    sanitized.put("headers", maskedHeaders);
                }

                // Connection status from session manager
                sanitized.put("connected", sessionManager.isConnected(name));

                servers.put(name, sanitized);
            });
        }
        result.put("servers", servers);
        result.put("serverCount", servers.size());

        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/admin/config/upload
     * Upload a new MCP configuration JSON, parse, validate, and connect servers.
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadConfig(@RequestBody String configJson) {
        log.info("📤 POST /api/admin/config/upload");

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, String>> successes = new ArrayList<>();
        List<Map<String, String>> errors = new ArrayList<>();

        try {
            // Parse and validate
            McpConfigFile config = objectMapper.readValue(configJson, McpConfigFile.class);
            config.validate();

            // Create variable resolver
            ConfigVariableResolver resolver = configLoader.createResolver(config);
            sessionManager.setVariableResolver(resolver);

            // Connect each server
            for (Map.Entry<String, McpServerConfig> entry : config.getMcpServers().entrySet()) {
                String serverName = entry.getKey();
                McpServerConfig serverConfig = entry.getValue();

                try {
                    sessionManager.connect(serverName, serverConfig);
                    successes.add(Map.of(
                            "server", serverName,
                            "status", "connected",
                            "url", serverConfig.getUrl()
                    ));
                } catch (Exception e) {
                    log.error("Failed to connect server '{}': {}", serverName, e.getMessage());
                    errors.add(Map.of(
                            "server", serverName,
                            "status", "failed",
                            "error", e.getMessage()
                    ));
                }
            }

            // Store config for display
            this.currentConfig = config;

            result.put("status", "completed");
            result.put("serversAdded", successes);
            result.put("errors", errors);
            result.put("totalServers", config.getMcpServers().size());
            result.put("successCount", successes.size());
            result.put("errorCount", errors.size());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Failed to parse config: {}", e.getMessage());
            result.put("status", "failed");
            result.put("error", "Invalid configuration: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * DELETE /api/admin/config/servers/{serverName}
     * Disconnect and remove a server.
     */
    @DeleteMapping("/servers/{serverName}")
    public ResponseEntity<Map<String, String>> removeServer(@PathVariable String serverName) {
        log.info("🗑️ DELETE /api/admin/config/servers/{}", serverName);

        try {
            sessionManager.disconnect(serverName);

            // Also remove from current config if present
            if (currentConfig != null && currentConfig.getMcpServers() != null) {
                currentConfig.getMcpServers().remove(serverName);
            }

            return ResponseEntity.ok(Map.of(
                    "status", "removed",
                    "server", serverName,
                    "message", "Server '" + serverName + "' disconnected and removed"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "server", serverName,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * POST /api/admin/config/servers/{serverName}/reconnect
     * Reconnect an existing server.
     */
    @PostMapping("/servers/{serverName}/reconnect")
    public ResponseEntity<Map<String, String>> reconnectServer(@PathVariable String serverName) {
        log.info("🔄 POST /api/admin/config/servers/{}/reconnect", serverName);

        if (currentConfig == null || currentConfig.getMcpServers() == null
                || !currentConfig.getMcpServers().containsKey(serverName)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "server", serverName,
                    "error", "Server '" + serverName + "' not found in current config"
            ));
        }

        try {
            McpServerConfig config = currentConfig.getMcpServers().get(serverName);
            sessionManager.connect(serverName, config);

            return ResponseEntity.ok(Map.of(
                    "status", "reconnected",
                    "server", serverName,
                    "message", "Server '" + serverName + "' reconnected successfully"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "server", serverName,
                    "error", e.getMessage()
            ));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private String maskSecret(String value) {
        if (value == null) return null;
        if (value.length() <= 8) return "***REDACTED***";
        // Show first 4 and last 4 characters for identification
        return value.substring(0, 4) + "***REDACTED***" + value.substring(value.length() - 4);
    }
}
