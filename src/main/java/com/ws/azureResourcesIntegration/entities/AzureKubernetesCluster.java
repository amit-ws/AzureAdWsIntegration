package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "azure_kubernetes_cluster", schema = "azure_test")
public class AzureKubernetesCluster {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    @Column(nullable = false)
    String azureId;
    String name;
    String regionName;
    String publicNetworkAccess;
    String nodeResourceGroup;
    String isLocalAccountsEnabled;
    String managedClusterIdentityType;
    String kubernetesVersion;
    boolean isAzureRbacEnabled;
    String type;
    @Column(nullable = false)
    String subscriptionId;
    @Column(nullable = false)
    String resourceGroupName;
    @Column(nullable = false)
    String wsTenantName;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY, mappedBy = "azureKubernetesCluster")
    List<AzureK8ClusterCredential> azureK8ClusterCredentials;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_resource_group_id", referencedColumnName = "id")
    AzureResourceGroup azureResourceGroup;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_subscription_id", referencedColumnName = "id")
    AzureSubscription azureSubscription;
}