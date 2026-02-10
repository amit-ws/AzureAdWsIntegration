package com.ws.wsAgenticSecurity.server;

import com.ws.wsAgenticSecurity.server.session.SessionManager;
import com.ws.wsAgenticSecurity.server.tools.DatabaseQueryTool;
import com.ws.wsAgenticSecurity.server.tools.FileOperationsTool;
import com.ws.wsAgenticSecurity.server.transport.ServerTransportProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;


/*
*
* Option 1: Standalone Executable (RECOMMENDED)
The MCP server runs as a completely separate process from your Spring Boot web app.
*
* */

@Slf4j
public class McpServerApplication {

    public static void main(String[] args) {
        try {
            log.info("Starting Java MCP Server...");

            // Session manager for tracking client data
            SessionManager sessionManager = new SessionManager();

            // Create transport provider
            ServerTransportProvider transportProvider = new ServerTransportProvider(sessionManager);

            // Create tools
            DatabaseQueryTool dbTool = new DatabaseQueryTool();
            FileOperationsTool fileTool = new FileOperationsTool();

            // Configure server capabilities
            McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
                    .tools(true) // Enable tools with list change notifications. Server will send notifications/tools/list_changed when tools change
                    .build();

            // Build MCP Server using SDK
            McpSyncServer server = McpServer.sync(transportProvider)
                    .serverInfo("java-mcp-server", "1.0.0")
                    .capabilities(capabilities)
                    .toolCall(dbTool.getDefinition(), dbTool::execute)
                    .toolCall(fileTool.getDefinition(), fileTool::execute)
                    .build();

            log.info("Java MCP Server started successfully");
            log.info("Server: java-mcp-server v1.0.0");
            log.info("Tools: database_query, file_operations");
            log.info("Waiting for client connection via stdio...");

            // Keep server running
            Thread.currentThread().join();

        } catch (Exception e) {
            log.error("Failed to start MCP Server", e);
            System.exit(1);
        }
    }
}