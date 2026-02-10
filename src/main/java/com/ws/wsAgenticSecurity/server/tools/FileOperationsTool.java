package com.ws.wsAgenticSecurity.server.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class FileOperationsTool {

    public McpSchema.Tool getDefinition() {
        return McpSchema.Tool.builder()
                .name("file_operations")
                .description("Perform file system operations")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "operation": {
                              "type": "string",
                              "enum": ["read", "write", "delete", "list"]
                            },
                            "path": {
                              "type": "string"
                            },
                            "content": {
                              "type": "string"
                            }
                          },
                          "required": ["operation", "path"]
                        }
                        """)
                .build();
    }

    public McpSchema.CallToolResult execute(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {

        log.info("🔧 Executing file_operations");

        try {
            String operation = (String) request.arguments().get("operation");
            String path = (String) request.arguments().get("path");
            String content = (String) request.arguments().get("content");

            String result = performOperation(operation, path, content);

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result)),
                    false
            );

        } catch (Exception e) {
            return new McpSchema.CallToolResult("Error: " + e.getMessage(), true);
        }
    }

    private String performOperation(String op, String path, String content) {
        return switch (op.toLowerCase()) {
            case "read" -> "File: " + path + "\nContent: Sample file content...";
            case "write" -> "✅ Wrote " + (content != null ? content.length() : 0) + " bytes to " + path;
            case "delete" -> "✅ Deleted: " + path;
            case "list" -> "Files in " + path + ":\n- file1.txt\n- file2.txt";
            default -> "Unknown operation";
        };
    }
}