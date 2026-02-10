package com.ws.wsAgenticSecurity.client.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

/**
 * Flexible MCP server configuration supporting HTTP-based servers.
 * Supports variable resolution from environment and config inputs.
 *
 * Example:
 * {
 *   "type": "http",
 *   "url": "https://api.example.com/mcp",
 *   "headers": {
 *     "Authorization": "Bearer ${env:API_TOKEN}"
 *   },
 *   "config": {
 *     "key": "value"
 *   }
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpServerConfig {

    /**
     * Server type (currently only "http" supported)
     */
    private String type = "http";

    /**
     * Base URL for HTTP-based MCP server
     */
    private String url;

    /**
     * HTTP headers to send with requests
     * Supports variable resolution: ${env:VAR_NAME} or ${input:VAR_NAME}
     */
    private Map<String, String> headers;

    /**
     * Additional configuration parameters
     * Provider-specific settings (Jira URL, Slack workspace, etc.)
     */
    private Map<String, Object> config;

    /**
     * Timeout in seconds for HTTP connections
     */
    private Integer timeout = 30;

    /**
     * Validate required fields
     */
    public void validate() {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("Server type is required");
        }

        if (!"http".equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("Only 'http' type is supported. Got: " + type);
        }

        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("Server URL is required for HTTP type");
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }
    }

    /**
     * Check if this is an HTTP server
     */
    public boolean isHttp() {
        return "http".equalsIgnoreCase(type);
    }
}
