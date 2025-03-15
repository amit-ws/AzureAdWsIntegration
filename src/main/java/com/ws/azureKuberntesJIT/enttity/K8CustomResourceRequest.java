package com.ws.azureKuberntesJIT.enttity;


import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
@Entity
@Table(name = "Kubernetes_custom_resource_request", schema = "azure_test")
public class K8CustomResourceRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    String roleId;
    String roleName;
    String resourceId;
    String resourceType;
    String roleBindingId;
    String roleType;
    RequestStatus status;
    String userName;
    String namespace;

    String clusterId;
    String cloudId;
    CloudProviderType cloudType;

    String wsTenantName;
    @Builder.Default
    Date createdAt = new Date();
}
