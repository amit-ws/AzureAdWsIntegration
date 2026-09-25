package com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpServerConfig {

    private String type = "http";

    private String url;

    private Map<String, String> headers;

    private Map<String, Object> config;

    private Integer timeout = 30;

    public void validate() {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("Server type is required");
        }

        if (!"http".equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("Only 'http' type is supported. Got: " + type);
        }

        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("Server URL is required for HTTP type");
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }
    }

    public boolean isHttp() {
        return "http".equalsIgnoreCase(type);
    }
}
