package com.ws.azureKuberntesJIT.enttity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    String namespace; /* NOTNULL for namespace typed role binding */

    @Column(nullable = false)
    String clusterId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    CloudProviderType cloudProviderType;

    @Column(nullable = false)
    String wsTenantName;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kubernetes_role_bind_id", referencedColumnName = "id")
    K8RoleBind kubernetesRoleBind;
}
