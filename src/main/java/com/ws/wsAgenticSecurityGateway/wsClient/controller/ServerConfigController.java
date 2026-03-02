package com.ws.wsAgenticSecurityGateway.wsClient.controller;

import com.ws.wsAgenticSecurityGateway.wsClient.dto.ServerConfigRequest;
import com.ws.wsAgenticSecurityGateway.wsClient.dto.ServerConfigResponse;
import com.ws.wsAgenticSecurityGateway.wsClient.service.ServerConfigService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * REST controller for MCP server configuration management.
 *
 * <p>Replaces the file-based {@code ConfigController} with DB-driven config.
 * Provides full CRUD plus connection lifecycle operations.
 *
 * <p>Base path: {@code /api/admin/mcp-servers}
 */
@RestController
@RequestMapping("/api/admin/mcp-servers")
@Slf4j
public class ServerConfigController {

    private final ServerConfigService serverConfigService;

    public ServerConfigController(ServerConfigService serverConfigService) {
        this.serverConfigService = serverConfigService;
    }

    // ════════════════════════════════════════════════════════════════════
    //  CRUD
    // ════════════════════════════════════════════════════════════════════

    /**
     * POST /api/admin/mcp-servers — Create a new server configuration.
     * Auto-connects if enabled + autoConnect.
     */
    @PostMapping
    public ResponseEntity<?> createServerConfig(@Valid @RequestBody ServerConfigRequest request) {
        log.info("POST /api/admin/mcp-servers — creating '{}'", request.getServerName());
        try {
            ServerConfigResponse response = serverConfigService.createServerConfig(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating server config '{}': {}", request.getServerName(), e.getMessage());
            return ResponseEntity.internalServerError().body(errorMap(e.getMessage()));
        }
    }

    /**
     * GET /api/admin/mcp-servers — List all server configurations with connection status.
     */
    @GetMapping
    public ResponseEntity<List<ServerConfigResponse>> listServerConfigs() {
        List<ServerConfigResponse> configs = serverConfigService.listServerConfigs();
        return ResponseEntity.ok(configs);
    }

    /**
     * GET /api/admin/mcp-servers/{name} — Get a specific server configuration.
     */
    @GetMapping("/{name}")
    public ResponseEntity<?> getServerConfig(@PathVariable String name) {
        try {
            ServerConfigResponse response = serverConfigService.getServerConfig(name);
            return ResponseEntity.ok(response);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap(e.getMessage()));
        }
    }

    /**
     * PUT /api/admin/mcp-servers/{name} — Update a server configuration.
     * If connected, disconnects first, updates, then reconnects.
     */
    @PutMapping("/{name}")
    public ResponseEntity<?> updateServerConfig(@PathVariable String name,
                                                @Valid @RequestBody ServerConfigRequest request) {
        log.info("PUT /api/admin/mcp-servers/{}", name);
        try {
            ServerConfigResponse response = serverConfigService.updateServerConfig(name, request);
            return ResponseEntity.ok(response);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating server config '{}': {}", name, e.getMessage());
            return ResponseEntity.internalServerError().body(errorMap(e.getMessage()));
        }
    }

    /**
     * DELETE /api/admin/mcp-servers/{name} — Delete a server configuration.
     * Disconnects first if connected.
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<?> deleteServerConfig(@PathVariable String name) {
        log.info("DELETE /api/admin/mcp-servers/{}", name);
        try {
            serverConfigService.deleteServerConfig(name);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "Server config '" + name + "' deleted");
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap(e.getMessage()));
        } catch (Exception e) {
            log.error("Error deleting server config '{}': {}", name, e.getMessage());
            return ResponseEntity.internalServerError().body(errorMap(e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  CONNECTION LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    /**
     * POST /api/admin/mcp-servers/{name}/connect — Connect to a configured server.
     */
    @PostMapping("/{name}/connect")
    public ResponseEntity<?> connectServer(@PathVariable String name) {
        log.info("POST /api/admin/mcp-servers/{}/connect", name);
        try {
            serverConfigService.connectServer(name);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "Server '" + name + "' connected");
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            log.error("Error connecting server '{}': {}", name, e.getMessage());
            return ResponseEntity.internalServerError().body(errorMap(e.getMessage()));
        }
    }

    /**
     * POST /api/admin/mcp-servers/{name}/disconnect — Disconnect a server.
     */
    @PostMapping("/{name}/disconnect")
    public ResponseEntity<?> disconnectServer(@PathVariable String name) {
        log.info("POST /api/admin/mcp-servers/{}/disconnect", name);
        try {
            serverConfigService.disconnectServer(name);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "Server '" + name + "' disconnected");
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            log.error("Error disconnecting server '{}': {}", name, e.getMessage());
            return ResponseEntity.internalServerError().body(errorMap(e.getMessage()));
        }
    }

    /**
     * POST /api/admin/mcp-servers/{name}/reconnect — Reconnect a server.
     */
    @PostMapping("/{name}/reconnect")
    public ResponseEntity<?> reconnectServer(@PathVariable String name) {
        log.info("POST /api/admin/mcp-servers/{}/reconnect", name);
        try {
            serverConfigService.reconnectServer(name);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("message", "Server '" + name + "' reconnected");
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            log.error("Error reconnecting server '{}': {}", name, e.getMessage());
            return ResponseEntity.internalServerError().body(errorMap(e.getMessage()));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Map<String, Object> errorMap(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", "error");
        error.put("message", message);
        return error;
    }
}
