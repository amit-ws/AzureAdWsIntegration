package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_resource_group", schema = "azure_test")
public class AzureResourceGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String azureResourceId;
    String name;
    String regionName;
    String location;
    Date syncedAt;
    String wsTenantName; // WhiteSwan account organization name

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "azure_resource_group_tags",
            joinColumns = @JoinColumn(name = "ws_resource_group_id")
    )
    @MapKeyColumn(name = "tag_key")
    @Column(name = "tag_value")
    Map<String, String> tags;

    @JsonIgnore
    @OneToMany(mappedBy = "azureResourceGroup", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureServer> azureServers = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "azureResourceGroup", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureStorageAccount> azureStorageAccounts = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "azureResourceGroup", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureVM> azureVMS = new ArrayList<>();

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_subscription_id", referencedColumnName = "id")
    AzureSubscription azureSubscription;

    //    @JsonIgnore
//    @OneToMany(mappedBy = "azureResourceGroup", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
//    List<AzureDatabase> azureDatabases = new ArrayList<>();

//    @JsonIgnore
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
//    AzureTenant azureTenant;

}
