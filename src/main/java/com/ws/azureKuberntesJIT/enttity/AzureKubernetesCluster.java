package com.ws.azureKuberntesJIT.enttity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureResourcesIntegration.entities.AzureResourceGroup;
import com.ws.azureResourcesIntegration.entities.AzureSubscription;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

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
    @Column(nullable = false)
    String subscriptionId;
    @Column(nullable = false)
    String resourceGroupName;


    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_resource_group_id", referencedColumnName = "id")
    AzureResourceGroup azureResourceGroup;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_subscription_id", referencedColumnName = "id")
    AzureSubscription azureSubscription;
}
