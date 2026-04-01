package com.ws.wsAgenticSecurityGateway.wsClient.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpConfigFile {

    private Map<String, McpServerConfig> mcpServers;

    private Map<String, String> inputs;

    public void validate() {
        if (mcpServers == null || mcpServers.isEmpty()) {
            throw new IllegalArgumentException("No MCP servers defined in configuration");
        }

        mcpServers.forEach((name, config) -> {
            try {
                config.validate();
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid config for server '" + name + "': " + e.getMessage(), e);
            }
        });
    }
}
