package com.ws.wsAgenticSecurityGateway.wsClient.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.wsClient.entity.GatewayMcpServerSessionEntity;
import com.ws.wsAgenticSecurityGateway.wsClient.repository.GatewayMcpServerSessionRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multiple HTTP-based MCP server connections.
 * Handles connection lifecycle, tool caching, and session state.
 */
@Component
@Slf4j
public class McpSessionManager {

    // Thread-safe map to store all active sessions
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();

    // Flag to skip audit/registry during shutdown (beans may be destroyed)
    private volatile boolean shuttingDown = false;

    // Audit service — all audit calls are @Async, never blocks
    private final McpAuditService auditService;

    // Capability Registry — persists discovered capabilities from enterprise servers
    private final CapabilityRegistryService registryService;

    private final ObjectMapper objectMapper;

    private final GatewayMcpServerSessionRepository serverSessionRepository;

    public McpSessionManager(McpAuditService auditService,
                             CapabilityRegistryService registryService,
                             GatewayMcpServerSessionRepository serverSessionRepository) {
        this.auditService = auditService;
        this.registryService = registryService;
        this.serverSessionRepository = serverSessionRepository;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Connect to an HTTP-based MCP server
     *
     * @param serverName Unique name for this server (from config key)
     * @param config Server configuration (url, headers, etc.)
     * @throws Exception if connection fails
     */
    public synchronized void connect(String serverName, McpServerConfig config) throws Exception {

        if (sessions.containsKey(serverName)) {
            log.warn("⚠️  Server '{}' is already connected. Disconnecting first...", serverName);
            disconnect(serverName);
        }

        log.info("🔌 Connecting to MCP server '{}'...", serverName);
        long startTime = System.currentTimeMillis();

        // Validate config
        try {
            config.validate();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            auditService.auditClientSessionInitFailed(null, serverName,
                    "Invalid configuration: " + e.getMessage(), duration);
            throw new RuntimeException("Invalid configuration for server '" + serverName + "': " + e.getMessage(), e);
        }

        try {
            // Headers arrive pre-resolved from ServerConfigService
            Map<String, String> headers = config.getHeaders();

            // Create HTTP transport
            log.info("🌐 Creating HTTP transport for: {}", config.getUrl());
            HttpMcpTransport httpTransport = new HttpMcpTransport(
                    config.getUrl(),
                    headers,
                    config.getTimeout()
            );

            // Wrap in MCP transport interface
            McpClientTransport mcpTransport = httpTransport;

            // Define client capabilities
            McpSchema.ClientCapabilities capabilities = new McpSchema.ClientCapabilities(
                    Map.of(),  // No experimental features
                    null,      // No roots
                    null,      // No sampling
                    null       // No custom capabilities
            );

            // Build sync client
            log.info("🔄 Building MCP client for '{}'...", serverName);
            McpSyncClient client = McpClient.sync(mcpTransport)
                    .clientInfo(new McpSchema.Implementation(
                            "ws-agentic-gateway", "1.0.0"))
                    .capabilities(capabilities)
                    .build();

            log.info("🔄 Initializing MCP client for '{}'...", serverName);

            // Initialize connection (handshake)
            client.initialize();

            if (!client.isInitialized()) {
                long duration = System.currentTimeMillis() - startTime;
                auditService.auditClientSessionInitFailed(null, serverName,
                        "Client initialization returned false", duration);
                throw new RuntimeException("Client initialization failed for server: " + serverName);
            }

            // Create session
            McpSession session = new McpSession(serverName, client, httpTransport);

            // Cache server metadata
            session.setServerInfo(client.getServerInfo());
            session.setCapabilities(client.getServerCapabilities());

            log.info("✅ Server '{}' connected successfully", serverName);
            log.info("   Server: {} v{}",
                    client.getServerInfo().name(),
                    client.getServerInfo().version());

            // Fetch and cache tools, resources, prompts
            fetchAndCacheTools(serverName, session);

            // Store session
            sessions.put(serverName, session);

            // ── Register discovered capabilities in the registry ───────
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
                        null,  // protocolVersion — not exposed by McpSyncClient
                        capsJson,
                        session.getTools(),
                        session.getResources(),
                        session.getPrompts()
                );
                log.info("🗂️  Server '{}' capabilities registered in registry", serverName);
            } catch (Exception regEx) {
                log.error("⚠️  Failed to register server '{}' in registry: {}",
                        serverName, regEx.getMessage(), regEx);
                // Don't throw — connection is still valid even if registry fails
            }

            // ── Persist southbound session to DB ──────────────────────
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
                        .build();
                serverSessionRepository.save(entity);
                log.info("💾 Southbound session persisted for server '{}'", serverName);
            } catch (Exception dbEx) {
                log.error("⚠️  Failed to persist southbound session for '{}': {}",
                        serverName, dbEx.getMessage());
                // Don't throw — connection is still valid even if DB persistence fails
            }

            long duration = System.currentTimeMillis() - startTime;

