package com.ws.wsAgenticSecurityGateway.authConfig.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthConfigValidationResponse {

    private String issuerUri;
    private String jwksUri;
    private boolean jwksReachable;
    private long jwksLatencyMs;
    private int jwksKeyCount;
    private boolean issuerMatchesDiscovery;
    private String errorMessage;
}
