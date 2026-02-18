package com.ws.azureKuberntesJIT.dto;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class K8sLogEntryDTO {
    String clusterId;
    String namespace;
    String podName;
    String containerName;
    String serviceAccount;
    String nodeName;
    Map<String, String> nodeLabels;
    Map<String, String> podLabels;
    String logLevel;
    String timestamp;
    String message;

    String rawLog;
    String clusterName;
    String cloudResourceAccountId;
    CloudProviderType cloudProviderType;
    String wsTenantName;
}
