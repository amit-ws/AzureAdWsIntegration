package com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.event.CapabilityRegistryChangedEvent;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.entity.GatewayMcpServerSessionEntity;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.repository.GatewayMcpServerSessionRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class McpSessionManager {

    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();

    private volatile boolean shuttingDown = false;

    private final GatewayAuditService auditService;

    private final CapabilityRegistryService registryService;

    private final ObjectMapper objectMapper;

    private final GatewayMcpServerSessionRepository serverSessionRepository;
    private final ApplicationEventPublisher eventPublisher;

    public McpSessionManager(GatewayAuditService auditService,
                             CapabilityRegistryService registryService,
                             GatewayMcpServerSessionRepository serverSessionRepository,
                             ApplicationEventPublisher eventPublisher) {
        this.auditService = auditService;
        this.registryService = registryService;
        this.serverSessionRepository = serverSessionRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = new ObjectMapper();
    }

    public synchronized void connect(String serverName, McpServerConfig config, String wsTenantName) throws Exception {
        HttpMcpTransport.clearRequestOverrideHeaders();

        if (sessions.containsKey(serverName)) {
 log.warn("⚠ Server '{}' is already connected. Disconnecting first...", serverName);
            disconnect(serverName);
        }

 log.info("Connecting to MCP server '{}'...", serverName);
        long startTime = System.currentTimeMillis();

        try {
            config.validate();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            auditService.auditClientSessionInitFailed(null, serverName,
                    "Invalid configuration: " + e.getMessage(), duration);
            throw new RuntimeException("Invalid configuration for server '" + serverName + "': " + e.getMessage(), e);
        }

        try {
            Map<String, String> headers = config.getHeaders();

 log.info("Creating HTTP transport for: {}", config.getUrl());
            HttpMcpTransport httpTransport = new HttpMcpTransport(
                    config.getUrl(),
                    headers,
                    config.getTimeout()
            );

            McpClientTransport mcpTransport = httpTransport;

            McpSchema.ClientCapabilities capabilities = new McpSchema.ClientCapabilities(
                    Map.of(),
                    null,
                    null,
                    null
            );

 log.info("Building MCP client for '{}'...", serverName);
            McpSyncClient client = McpClient.sync(mcpTransport)
                    .clientInfo(new McpSchema.Implementation(
                            "ws-agentic-gateway", "1.0.0"))
                    .capabilities(capabilities)
                    .toolsChangeConsumer(updatedTools -> {
 log.info("WS Client-side notification: tools/list_changed from '{}' — re-fetching capabilities", serverName);
                        handleSouthboundCapabilityChange(serverName);
                    })
                    .build();

 log.info("Initializing MCP client for '{}'...", serverName);

            client.initialize();

            if (!client.isInitialized()) {
                long duration = System.currentTimeMillis() - startTime;
                auditService.auditClientSessionInitFailed(null, serverName,
                        "Client initialization returned false", duration);
                throw new RuntimeException("Client initialization failed for server: " + serverName);
            }

            McpSession session = new McpSession(serverName, client, httpTransport, wsTenantName);

            session.setServerInfo(client.getServerInfo());
            session.setCapabilities(client.getServerCapabilities());

 log.info("Server '{}' connected successfully", serverName);
            log.info("Server: {} v{}",
                    client.getServerInfo().name(),
                    client.getServerInfo().version());

            fetchAndCacheTools(serverName, session);

            sessions.put(serverName, session);

            try {
                JsonNode capsJson = null;
                if (client.getServerCapabilities() != null) {
                    capsJson = objectMapper.valueToTree(Map.of(
                            "tools", client.getServerCapabilities().tools() != null,
                            "resources", client.getServerCapabilities().resources() != null,
                            "prompts", client.getServerCapabilities().prompts() != null,
                            "logging", client.getServerCapabilities().logging() != null
                    ));
                }

                registryService.registerServer(
                        session.getSessionId(),
                        serverName,
                        client.getServerInfo() != null ? client.getServerInfo().name() : serverName,
                        client.getServerInfo() != null ? client.getServerInfo().version() : null,
                        null,
                        capsJson,
                        session.getTools(),
                        session.getResources(),
                        session.getPrompts(),
                        wsTenantName
                );
 log.info("Server '{}' capabilities registered in registry", serverName);
                eventPublisher.publishEvent(new CapabilityRegistryChangedEvent("SERVER_CONNECTED", serverName));
            } catch (Exception regEx) {
 log.error("⚠ Failed to register server '{}' in registry: {}",
                        serverName, regEx.getMessage(), regEx);
            }

            try {
                GatewayMcpServerSessionEntity entity = GatewayMcpServerSessionEntity.builder()
                        .serverName(serverName)
                        .sessionId(session.getSessionId())
                        .serverDisplayName(client.getServerInfo() != null ? client.getServerInfo().name() : serverName)
                        .serverVersion(client.getServerInfo() != null ? client.getServerInfo().version() : null)
                        .serverUrl(config.getUrl())
                        .toolCount(session.getTools() != null ? session.getTools().size() : 0)
                        .resourceCount(session.getResources() != null ? session.getResources().size() : 0)
                        .promptCount(session.getPrompts() != null ? session.getPrompts().size() : 0)
                        .status("CONNECTED")
                        .wsTenantName(wsTenantName)
                        .build();
                serverSessionRepository.save(entity);
 log.info("WS Client-side session persisted for server '{}'", serverName);
            } catch (Exception dbEx) {
 log.error("⚠ Failed to persist WS Client-side session for '{}': {}",
                        serverName, dbEx.getMessage());
            }

            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> serverInfoMap = new HashMap<>();
            if (client.getServerInfo() != null) {
                serverInfoMap.put("name", client.getServerInfo().name());
                serverInfoMap.put("version", client.getServerInfo().version());
            }

            Map<String, Object> capsMap = new HashMap<>();
            if (client.getServerCapabilities() != null) {
                capsMap.put("tools", client.getServerCapabilities().tools() != null);
                capsMap.put("resources", client.getServerCapabilities().resources() != null);
                capsMap.put("prompts", client.getServerCapabilities().prompts() != null);
                capsMap.put("logging", client.getServerCapabilities().logging() != null);
            }

            auditService.auditClientSessionInitialized(
                    session.getSessionId(), serverName, null, serverInfoMap, capsMap, duration);

 log.info("Server '{}' ready with {} tools",
                    serverName, session.getToolCount());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            auditService.auditClientSessionInitFailed(null, serverName, e.getMessage(), duration);
 log.error("Failed to connect to server '{}': {}", serverName, e.getMessage(), e);
            throw new RuntimeException("Failed to connect to MCP server '" + serverName + "': " + e.getMessage(), e);
        } finally {
            HttpMcpTransport.clearRequestOverrideHeaders();
        }
    }

    private void fetchAndCacheTools(String serverName, McpSession session) {
        try {
            McpSyncClient client = session.getClient();

            if (session.getCapabilities() != null &&
                    session.getCapabilities().tools() != null) {

 log.info("Fetching tools for server '{}'...", serverName);
                long toolsStart = System.currentTimeMillis();
                List<McpSchema.Tool> tools = client.listTools().tools();
                session.setTools(tools);
                long toolsDuration = System.currentTimeMillis() - toolsStart;

                log.info("Found {} tools:", tools.size());
                tools.forEach(tool ->
                        log.info("- {} : {}", tool.name(),
                                tool.description() != null ? tool.description() : "No description")
                );

                List<String> toolNames = tools.stream().map(McpSchema.Tool::name).toList();
                auditService.auditClientToolsListFetched(session.getSessionId(), serverName, tools.size(), toolNames, toolsDuration);
            } else {
 log.info("ℹ Server '{}' does not support tools capability", serverName);
            }

            if (session.getCapabilities() != null &&
                    session.getCapabilities().resources() != null) {

 log.info("Fetching resources for server '{}'...", serverName);
                long resStart = System.currentTimeMillis();
                List<McpSchema.Resource> resources = client.listResources().resources();
                session.setResources(resources);
                long resDuration = System.currentTimeMillis() - resStart;
                log.info("Found {} resources", resources.size());

                List<String> resourceUris = resources.stream().map(McpSchema.Resource::uri).toList();
                auditService.auditClientResourcesListFetched(session.getSessionId(), serverName, resources.size(), resourceUris, resDuration);
            }

            if (session.getCapabilities() != null &&
                    session.getCapabilities().prompts() != null) {

 log.info("Fetching prompts for server '{}'...", serverName);
                long promptStart = System.currentTimeMillis();
                List<McpSchema.Prompt> prompts = client.listPrompts().prompts();
                session.setPrompts(prompts);
                long promptDuration = System.currentTimeMillis() - promptStart;
                log.info("Found {} prompts", prompts.size());

                List<String> promptNames = prompts.stream().map(McpSchema.Prompt::name).toList();
                auditService.auditClientPromptsListFetched(session.getSessionId(), serverName, prompts.size(), promptNames, promptDuration);
            }

        } catch (Exception e) {
 log.error("⚠ Failed to fetch capabilities for '{}': {}", serverName, e.getMessage());
            auditService.auditClientToolsListFailed(session.getSessionId(), serverName, e.getMessage(), 0);
        }
    }

    private void handleSouthboundCapabilityChange(String serverName) {
        McpSession session = sessions.get(serverName);
        if (session == null) {
            log.warn("Received tool change notification for unknown server '{}' — ignoring", serverName);
            return;
        }

        auditService.auditClientNotificationReceived(
                session.getSessionId(), serverName, "notifications/tools/list_changed");

        try {
            fetchAndCacheTools(serverName, session);

            JsonNode capsJson = null;
            if (session.getCapabilities() != null) {
                capsJson = objectMapper.valueToTree(Map.of(
                        "tools", session.getCapabilities().tools() != null,
                        "resources", session.getCapabilities().resources() != null,
                        "prompts", session.getCapabilities().prompts() != null,
                        "logging", session.getCapabilities().logging() != null
                ));
            }

            registryService.registerServer(
                    session.getSessionId(),
                    serverName,
                    session.getServerInfo() != null ? session.getServerInfo().name() : serverName,
                    session.getServerInfo() != null ? session.getServerInfo().version() : null,
                    null,
                    capsJson,
                    session.getTools(),
                    session.getResources(),
                    session.getPrompts(),
                    session.getWsTenantName()
            );

 log.info("Server '{}' capabilities refreshed after WS Client-side notification", serverName);

            eventPublisher.publishEvent(
                    new CapabilityRegistryChangedEvent("SOUTHBOUND_TOOLS_CHANGED", serverName));

        } catch (Exception e) {
 log.error("⚠ Failed to handle WS Client-side capability change for '{}': {}",
                    serverName, e.getMessage(), e);
        }
    }

    public McpSession getSession(String serverName) {
        McpSession session = sessions.get(serverName);
        if (session == null) {
            throw new IllegalArgumentException("Server '" + serverName + "' is not connected");
        }
        return session;
    }

    public McpSyncClient getClient(String serverName) {
        return getSession(serverName).getClient();
    }

    public Map<String, McpSession> getAllSessions() {
        return Map.copyOf(sessions);
    }

    public List<String> getServerNames() {
        return List.copyOf(sessions.keySet());
    }

    public boolean isConnected(String serverName) {
        McpSession session = sessions.get(serverName);
        if (session == null) {
            log.debug("Connectivity check failed for '{}': no in-memory session", serverName);
            return false;
        }

        boolean dbConnected = serverSessionRepository
                .findByServerNameAndStatus(serverName, "CONNECTED")
                .isPresent();
        if (!dbConnected) {
            log.debug("Connectivity check failed for '{}': DB status is not CONNECTED", serverName);
            return false;
        }

        boolean transportConnected = session.getTransport() != null && session.getTransport().isConnected();
        if (!transportConnected) {
            log.warn("Connectivity check failed for '{}': session='{}', dbStatus='CONNECTED', transportConnected=false",
                    serverName, session.getSessionId());
            return false;
        }

        if (!session.isActive()) {
            log.warn("Connectivity check note for '{}': session='{}' transportConnected=true but clientInitialized=false",
                    serverName, session.getSessionId());
        }

        return true;
    }

    @Transactional
    public synchronized void disconnect(String serverName) {
        McpSession session = sessions.remove(serverName);
        if (session != null) {
 log.info("Disconnecting server '{}'...", serverName);
            try {
                session.close();
 log.info("Server '{}' disconnected successfully", serverName);
            } catch (Exception e) {
 log.error("⚠ Error during disconnect for '{}': {}", serverName, e.getMessage());
            }

            if (!shuttingDown) {
                try {
                    auditService.auditClientSessionDisconnectedSync(session.getSessionId(), serverName);
                } catch (Exception e) {
 log.error("⚠ Failed audit for '{}': {}", serverName, e.getMessage());
                }

                try {
                    int updated = serverSessionRepository.markDisconnected(serverName);
                    if (updated > 0) {
 log.info("WS Client-side session marked DISCONNECTED for '{}' (rows={})",
                                serverName, updated);
                    } else {
 log.warn("⚠ No CONNECTED WS Client-side DB session row found to disconnect for '{}'",
                                serverName);
                    }
                } catch (Exception dbEx) {
 log.error("⚠ Failed to mark WS Client-side session DISCONNECTED for '{}': {}",
                            serverName, dbEx.getMessage(), dbEx);
                }

                try {
                    registryService.removeServer(session.getSessionId(), serverName);
                    eventPublisher.publishEvent(new CapabilityRegistryChangedEvent("SERVER_DISCONNECTED", serverName));
                } catch (Exception e) {
 log.error("⚠ Failed to remove server '{}' from registry: {}",
                            serverName, e.getMessage());
                }
            }
        } else {
 log.warn("⚠ Server '{}' was not connected", serverName);
        }
    }

    @Transactional
    public synchronized void shutdown() {
 log.info("Gateway shutting down — disconnecting all WS Client-side MCP servers...");
        shuttingDown = true;

        try {
            serverSessionRepository.markAllDisconnected();
 log.info("All WS Client-side sessions marked DISCONNECTED in DB");
        } catch (Exception e) {
 log.error("⚠ Failed to mark sessions DISCONNECTED in DB: {}", e.getMessage());
        }

        for (String serverName : List.copyOf(sessions.keySet())) {
            McpSession session = sessions.remove(serverName);
            if (session != null) {
                try {
                    auditService.auditClientSessionDisconnectedSync(session.getSessionId(), serverName);
                } catch (Exception e) {
 log.error("⚠ Failed shutdown audit for '{}': {}", serverName, e.getMessage());
                }
                try {
                    session.close();
 log.info("Server '{}' disconnected", serverName);
                } catch (Exception e) {
 log.error("⚠ Error closing '{}': {}", serverName, e.getMessage());
                }
            }
        }
        sessions.clear();
 log.info("All WS Client-side sessions disconnected gracefully");
    }

    @Transactional
    public void cleanupOrphanedSessions() {
        try {
            List<GatewayMcpServerSessionEntity> orphaned = serverSessionRepository.findByStatus("CONNECTED");
            if (!orphaned.isEmpty()) {
                for (GatewayMcpServerSessionEntity session : orphaned) {
                    auditService.auditClientSessionDisconnectedSync(session.getSessionId(), session.getServerName());
                }
                serverSessionRepository.markAllDisconnected();
 log.info("Startup cleanup: marked {} orphaned WS Client-side session(s) as DISCONNECTED", orphaned.size());
            } else {
 log.info("Startup cleanup: no orphaned WS Client-side sessions found");
            }
        } catch (Exception e) {
 log.error("⚠ Failed to cleanup orphaned WS Client-side sessions: {}", e.getMessage());
        }
    }
}
