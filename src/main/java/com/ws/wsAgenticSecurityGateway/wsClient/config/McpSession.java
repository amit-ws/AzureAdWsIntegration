package com.ws.wsAgenticSecurityGateway.wsClient.config;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
public class McpSession {

    private final String sessionId;
    private final String serverName;
    private final McpSyncClient client;
    private final HttpMcpTransport transport;
    private final LocalDateTime connectedAt;
    private final String wsTenantName;

    private List<McpSchema.Tool> tools;
    private List<McpSchema.Resource> resources;
    private List<McpSchema.Prompt> prompts;
    private McpSchema.ServerCapabilities capabilities;
    private McpSchema.Implementation serverInfo;

    public McpSession(String serverName,
                      McpSyncClient client,
                      HttpMcpTransport transport,
                      String wsTenantName) {
        this.sessionId = UUID.randomUUID().toString();
        this.serverName = serverName;
        this.client = client;
        this.transport = transport;
        this.connectedAt = LocalDateTime.now();
        this.wsTenantName = wsTenantName;
    }

    public void setTools(List<McpSchema.Tool> tools) {
        this.tools = tools;
    }

    public void setResources(List<McpSchema.Resource> resources) {
        this.resources = resources;
    }

    public void setPrompts(List<McpSchema.Prompt> prompts) {
        this.prompts = prompts;
    }

    public void setCapabilities(McpSchema.ServerCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    public void setServerInfo(McpSchema.Implementation serverInfo) {
        this.serverInfo = serverInfo;
    }

    public boolean isActive() {
        return client != null && client.isInitialized();
    }

    public int getToolCount() {
        return tools != null ? tools.size() : 0;
    }

    public void close() {
        try {
            if (transport != null) {
                transport.closeGracefully().block();
            }
        } catch (Exception e) {
            System.err.println("Error closing session for " + serverName + ": " + e.getMessage());
        }
    }
}
