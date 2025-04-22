//package com.ws.azureKuberntesJIT.enttity;
//
//import com.ws.azureAdIntegration.constants.CloudProviderType;
//import com.ws.azureKuberntesJIT.constant.K8RoleBindingType;
//import com.ws.azureResourcesIntegration.constant.RequestStatus;
//import jakarta.persistence.*;
//import lombok.AllArgsConstructor;
//import lombok.Builder;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//
//import java.util.Date;
//
//@NoArgsConstructor
//@AllArgsConstructor
//@Builder
//@Data
//@Entity
//@Table(name = "Kubernetes_resource_request", schema = "azure_test")
//public class K8ResourceRequest {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    Long id;  // Make it UUID
//
//    // Role details
//    String roleId;
//    String roleName;
//    String roleType;
//
//    // Role Binding details
//    String resourceId;
//    String resourceType;
//    String roleBindingId; // <--- UUID from metadata object of Role binding object sent by K8
//    String roleBindingName;
//    K8RoleBindingType bindingType;
//    String userName;
//    String namespace;
//
//    // For both
//    RequestStatus status;
//
//    // Generic details
//    String clusterId;
//    String cloudId;
//    CloudProviderType cloudType;
//    String wsTenantName;
//    @Builder.Default
//    Date createdAt = new Date();
//}
