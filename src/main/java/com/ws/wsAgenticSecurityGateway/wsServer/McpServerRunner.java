//package com.ws.wsAgenticSecurity.server;
//
//
//import com.ws.wsAgenticSecurity.server.session.SessionManager;
//import com.ws.wsAgenticSecurity.server.tools.DatabaseQueryTool;
//import com.ws.wsAgenticSecurity.server.tools.FileOperationsTool;
//import com.ws.wsAgenticSecurity.server.transport.ServerTransportProvider;
//import io.modelcontextprotocol.server.McpServer;
//import io.modelcontextprotocol.server.McpSyncServer;
//import io.modelcontextprotocol.spec.McpSchema;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.boot.CommandLineRunner;
//import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
//import org.springframework.stereotype.Component;
//
//
///*
//*
//* [Option 2] Spring Boot CommandLineRunner (If you want both together)
//MCP server starts automatically when Spring Boot starts, but runs in a separate thread.
//*
//* */
//@Component
//@ConditionalOnProperty(name = "mcp.server.enabled", havingValue = "true", matchIfMissing = false)
//@Slf4j
//public class McpServerRunner implements CommandLineRunner {
//
//    @Override
//    public void run(String... args) {
//        // Run MCP server in a separate thread so it doesn't block Spring Boot
//        Thread serverThread = new Thread(() -> {
//            try {
//                log.info("🚀 Starting MCP Server in background...");
//
//                SessionManager sessionManager = new SessionManager();
//                ServerTransportProvider transportProvider = new ServerTransportProvider(sessionManager);
//
//                DatabaseQueryTool dbTool = new DatabaseQueryTool();
//                FileOperationsTool fileTool = new FileOperationsTool();
//
//                McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
//                        .tools(true)
//                        .build();
//
//                McpSyncServer server = McpServer.sync(transportProvider)
//                        .serverInfo("java-mcp-server", "1.0.0")
//                        .capabilities(capabilities)
//                        .toolCall(dbTool.getDefinition(), dbTool::execute)
//                        .toolCall(fileTool.getDefinition(), fileTool::execute)
//                        .build();
//
//                log.info("✅ MCP Server started in background");
//                log.info("📡 Server: java-mcp-server v1.0.0");
//
//                // Keep thread alive
//                Thread.currentThread().join();
//
//            } catch (Exception e) {
//                log.error("❌ MCP Server failed", e);
//            }
//        });
//
//        serverThread.setName("MCP-Server-Thread");
//        serverThread.setDaemon(false);
//        serverThread.start();
//    }
//}