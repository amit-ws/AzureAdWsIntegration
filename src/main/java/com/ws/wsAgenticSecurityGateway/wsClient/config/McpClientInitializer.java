package com.ws.wsAgenticSecurityGateway.wsClient.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Application initializer that auto-connects to all configured MCP servers on startup.
 * Implements ApplicationRunner to execute after Spring context is fully initialized.
 *
 * <p>{@code @Order(1)} ensures this runs <strong>before</strong> the MCP Server
 * initializer ({@code @Order(2)}) so that the capability registry is populated
 * before the server exposes tools to AI agents.
 */
@Component
@Slf4j
@Order(1)
public class McpClientInitializer implements ApplicationRunner {

    private final McpConfigLoader configLoader;
    private final McpSessionManager sessionManager;

    public McpClientInitializer(McpConfigLoader configLoader,
                                McpSessionManager sessionManager) {
        this.configLoader = configLoader;
        this.sessionManager = sessionManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("🚀 MCP CLIENT INITIALIZATION STARTED");
        log.info("═══════════════════════════════════════════════════════════");

        try {
            // Load configuration
            log.info("📂 Loading MCP configuration...");
            McpConfigFile config = configLoader.loadConfig();

            if (config.getMcpServers() == null || config.getMcpServers().isEmpty()) {
                log.warn("⚠️  No MCP servers configured. Skipping initialization.");
                return;
            }

            log.info("🔍 Found {} MCP server(s) in configuration",
                    config.getMcpServers().size());

            // Create variable resolver
            ConfigVariableResolver resolver = configLoader.createResolver(config);
            sessionManager.setVariableResolver(resolver);
            log.info("✅ Variable resolver initialized");

            // Connect to each server
            int successCount = 0;
            int failureCount = 0;

            for (Map.Entry<String, McpServerConfig> entry : config.getMcpServers().entrySet()) {
                String serverName = entry.getKey();
                McpServerConfig serverConfig = entry.getValue();

                log.info("───────────────────────────────────────────────────────────");
                log.info("🔌 Connecting to server: {}", serverName);
                log.info("   Type: {}", serverConfig.getType());
                log.info("   URL: {}", serverConfig.getUrl());

                try {
                    sessionManager.connect(serverName, serverConfig);
                    successCount++;
                    log.info("✅ Server '{}' initialized successfully", serverName);

                } catch (Exception e) {
                    failureCount++;
                    log.error("❌ Failed to initialize server '{}': {}",
                            serverName, e.getMessage());
                    // Continue with other servers even if one fails
                }
            }

            log.info("═══════════════════════════════════════════════════════════");
            log.info("🎉 MCP CLIENT INITIALIZATION COMPLETED");
            log.info("   ✅ Success: {} server(s)", successCount);
            if (failureCount > 0) {
                log.info("   ❌ Failed:  {} server(s)", failureCount);
            }
            log.info("═══════════════════════════════════════════════════════════");

            // Print summary
            printConnectionSummary();

        } catch (Exception e) {
            log.error("═══════════════════════════════════════════════════════════");
            log.error("❌ MCP CLIENT INITIALIZATION FAILED");
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Error: {}", e.getMessage(), e);
            // Don't throw - allow application to start even if MCP init fails
        }
    }

    /**
     * Print a summary of all connected servers
     */
    private void printConnectionSummary() {
        Map<String, McpSession> sessions = sessionManager.getAllSessions();

        if (sessions.isEmpty()) {
            log.info("📊 No active MCP connections");
            return;
        }

        log.info("📊 ACTIVE MCP CONNECTIONS:");
        log.info("───────────────────────────────────────────────────────────");

        sessions.forEach((name, session) -> {
            log.info("🖥️  Server: {}", name);
            log.info("   Status: {}", session.isActive() ? "ACTIVE" : "INACTIVE");

            if (session.getServerInfo() != null) {
                log.info("   Name: {} v{}",
                        session.getServerInfo().name(),
                        session.getServerInfo().version());
            }

            log.info("   Tools: {}", session.getToolCount());
            log.info("   Connected: {}", session.getConnectedAt());
            log.info("   ───────────────────────────────────────────────────");
        });
    }
}
