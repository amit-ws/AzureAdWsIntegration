//package com.ws.azureKuberntesJIT.enttity;
//
//
//import com.ws.azureAdIntegration.constants.CloudProviderType;
//import com.ws.azureKuberntesJIT.constant.K8ResourceType;
//import jakarta.persistence.*;
//import lombok.AllArgsConstructor;
//import lombok.Builder;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//
//@NoArgsConstructor
//@AllArgsConstructor
//@Data
//@Builder
//@Entity
//@Table(name = "kubernetes_resource_annotation", schema = "azure_test")
//public class K8ResourceAnnotation {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    Long id;
//
//    @Column(nullable = false)
//    String key;
//    @Column(nullable = false)
//    String value;
//
//    @Column(nullable = false)
//    String kubernetesResourceId; /* The parent K8 resource this annotation record belongs to, Eg: namespace */
//
//    @Enumerated(EnumType.STRING)
//    K8ResourceType k8ResourceType;
//
//    @Column(nullable = false)
//    String clusterId;
//
//    @Enumerated(EnumType.STRING)
//    CloudProviderType cloudProviderType;
//
//    @Column(nullable = false)
//    String resourceAccountId;
//
//    @Column(nullable = false)
//    String wsTenantName;
//}
