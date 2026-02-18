package com.ws.azureKuberntesJIT.dto;


import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8sAuditLogAdvanced {
    String timeGenerated;
    String namespace;
    String podName;
    String verb;
    String user;
    String userUID;
    List<String> userGroups;  // user.groups is usually an array
    String resource;
    String subResource;
    String resourceName;
    String requestURI;
    List<String> sourceIPs;         // if you want it as List<String>, you can parse JSON string later
    String userAgent;
    Integer responseStatusCode;
    String responseStatusReason;
    String stage;
    String annotations;
    Map<String, Object> annotationObj;
    OffsetDateTime requestReceivedTimestamp;  // datetime type
    String auditID;
    Map<String, Object> requestObject;
    Map<String, Object> responseObject;
}

