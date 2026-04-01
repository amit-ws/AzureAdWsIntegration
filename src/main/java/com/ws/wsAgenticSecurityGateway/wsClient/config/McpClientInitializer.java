package com.ws.wsAgenticSecurityGateway.wsClient.config;

import com.ws.wsAgenticSecurityGateway.wsClient.entity.GatewayServerConfigEntity;
import com.ws.wsAgenticSecurityGateway.wsClient.service.ServerConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
@Order(1)
public class McpClientInitializer implements ApplicationRunner {

    private final ServerConfigService serverConfigService;
    private final McpSessionManager sessionManager;

    public McpClientInitializer(ServerConfigService serverConfigService,
                                McpSessionManager sessionManager) {
        this.serverConfigService = serverConfigService;
        this.sessionManager = sessionManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        sessionManager.cleanupOrphanedSessions();

        log.info("MCP CLIENT INITIALIZATION STARTED");

        try {
            log.info("Loading MCP server configurations from database...");
            List<GatewayServerConfigEntity> configs = serverConfigService.getStartupConfigs();

            if (configs.isEmpty()) {
                log.info("No auto-connect MCP servers configured. Skipping initialization.");
                log.info("Use POST /api/admin/mcp-servers to add server configurations.");
                return;
            }

            log.info("Found {} MCP server(s) for auto-connect", configs.size());

            int successCount = 0;
            int failureCount = 0;

            for (GatewayServerConfigEntity config : configs) {
                log.info("-----------------------------------------------------------");
                log.info("Connecting to server: {}", config.getServerName());
                log.info("Type: {}", config.getType());
                log.info("URL: {}", config.getUrl());

                try {
                    serverConfigService.connectFromConfig(config);
                    successCount++;
                    log.info("Server '{}' initialized successfully", config.getServerName());

                } catch (Exception e) {
                    failureCount++;
                    log.error("Failed to initialize server '{}': {}",
                            config.getServerName(), e.getMessage());
                }
            }

            log.info("MCP CLIENT INITIALIZATION COMPLETED");
            log.info("Success: {} server(s)", successCount);
            if (failureCount > 0) {
                log.info("Failed:  {} server(s)", failureCount);
            }

            printConnectionSummary();

        } catch (Exception e) {
            log.error("MCP CLIENT INITIALIZATION FAILED");
            log.error("Error: {}", e.getMessage(), e);
        }
    }

    private void printConnectionSummary() {
        Map<String, McpSession> sessions = sessionManager.getAllSessions();

        if (sessions.isEmpty()) {
            log.info("No active MCP connections");
            return;
        }

        log.info("ACTIVE MCP CONNECTIONS:");
        log.info("-----------------------------------------------------------");

        sessions.forEach((name, session) -> {
            log.info("Server: {}", name);
            log.info("Status: {}", session.isActive() ? "ACTIVE" : "INACTIVE");

            if (session.getServerInfo() != null) {
                log.info("Name: {} v{}",
                        session.getServerInfo().name(),
                        session.getServerInfo().version());
            }

            log.info("Tools: {}", session.getToolCount());
            log.info("Connected: {}", session.getConnectedAt());
            log.info("-----------------------------------------------------------");
        });
    }
}
