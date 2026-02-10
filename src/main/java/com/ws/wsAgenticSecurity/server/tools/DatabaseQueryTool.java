package com.ws.wsAgenticSecurity.server.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class DatabaseQueryTool {

    public McpSchema.Tool getDefinition() {
        return McpSchema.Tool.builder()
                .name("database_query")
                .description("Execute SQL queries against configured databases")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "query": {
                              "type": "string",
                              "description": "SQL query to execute"
                            },
                            "database": {
                              "type": "string",
                              "description": "Database name",
                              "enum": ["users_db", "products_db", "analytics_db"]
                            },
                            "limit": {
                              "type": "integer",
                              "description": "Max rows to return",
                              "default": 100
                            }
                          },
                          "required": ["query", "database"]
                        }
                        """)
                .build();
    }

    public McpSchema.CallToolResult execute(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {

        log.info("🔧 Executing database_query");
        log.info("Arguments: {}", request.arguments());

        try {
            String query = (String) request.arguments().get("query");
            String database = (String) request.arguments().get("database");
            Integer limit = request.arguments().containsKey("limit") ?
                    ((Number) request.arguments().get("limit")).intValue() : 100;

            String result = executeDummyQuery(query, database, limit);

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result)),
                    false
            );

        } catch (Exception e) {
            log.error("Error", e);
            return new McpSchema.CallToolResult(
                    "Error: " + e.getMessage(),
                    true
            );
        }
    }

    private String executeDummyQuery(String query, String db, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("Query Results from ").append(db).append(":\n\n");

        if (query.toUpperCase().contains("SELECT")) {
            sb.append("| id | name        | value   |\n");
            sb.append("|----|-----------  |---------||\n");
            for (int i = 1; i <= Math.min(5, limit); i++) {
                sb.append(String.format("| %-2d | Item_%d     | $%-5.2f  |\n", i, i, 10.0 * i));
            }
        } else {
            sb.append("Query executed successfully on ").append(db);
        }

        return sb.toString();
    }
}