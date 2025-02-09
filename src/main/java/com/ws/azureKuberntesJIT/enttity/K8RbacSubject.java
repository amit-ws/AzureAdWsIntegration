package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


import jakarta.persistence.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_rbac_subject", schema = "azure_test")
public class K8RbacSubject {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    String apiGroup;
    String kind;
    String name;
    String namespace;

    String clusterId;
    @Enumerated(EnumType.STRING)
    CloudProviderType cloudProviderType;

    @ManyToOne(fetch = FetchType.LAZY)
    K8RoleBind kubernetesRoleBind;
}
