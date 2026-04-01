package com.ws.wsAgenticSecurityGateway.wsServer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ws.gateway.auth.token-classification")
@Data
public class TokenClassificationProperties {

    private String mode = "jwt-signals";

    private int cacheTtl = 3600;

    private String introspectionUri = "";

    private String introspectionClientId = "";

    private String introspectionClientSecret = "";

    private int introspectionTimeoutMs = 3000;

    public boolean isIntrospectMode() {
        return "introspect".equalsIgnoreCase(mode);
    }

    public boolean isJwtSignalsMode() {
        return "jwt-signals".equalsIgnoreCase(mode);
    }

    public boolean isConservativeMode() {
        return "conservative".equalsIgnoreCase(mode);
    }

    public boolean isIntrospectionConfigured() {
        return introspectionClientId != null && !introspectionClientId.isBlank()
                && introspectionClientSecret != null && !introspectionClientSecret.isBlank();
    }
}
