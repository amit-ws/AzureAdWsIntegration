package com.ws.azureKuberntesJIT.models;


import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.constant.K8ResourceType;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8ResourceRaiseRequest {
    // For Role specific
    String roleId;  /* UUID of Role */
    String roleName;
    String roleKind;
    List<String> verbs;
    String resourcesName;
    K8ResourceType k8ResourceType;

    // For Role binding specific
    String k8ResourceId;
    String k8ResourceName;
    String namespace; /* To be taken from the published resource */
    String userId;
    @NotNull(message = "Please provide user name")
    String userName;

    Long expiryTimeAmount;
    @NotNull(message = "Please provid WS configured user email")
    String wsUserEmail; /* WS Tenant user email */

    // Generic
    String message;
    @NotNull(message = "Please provide cluster Id")
    String clusterId;
    @NotNull(message = "Please provide cloud account ID")
    String cloudResourceAccountId;
    @NotNull(message = "Please provide cloud type (eg: AZURE, GCP, AWS)")
    CloudProviderType cloudType;
    @NotNull(message = "Please provide WS tenant name")
    String wsTenantName;

    String user;
    String clusterName;
}
