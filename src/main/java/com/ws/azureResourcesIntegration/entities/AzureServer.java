package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Entity
@Data
@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_server", schema = "azure_test")
public class AzureServer extends BaseAzureResource{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String azureServerId;
    String serverName;
    String type;
    String serverVersion;
    String region;
    String resourceGroup;
    String kind;
    String state;
    Boolean managedServiceIdentityEnabled;
    String managedServiceIdentityType;
    String publicNetworkAccess;
    String resourceGroupName;
    String innerModelState;
    String administratorType;
    String administratorSignInName;
    String administratorId;
    String location;
    String administratorLogin;
    String endpointId;
    String resourceType;

    @OneToMany(mappedBy = "azureServer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    List<AzureDatabase> azureDatabases = new ArrayList<>();

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_resource_group_id", referencedColumnName = "id")
    AzureResourceGroup azureResourceGroup;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_subscription_id", referencedColumnName = "id")
    AzureSubscription azureSubscription;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;
}
