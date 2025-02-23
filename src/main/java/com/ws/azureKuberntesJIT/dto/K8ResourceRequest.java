package com.ws.azureKuberntesJIT.dto;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.constant.K8ResourceType;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@NoArgsConstructor
@AllArgsConstructor
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8ResourceRequest {
    @NotNull(message = "WhiteSwan Tnant name is required")
    String wsTenantName;
    @NotNull(message = "Cluster ID is required")
    String clusterId;
    @NotNull(message = "Cloud type is required")
    CloudProviderType cloudProviderType;
    @NotNull(message = "Kubernetes resource type is required")
    K8ResourceType type;
    String namespace;
}