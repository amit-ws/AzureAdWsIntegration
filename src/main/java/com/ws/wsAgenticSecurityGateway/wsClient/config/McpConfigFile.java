package com.ws.wsAgenticSecurityGateway.wsClient.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

/**
 * Root configuration model for MCP client.
 * Supports flexible server configurations and input variables.
 *
 * Example:
 * {
 *   "mcpServers": {
 *     "github": {
 *       "type": "http",
 *       "url": "https://api.example.com",
 *       "headers": {
 *         "Authorization": "Bearer ${input:github_token}"
 *       }
 *     }
 *   },
 *   "inputs": {
 *     "github_token": "ghp_xxx",
 *     "api_key": "xxx"
 *   }
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpConfigFile {

    /**
     * Map of server name to server configuration
     * Key: server name (e.g., "github", "jira", "slack")
     * Value: server configuration details
     */
    private Map<String, McpServerConfig> mcpServers;

    /**
     * Input variables for variable resolution
     * These can be referenced in configs using ${input:key} syntax
     *
     * Example:
     * "inputs": {
     *   "github_token": "ghp_xxx",
     *   "api_key": "xxx"
     * }
     */
    private Map<String, String> inputs;

    /**
     * Validate the configuration
     */
    public void validate() {
        if (mcpServers == null || mcpServers.isEmpty()) {
            throw new IllegalArgumentException("No MCP servers defined in configuration");
        }

        // Validate each server config
        mcpServers.forEach((name, config) -> {
            try {
                config.validate();
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid config for server '" + name + "': " + e.getMessage(), e);
            }
        });
    }
}
