package com.ws.azureKuberntesJIT.models;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class RaiseRequest {
    String roleId;
    String roleName;
    String resourceId;
    String resourceType;
    String roleType;
    String clusterId;
    String cloudId;
    String userName;
    String namespace;
    CloudProviderType cloudType;
    String wsTenantName;
}
