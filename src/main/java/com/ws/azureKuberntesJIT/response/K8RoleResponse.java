package com.ws.azureKuberntesJIT.response;


import com.ws.azureAdIntegration.constants.CloudProviderType;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.ArrayList;
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
    String clusterId;
    CloudProviderType cloudType;
    String parentResourceId;
//    String type; /*cLUSTER LEVEL or NAMESPACE LEVEL*/

    List<String> verbs;
    List<String> apiGroups;
    List<String> resources;
    List<String> resourceNames;


    public K8RoleResponse(Long id, String UID, String name, String namespace, String clusterId,
                          CloudProviderType cloudType, String parentResourceId) {
        this.id = id;
        this.UID = UID;
        this.name = name;
        this.namespace = namespace;
        this.clusterId = clusterId;
        this.cloudType = cloudType;
        this.parentResourceId = parentResourceId;
    }

}
