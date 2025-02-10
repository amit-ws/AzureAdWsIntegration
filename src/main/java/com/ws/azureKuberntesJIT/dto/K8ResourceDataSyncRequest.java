package com.ws.azureKuberntesJIT.dto;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.Map;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8ResourceDataSyncRequest {
    @NotNull(message = "Please provide your cloud's resource ID (for azure: subscritpionId, gcp: projectId and for aws: accountId")
    String resourceAccountId; /* For AZURE -> SUBSCRIPTION_ID  for GCP -> PROJECT_ID  for AWS -> ACCOUNT_ID */
    @NotNull(message = "Please provide K8 cluster Id with its configuration credential")
    Map<String, String> clusterIdAndKubeConfigMap; /* KEY =  cluster_Id   VALUE = respective configuration credential*/
    @NotNull(message = "Please provide your cloud type (eg: AZURE/AWS/GCP")
    CloudProviderType cloudProviderType;
    @NotNull(message = "Please provide your WhiteSwan tenant name")
    String wsTenantName;
    @NotNull(message = "Please provide WS domain email")
    String tenantEmail;
}
