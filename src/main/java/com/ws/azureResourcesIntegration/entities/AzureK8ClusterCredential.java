package com.ws.azureResourcesIntegration.entities;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureResourcesIntegration.constant.KubernetesClusterCredentialType;
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
@Table(name = "azure_kubernetes_cluster_credential", schema = "azure_test")
public class AzureK8ClusterCredential {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;

    String name;

    @Column(nullable = false)
    String clusterServerUrl;

    @Column(nullable = false)
    String token;

    @Enumerated(EnumType.STRING)
    KubernetesClusterCredentialType type;

    @Column(nullable = false)
    String resourceGroupName;

    @Column(nullable = false)
    String subscriptionId;

    Date syncedAt;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_kubernetes_cluster_id", referencedColumnName = "id")
    AzureKubernetesCluster azureKubernetesCluster;
}
