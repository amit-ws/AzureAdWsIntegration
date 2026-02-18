package com.ws.certificateJIT.k8;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

@Configuration
@ConfigurationProperties(prefix = "certificate")
@Data
public class CertificateConfig {

    private Integer defaultExpirationSeconds = 600;
    private Integer maxExpirationSeconds = 86400;
    private String signerName = "kubernetes.io/kube-apiserver-client";
}
