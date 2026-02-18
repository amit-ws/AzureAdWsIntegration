package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.*;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_subscription", schema = "azure_test")
public class AzureSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String azureSubscriptionId;
    String subscriptionName;
    String subscriptionState;
    String authorizationSource;
    String spendingLimit;
    Date syncedAt;
    @Column(nullable = false)
    String wsTenantName; // WhiteSwan account organization name

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "azure_subscription_tags",
            joinColumns = @JoinColumn(name = "ws_subscription_id")
    )
    @MapKeyColumn(name = "tag_key")
    @Column(name = "tag_value")
    Map<String, String> tags;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;

    @JsonIgnore
    @OneToMany(mappedBy = "azureSubscription", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureResourceGroup> azureResourceGroups = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "azureSubscription", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureVM> azureVMS = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "azureSubscription", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureServer> azureServers = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "azureSubscription", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureStorageAccount> azureStorageAccounts = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "azureSubscription", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureKubernetesCluster> azureKubernetesClusters = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "azureSubscription", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureRoleDefinition> azureRoleDefinitions = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "azureSubscription", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureRoleAssignment> azureRoleAssignments = new ArrayList<>();

    //    @JsonIgnore
//    @OneToMany(mappedBy = "azureSubscription", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
//    List<CustomRoleAssignment> customRoleAssignments = new ArrayList<>();

}
