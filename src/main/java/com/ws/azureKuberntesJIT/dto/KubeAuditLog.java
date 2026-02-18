package com.ws.azureKuberntesJIT.dto;


import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.Instant;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class KubeAuditLog {
    Instant timeGenerated;
    String namespace;
    String podName;
    String verb;
    String user;
    String resource;
    String sourceIPs;
    String responseStatus;
    String requestURI;
}
