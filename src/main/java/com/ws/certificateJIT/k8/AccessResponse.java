package com.ws.certificateJIT.k8;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessResponse {
    private String certificateSigningRequestName;
    private String status;
    private String message;
    private String certificatePem;
    private String privateKeyPem;
    private String kubeconfig;
    private Long expirationTime;
    private String expirationTimeStamp;
}