package com.ws.wsAgenticSecurityGateway.wsClient.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.audit.error.McpErrorCode;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSession;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSessionManager;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class McpClientService {

    private final McpSessionManager sessionManager;
    private final McpAuditService auditService;
    private final ObjectMapper objectMapper;

    public McpClientService(McpSessionManager sessionManager,
                            McpAuditService auditService) {
        this.sessionManager = sessionManager;
        this.auditService = auditService;
        this.objectMapper = new ObjectMapper();
    }

    public Map<String, Map<String, Object>> listServers() {
        Map<String, Map<String, Object>> serversInfo = new HashMap<>();

        sessionManager.getAllSessions().forEach((configName, session) -> {
            Map<String, Object> info = new HashMap<>();
            info.put("configName", configName);
            info.put("status", session.isActive() ? "connected" : "disconnected");
            info.put("connectedAt", session.getConnectedAt().toString());
            info.put("toolCount", session.getToolCount());
            info.put("resourceCount", session.getResources() != null ? session.getResources().size() : 0);
            info.put("promptCount", session.getPrompts() != null ? session.getPrompts().size() : 0);

            if (session.getServerInfo() != null) {
                info.put("serverName", session.getServerInfo().name());
                info.put("serverVersion", session.getServerInfo().version());
            }

            if (session.getCapabilities() != null) {
                Map<String, Boolean> capabilities = new HashMap<>();
                capabilities.put("tools", session.getCapabilities().tools() != null);
                capabilities.put("resources", session.getCapabilities().resources() != null);
                capabilities.put("prompts", session.getCapabilities().prompts() != null);
                capabilities.put("logging", session.getCapabilities().logging() != null);
                info.put("capabilities", capabilities);
            }

            serversInfo.put(configName, info);
        });

        return serversInfo;
    }

    public Map<String, Object> getServerStatus(String serverName) {
        McpSession session = sessionManager.getSession(serverName);
        McpSyncClient client = session.getClient();

        Map<String, Object> status = new HashMap<>();
        status.put("serverName", serverName);
        status.put("initialized", client.isInitialized());
        status.put("active", session.isActive());
        status.put("connectedAt", session.getConnectedAt());
        status.put("serverInfo", client.getServerInfo());
        status.put("capabilities", client.getServerCapabilities());
        status.put("toolCount", session.getToolCount());

        if (session.getTools() != null) {
            status.put("toolNames", session.getTools().stream()
                    .map(McpSchema.Tool::name)
                    .toList());
        }

        return status;
    }

    public List<McpSchema.Tool> listTools(String serverName) {
        McpSession session = sessionManager.getSession(serverName);

        if (session.getTools() != null) {
            log.info("Returning {} cached tools for server '{}'",
                    session.getTools().size(), serverName);
            return session.getTools();
        }

        log.info("Fetching tools from server '{}'...", serverName);
        long start = System.currentTimeMillis();
        try {
            List<McpSchema.Tool> tools = session.getClient().listTools().tools();
            session.setTools(tools);
            long duration = System.currentTimeMillis() - start;
            List<String> toolNames = tools.stream().map(McpSchema.Tool::name).toList();
            auditService.auditClientToolsListFetched(session.getSessionId(), serverName, tools.size(), toolNames, duration);
            return tools;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            auditService.auditClientToolsListFailed(session.getSessionId(), serverName, e.getMessage(), duration);
            throw e;
        }
    }

    public List<McpSchema.Resource> listResources(String serverName) {
        McpSession session = sessionManager.getSession(serverName);

        if (session.getResources() != null) {
            return session.getResources();
        }

        log.info("Fetching resources from server '{}'...", serverName);
        long start = System.currentTimeMillis();
        try {
            List<McpSchema.Resource> resources = session.getClient().listResources().resources();
            session.setResources(resources);
            long duration = System.currentTimeMillis() - start;
            List<String> uris = resources.stream().map(McpSchema.Resource::uri).toList();
            auditService.auditClientResourcesListFetched(session.getSessionId(), serverName, resources.size(), uris, duration);
            return resources;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            auditService.auditClientResourcesListFailed(session.getSessionId(), serverName, e.getMessage(), duration);
            throw e;
        }
    }

    public List<McpSchema.Prompt> listPrompts(String serverName) {
        McpSession session = sessionManager.getSession(serverName);

        if (session.getPrompts() != null) {
            return session.getPrompts();
        }

        log.info("Fetching prompts from server '{}'...", serverName);
        long start = System.currentTimeMillis();
        try {
            List<McpSchema.Prompt> prompts = session.getClient().listPrompts().prompts();
            session.setPrompts(prompts);
            long duration = System.currentTimeMillis() - start;
            List<String> promptNames = prompts.stream().map(McpSchema.Prompt::name).toList();
            auditService.auditClientPromptsListFetched(session.getSessionId(), serverName, prompts.size(), promptNames, duration);
            return prompts;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            auditService.auditClientPromptsListFailed(session.getSessionId(), serverName, e.getMessage(), duration);
            throw e;
        }
    }

    public List<McpSchema.Content> callTool(String correlationId, String serverName, String toolName, JsonNode input,
                                             LocalDateTime firedAt, Integer eventSequence) {
        log.info("Calling tool '{}' on server '{}'", toolName, serverName);
        long start = System.currentTimeMillis();

        McpSession session = sessionManager.getSession(serverName);
        McpSyncClient client = session.getClient();

        Map<String, Object> args = objectMapper.convertValue(
                input,
                new TypeReference<>() {
                }
        );

        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args);

        try {
            List<McpSchema.Content> result = client.callTool(request).content();
            long duration = System.currentTimeMillis() - start;

            log.info("Tool '{}' executed successfully, returned {} content item(s)",
                    toolName, result.size());

            result.forEach(content -> {
                if (content instanceof McpSchema.TextContent t) {
                    log.debug("TEXT: {} chars", t.text().length());
                } else if (content instanceof McpSchema.ImageContent) {
                    log.debug("IMAGE");
                } else if (content instanceof McpSchema.AudioContent) {
                    log.debug("AUDIO");
                } else if (content instanceof McpSchema.EmbeddedResource r) {
                    log.debug("EMBEDDED RESOURCE: {}", r.resource().uri());
                }
            });

            auditService.auditClientToolInvocation(session.getSessionId(), correlationId, serverName, toolName, args, result, duration, firedAt, eventSequence);

            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            auditService.auditClientToolInvocationFailed(
                    session.getSessionId(), correlationId, serverName, toolName, args, e.getMessage(), McpErrorCode.INTERNAL_ERROR, duration, firedAt, eventSequence);
            throw e;
        }
    }

    public McpSchema.GetPromptResult getPrompt(String serverName, String promptName,
                                                Map<String, Object> arguments) {
        log.info("Getting prompt '{}' from server '{}'", promptName, serverName);
        long start = System.currentTimeMillis();

        McpSession session = sessionManager.getSession(serverName);
        McpSyncClient client = session.getClient();

        McpSchema.GetPromptRequest request = new McpSchema.GetPromptRequest(promptName, arguments);

        try {
            McpSchema.GetPromptResult result = client.getPrompt(request);
            long duration = System.currentTimeMillis() - start;

            int messageCount = result.messages() != null ? result.messages().size() : 0;
            log.info("Prompt '{}' retrieved successfully, {} message(s)", promptName, messageCount);

            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Failed to get prompt '{}' from '{}': {}", promptName, serverName, e.getMessage());
            throw e;
        }
    }

    public List<McpSchema.ResourceContents> readResource(String correlationId, String serverName, String uri,
                                                          LocalDateTime firedAt, Integer eventSequence) {
        log.info("Reading resource '{}' from server '{}'", uri, serverName);
        long start = System.currentTimeMillis();

        McpSession session = sessionManager.getSession(serverName);
        McpSyncClient client = session.getClient();

        McpSchema.ReadResourceRequest request = new McpSchema.ReadResourceRequest(uri);

        try {
            List<McpSchema.ResourceContents> contents = client.readResource(request).contents();
            long duration = System.currentTimeMillis() - start;

            log.info("Resource read successfully, {} content item(s)", contents.size());

            auditService.auditClientResourceRead(session.getSessionId(), correlationId, serverName, uri, contents, duration, firedAt, eventSequence);

            return contents;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            auditService.auditClientResourceReadFailed(session.getSessionId(), correlationId, serverName, uri, e.getMessage(), duration, firedAt, eventSequence);
            throw e;
        }
    }

    public void disconnect(String serverName) {
        log.info("Disconnecting server '{}'...", serverName);
        sessionManager.disconnect(serverName);
    }

    public void disconnectAll() {
        log.info("Disconnecting all servers...");
        for (String serverName : sessionManager.getServerNames()) {
            sessionManager.disconnect(serverName);
        }
    }

    public void printSessionInfo(String serverName) {
        McpSession session = sessionManager.getSession(serverName);
        McpSyncClient client = session.getClient();

        System.out.println("MCP SESSION INFO: " + serverName);
        System.out.println("Status: " + (session.isActive() ? "ACTIVE" : "INACTIVE"));
        System.out.println("Connected At: " + session.getConnectedAt());
        System.out.println("Initialized: " + client.isInitialized());

        if (client.getServerInfo() != null) {
            System.out.println("\n--- SERVER INFO ---");
            System.out.println("Name: " + client.getServerInfo().name());
            System.out.println("Version: " + client.getServerInfo().version());
        }

        if (client.getClientInfo() != null) {
            System.out.println("\n--- CLIENT INFO ---");
            System.out.println("Name: " + client.getClientInfo().name());
            System.out.println("Version: " + client.getClientInfo().version());
        }

        McpSchema.ServerCapabilities caps = client.getServerCapabilities();
        if (caps != null) {
            System.out.println("\n--- SERVER CAPABILITIES ---");
            System.out.println("Tools: " + (caps.tools() != null ? "SUPPORTED" : "NOT SUPPORTED"));
            System.out.println("Resources: " + (caps.resources() != null ? "SUPPORTED" : "NOT SUPPORTED"));
            System.out.println("Prompts: " + (caps.prompts() != null ? "SUPPORTED" : "NOT SUPPORTED"));
            System.out.println("Logging: " + (caps.logging() != null ? "SUPPORTED" : "NOT SUPPORTED"));
            System.out.println("Completions: " + (caps.completions() != null ? "SUPPORTED" : "NOT SUPPORTED"));
        }

        System.out.println("\n--- CACHED DATA ---");
        System.out.println("Tools: " + session.getToolCount());
        System.out.println("Resources: " + (session.getResources() != null ? session.getResources().size() : 0));
        System.out.println("Prompts: " + (session.getPrompts() != null ? session.getPrompts().size() : 0));
    }
}
