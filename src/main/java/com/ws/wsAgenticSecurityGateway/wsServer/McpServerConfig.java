//package com.ws.wsAgenticSecurity.server;
//
//import com.ws.wsAgenticSecurity.server.session.SessionManager;
//import com.ws.wsAgenticSecurity.server.tools.DatabaseQueryTool;
//import com.ws.wsAgenticSecurity.server.tools.FileOperationsTool;
//import com.ws.wsAgenticSecurity.server.transport.ServerTransportProvider;
//import io.modelcontextprotocol.server.McpServer;
//import io.modelcontextprotocol.server.McpSyncServer;
//import io.modelcontextprotocol.spec.McpSchema;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.boot.ApplicationRunner;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.context.annotation.Profile;
//
///*
//*
//* Option 3: Spring Boot Profile (Clean separation)
//Use Spring profiles to control what runs.
//*
//* */
//
//@Configuration
//@Profile("mcp-server")
//@Slf4j
//public class McpServerConfig {
//
//    @Bean
//    public ApplicationRunner mcpServerRunner() {
//        return args -> {
//            log.info("🚀 MCP Server Profile Active - Starting Server...");
//
//            SessionManager sessionManager = new SessionManager();
//            ServerTransportProvider transportProvider = new ServerTransportProvider(sessionManager);
//
//            DatabaseQueryTool dbTool = new DatabaseQueryTool();
//            FileOperationsTool fileTool = new FileOperationsTool();
//
//            McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
//                    .tools(true)
//                    .build();
//
//            McpSyncServer server = McpServer.sync(transportProvider)
//                    .serverInfo("java-mcp-server", "1.0.0")
//                    .capabilities(capabilities)
//                    .toolCall(dbTool.getDefinition(), dbTool::execute)
//                    .toolCall(fileTool.getDefinition(), fileTool::execute)
//                    .build();
//
//            log.info("✅ MCP Server started");
//
//            // Keep running
//            Thread.currentThread().join();
//        };
//    }
//}