            // Build server info map for audit
            Map<String, Object> serverInfoMap = new HashMap<>();
            if (client.getServerInfo() != null) {
                serverInfoMap.put("name", client.getServerInfo().name());
                serverInfoMap.put("version", client.getServerInfo().version());
            }

            // Build capabilities map for audit
            Map<String, Object> capsMap = new HashMap<>();
            if (client.getServerCapabilities() != null) {
                capsMap.put("tools", client.getServerCapabilities().tools() != null);
                capsMap.put("resources", client.getServerCapabilities().resources() != null);
                capsMap.put("prompts", client.getServerCapabilities().prompts() != null);
                capsMap.put("logging", client.getServerCapabilities().logging() != null);
            }

            // Async audit — session initialization success
            auditService.auditClientSessionInitialized(
                    session.getSessionId(), serverName, null, serverInfoMap, capsMap, duration);

            log.info("🎉 Server '{}' ready with {} tools",
                    serverName, session.getToolCount());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            auditService.auditClientSessionInitFailed(null, serverName, e.getMessage(), duration);
            log.error("❌ Failed to connect to server '{}': {}", serverName, e.getMessage(), e);
            throw new RuntimeException("Failed to connect to MCP server '" + serverName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Fetch tools from server and cache in session
     */
    private void fetchAndCacheTools(String serverName, McpSession session) {
        try {
            McpSyncClient client = session.getClient();

            // Check if server supports tools
            if (session.getCapabilities() != null &&
                    session.getCapabilities().tools() != null) {

                log.info("🔧 Fetching tools for server '{}'...", serverName);
                long toolsStart = System.currentTimeMillis();
                List<McpSchema.Tool> tools = client.listTools().tools();
                session.setTools(tools);
                long toolsDuration = System.currentTimeMillis() - toolsStart;

                log.info("   Found {} tools:", tools.size());
                tools.forEach(tool ->
                        log.info("      - {} : {}", tool.name(),
                                tool.description() != null ? tool.description() : "No description")
                );

                // Audit — tools list fetched
                List<String> toolNames = tools.stream().map(McpSchema.Tool::name).toList();
                auditService.auditClientToolsListFetched(session.getSessionId(), serverName, tools.size(), toolNames, toolsDuration);
            } else {
                log.info("ℹ️  Server '{}' does not support tools capability", serverName);
            }

            // Optionally fetch resources
            if (session.getCapabilities() != null &&
                    session.getCapabilities().resources() != null) {

                log.info("📦 Fetching resources for server '{}'...", serverName);
                long resStart = System.currentTimeMillis();
                List<McpSchema.Resource> resources = client.listResources().resources();
                session.setResources(resources);
                long resDuration = System.currentTimeMillis() - resStart;
                log.info("   Found {} resources", resources.size());

                // Audit — resources list fetched
                List<String> resourceUris = resources.stream().map(McpSchema.Resource::uri).toList();
                auditService.auditClientResourcesListFetched(session.getSessionId(), serverName, resources.size(), resourceUris, resDuration);
            }

            // Optionally fetch prompts
            if (session.getCapabilities() != null &&
                    session.getCapabilities().prompts() != null) {

                log.info("💬 Fetching prompts for server '{}'...", serverName);
                long promptStart = System.currentTimeMillis();
                List<McpSchema.Prompt> prompts = client.listPrompts().prompts();
                session.setPrompts(prompts);
                long promptDuration = System.currentTimeMillis() - promptStart;
                log.info("   Found {} prompts", prompts.size());

                // Audit — prompts list fetched
                List<String> promptNames = prompts.stream().map(McpSchema.Prompt::name).toList();
                auditService.auditClientPromptsListFetched(session.getSessionId(), serverName, prompts.size(), promptNames, promptDuration);
            }

        } catch (Exception e) {
            log.error("⚠️  Failed to fetch capabilities for '{}': {}", serverName, e.getMessage());
            auditService.auditClientToolsListFailed(session.getSessionId(), serverName, e.getMessage(), 0);
            // Don't throw - connection is still valid even if fetching fails
        }
    }

    /**
     * Get a specific session by server name
     */
    public McpSession getSession(String serverName) {
        McpSession session = sessions.get(serverName);
        if (session == null) {
            throw new IllegalArgumentException("Server '" + serverName + "' is not connected");
        }
        return session;
    }

    /**
     * Get client for a specific server
     */
    public McpSyncClient getClient(String serverName) {
        return getSession(serverName).getClient();
    }

    /**
     * Get all active sessions
     */
    public Map<String, McpSession> getAllSessions() {
        return Map.copyOf(sessions);
    }

    /**
     * Get all server names
     */
    public List<String> getServerNames() {
        return List.copyOf(sessions.keySet());
    }

    /**
     * Check if server is connected — checks BOTH in-memory session AND DB status.
     * The DB is the source of truth (admin can mark DISCONNECTED via UI/API),
     * while in-memory confirms the transport is alive.
     */
    public boolean isConnected(String serverName) {
        McpSession session = sessions.get(serverName);
        if (session == null || !session.isActive()) {
            return false;
        }
        // DB is source of truth — admin disconnect or shutdown marks DB DISCONNECTED
        // even if in-memory session still exists
        return serverSessionRepository
                .findByServerNameAndStatus(serverName, "CONNECTED")
                .isPresent();
    }

    /**
     * Disconnect a specific server (normal runtime disconnect)
     */
    @Transactional
    public synchronized void disconnect(String serverName) {
        McpSession session = sessions.remove(serverName);
        if (session != null) {
            log.info("🔌 Disconnecting server '{}'...", serverName);
            try {
                session.close();
                log.info("✅ Server '{}' disconnected successfully", serverName);
            } catch (Exception e) {
                log.error("⚠️  Error during disconnect for '{}': {}", serverName, e.getMessage());
            }

            // During shutdown, skip audit + registry — beans may already be destroyed
            if (!shuttingDown) {
                try {
                    auditService.auditClientSessionDisconnectedSync(session.getSessionId(), serverName);
                } catch (Exception e) {
                    log.error("⚠️  Failed audit for '{}': {}", serverName, e.getMessage());
                }

                // Mark southbound session as disconnected in DB
                try {
                    int updated = serverSessionRepository.markDisconnected(serverName);
                    if (updated > 0) {
                        log.info("💾 Southbound session marked DISCONNECTED for '{}' (rows={})",
                                serverName, updated);
                    } else {
                        log.warn("⚠️  No CONNECTED southbound DB session row found to disconnect for '{}'",
                                serverName);
                    }
                } catch (Exception dbEx) {
                    log.error("⚠️  Failed to mark southbound session DISCONNECTED for '{}': {}",
                            serverName, dbEx.getMessage(), dbEx);
                }

                // Remove capabilities from registry
                try {
                    registryService.removeServer(session.getSessionId(), serverName);
                } catch (Exception e) {
                    log.error("⚠️  Failed to remove server '{}' from registry: {}",
                            serverName, e.getMessage());
                }
            }
        } else {
            log.warn("⚠️  Server '{}' was not connected", serverName);
        }
    }

    /**
     * Graceful shutdown — called by {@link GatewayShutdownHook} which runs
     * BEFORE Spring destroys beans, so EntityManager and transactions still work.
     */
    @Transactional
    public synchronized void shutdown() {
        log.info("🛑 Gateway shutting down — disconnecting all southbound MCP servers...");
        shuttingDown = true;

        // 1. Bulk-update DB: mark ALL connected sessions as DISCONNECTED in one query
        try {
            serverSessionRepository.markAllDisconnected();
            log.info("💾 All southbound sessions marked DISCONNECTED in DB");
        } catch (Exception e) {
            log.error("⚠️  Failed to mark sessions DISCONNECTED in DB: {}", e.getMessage());
        }

        // 2. Audit + close each MCP client connection
        for (String serverName : List.copyOf(sessions.keySet())) {
            McpSession session = sessions.remove(serverName);
            if (session != null) {
                // Audit log — sync call, beans are still alive at ContextClosedEvent
                try {
                    auditService.auditClientSessionDisconnectedSync(session.getSessionId(), serverName);
                } catch (Exception e) {
                    log.error("⚠️  Failed shutdown audit for '{}': {}", serverName, e.getMessage());
                }
                try {
                    session.close();
                    log.info("✅ Server '{}' disconnected", serverName);
                } catch (Exception e) {
                    log.error("⚠️  Error closing '{}': {}", serverName, e.getMessage());
                }
            }
        }
        sessions.clear();
        log.info("✅ All southbound sessions disconnected gracefully");
    }

    /**
     * Get connection status summary
     */
    public Map<String, Object> getStatusSummary() {
        return Map.of(
                "totalServers", sessions.size(),
                "activeServers", sessions.values().stream()
                        .filter(McpSession::isActive)
                        .count(),
                "servers", sessions.keySet()
        );
    }

    /**
     * Startup cleanup — mark all orphaned CONNECTED southbound sessions as DISCONNECTED.
     * Called explicitly from {@code McpClientInitializer.run()} BEFORE creating new connections.
     */
    @Transactional
    public void cleanupOrphanedSessions() {
        try {
            List<GatewayMcpServerSessionEntity> orphaned = serverSessionRepository.findByStatus("CONNECTED");
            if (!orphaned.isEmpty()) {
                for (GatewayMcpServerSessionEntity session : orphaned) {
                    auditService.auditClientSessionDisconnectedSync(session.getSessionId(), session.getServerName());
                }
                serverSessionRepository.markAllDisconnected();
                log.info("🧹 Startup cleanup: marked {} orphaned southbound session(s) as DISCONNECTED", orphaned.size());
            } else {
                log.info("🧹 Startup cleanup: no orphaned southbound sessions found");
            }
        } catch (Exception e) {
            log.error("⚠️  Failed to cleanup orphaned southbound sessions: {}", e.getMessage());
        }
    }
}
