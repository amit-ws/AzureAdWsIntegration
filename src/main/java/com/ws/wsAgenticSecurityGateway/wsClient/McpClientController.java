package com.ws.wsAgenticSecurityGateway.wsClient;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for MCP client operations.
 * Provides endpoints to interact with multiple MCP servers.
 */
@RestController
@RequestMapping("/api/mcp")
@Slf4j
public class McpClientController {

    private final McpClientService service;

    public McpClientController(McpClientService service) {
        this.service = service;
    }

    /**
     * GET /api/mcp/servers
     * List all connected MCP servers with their metadata
     */
    @GetMapping("/servers")
    public ResponseEntity<Map<String, Map<String, Object>>> listServers() {
        log.info("📋 GET /api/mcp/servers - Listing all servers");
        Map<String, Map<String, Object>> servers = service.listServers();
        return ResponseEntity.ok(servers);
    }

    /**
     * GET /api/mcp/servers/{serverName}
     * Get detailed status for a specific server
     */
    @GetMapping("/servers/{serverName}")
    public ResponseEntity<Map<String, Object>> getServerStatus(@PathVariable String serverName) {
        log.info("📋 GET /api/mcp/servers/{} - Getting server status", serverName);
        try {
            Map<String, Object> status = service.getServerStatus(serverName);
            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * GET /api/mcp/servers/{serverName}/tools
     * List all tools available on a specific server
     */
    @GetMapping("/servers/{serverName}/tools")
    public ResponseEntity<List<McpSchema.Tool>> listTools(@PathVariable String serverName) {
        log.info("🔧 GET /api/mcp/servers/{}/tools - Listing tools", serverName);
        try {
            List<McpSchema.Tool> tools = service.listTools(serverName);
            return ResponseEntity.ok(tools);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * GET /api/mcp/servers/{serverName}/resources
     * List all resources available on a specific server
     */
    @GetMapping("/servers/{serverName}/resources")
    public ResponseEntity<List<McpSchema.Resource>> listResources(@PathVariable String serverName) {
        log.info("📦 GET /api/mcp/servers/{}/resources - Listing resources", serverName);
        try {
            List<McpSchema.Resource> resources = service.listResources(serverName);
            return ResponseEntity.ok(resources);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * GET /api/mcp/servers/{serverName}/prompts
     * List all prompts available on a specific server
     */
    @GetMapping("/servers/{serverName}/prompts")
    public ResponseEntity<List<McpSchema.Prompt>> listPrompts(@PathVariable String serverName) {
        log.info("💬 GET /api/mcp/servers/{}/prompts - Listing prompts", serverName);
        try {
            List<McpSchema.Prompt> prompts = service.listPrompts(serverName);
            return ResponseEntity.ok(prompts);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * POST /api/mcp/servers/{serverName}/tools/{toolName}
     * Call a specific tool on a server
     * <p>
     * Request body: JSON object with tool arguments
     */
    @PostMapping("/servers/{serverName}/tools/{toolName}")
    public ResponseEntity<List<McpSchema.Content>> callTool(
            @PathVariable String serverName,
            @PathVariable String toolName,
            @RequestBody JsonNode input) {

        log.info("🔧 POST /api/mcp/servers/{}/tools/{} - Calling tool", serverName, toolName);

        try {
            String correlationId = UUID.randomUUID().toString();
            List<McpSchema.Content> result = service.callTool(correlationId, serverName, toolName, input, null, null);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error calling tool: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * GET /api/mcp/servers/{serverName}/resources/read
     * Read a specific resource from a server
     * <p>
     * Query param: uri - The resource URI to read
     */
    @GetMapping("/servers/{serverName}/resources/read")
    public ResponseEntity<List<McpSchema.ResourceContents>> readResource(
            @PathVariable String serverName,
            @RequestParam String uri) {

        log.info("📖 GET /api/mcp/servers/{}/resources/read?uri={}", serverName, uri);

        try {
            String correlationId = UUID.randomUUID().toString();
            List<McpSchema.ResourceContents> contents = service.readResource(correlationId, serverName, uri, null, null);
            return ResponseEntity.ok(contents);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error reading resource: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * DELETE /api/mcp/servers/{serverName}
     * Disconnect a specific server
     */
    @DeleteMapping("/servers/{serverName}")
    public ResponseEntity<Map<String, String>> disconnectServer(@PathVariable String serverName) {
        log.info("🔌 DELETE /api/mcp/servers/{} - Disconnecting server", serverName);

        try {
            service.disconnect(serverName);
            return ResponseEntity.ok(Map.of(
                    "message", "Server '" + serverName + "' disconnected successfully",
                    "serverName", serverName
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * DELETE /api/mcp/servers
     * Disconnect all servers
     */
    @DeleteMapping("/servers")
    public ResponseEntity<Map<String, String>> disconnectAll() {
        log.info("🔌 DELETE /api/mcp/servers - Disconnecting all servers");
        service.disconnectAll();
        return ResponseEntity.ok(Map.of(
                "message", "All servers disconnected successfully"
        ));
    }

    /**
     * GET /api/mcp/servers/{serverName}/info
     * Print detailed session info to console (for debugging)
     */
    @GetMapping("/servers/{serverName}/info")
    public ResponseEntity<Map<String, String>> printSessionInfo(@PathVariable String serverName) {
        log.info("📊 GET /api/mcp/servers/{}/info - Printing session info", serverName);

        try {
            service.printSessionInfo(serverName);
            return ResponseEntity.ok(Map.of(
                    "message", "Session info printed to console",
                    "serverName", serverName
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Map<String, Object>> servers = service.listServers();
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "totalServers", servers.size(),
                "activeServers", servers.values().stream()
                        .filter(s -> "connected".equals(s.get("status")))
                        .count()
        ));
    }
}