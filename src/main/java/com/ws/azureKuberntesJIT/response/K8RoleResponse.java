package com.ws.azureKuberntesJIT.response;


import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8RoleResponse {
    Long id;
    String UID;
    String name;
    String namespace;
    K8ResourceLevel type;
    String clusterId;
    String resourceAccountId;
    CloudProviderType cloudType;

    List<String> verbs;
    List<String> apiGroups;
    List<String> resources;
    List<String> resourceNames;


    public K8RoleResponse(Long id, String UID, String name, String namespace, K8ResourceLevel type, String clusterId,
                          CloudProviderType cloudType, String resourceAccountId) {
        this.id = id;
        this.UID = UID;
        this.name = name;
        this.type = type;
        this.namespace = namespace;
        this.clusterId = clusterId;
        this.cloudType = cloudType;
        this.resourceAccountId = resourceAccountId;
    }

}
