package com.ws.wsAgenticSecurityGateway.wsClient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServerConfigRequest {

    @NotBlank(message = "Server name is required")
    @Size(max = 128, message = "Server name must be at most 128 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "Server name must contain only alphanumeric, underscore, or hyphen characters")
    private String serverName;

    @Size(max = 20)
    @Builder.Default
    private String type = "http";

    @NotBlank(message = "URL is required")
    @Size(max = 1024, message = "URL must be at most 1024 characters")
    @Pattern(regexp = "^https?://.*", message = "URL must start with http:// or https://")
    private String url;

    private Map<String, String> headers;

    private Map<String, Object> serverConfig;

    @Builder.Default
    private Integer timeoutSeconds = 30;

    @Builder.Default
    private Boolean enabled = true;

    @Builder.Default
    private Boolean autoConnect = true;
}
