package com.ws.wsAgenticSecurity.client.config;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents an active HTTP-based MCP session with complete server metadata.
 * Stores client, transport, tools, and connection information.
 */
@Getter
public class McpSession {

    private final String serverName;
    private final McpSyncClient client;
    private final HttpMcpTransport transport;
    private final LocalDateTime connectedAt;

    // Cached server data
    private List<McpSchema.Tool> tools;
    private List<McpSchema.Resource> resources;
    private List<McpSchema.Prompt> prompts;
    private McpSchema.ServerCapabilities capabilities;
    private McpSchema.Implementation serverInfo;

    public McpSession(String serverName,
                      McpSyncClient client,
                      HttpMcpTransport transport) {
        this.serverName = serverName;
        this.client = client;
        this.transport = transport;
        this.connectedAt = LocalDateTime.now();
    }

    /**
     * Cache tools after fetching from server
     */
    public void setTools(List<McpSchema.Tool> tools) {
        this.tools = tools;
    }

    /**
     * Cache resources after fetching from server
     */
    public void setResources(List<McpSchema.Resource> resources) {
        this.resources = resources;
    }

    /**
     * Cache prompts after fetching from server
     */
    public void setPrompts(List<McpSchema.Prompt> prompts) {
        this.prompts = prompts;
    }

    /**
     * Cache server capabilities
     */
    public void setCapabilities(McpSchema.ServerCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    /**
     * Cache server info
     */
    public void setServerInfo(McpSchema.Implementation serverInfo) {
        this.serverInfo = serverInfo;
    }

    /**
     * Check if session is active
     */
    public boolean isActive() {
        return client != null && client.isInitialized() && transport.isConnected();
    }

    /**
     * Get tool count
     */
    public int getToolCount() {
        return tools != null ? tools.size() : 0;
    }

    /**
     * Close the session and cleanup resources
     */
    public void close() {
        try {
            if (transport != null) {
                transport.closeGracefully().block();
            }
        } catch (Exception e) {
            // Log but don't throw
            System.err.println("Error closing session for " + serverName + ": " + e.getMessage());
        }
    }
}
