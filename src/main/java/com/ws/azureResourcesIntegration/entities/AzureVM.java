package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;


@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Data
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_vm", schema = "azure_test")
public class AzureVM extends BaseAzureResource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String azureVmId;
    String instanceId;
    String name;
    String computerName;
    String powerState;
    String size;
    String osType;
    String publicIpInstanceId;
    Integer osDiskSize;
    String region;
    String securityType;
    String resourceType;
    String zones;
    String resourceIdentityType;
    String ipAddress;
    OffsetDateTime timeCreated;
//    @ElementCollection(fetch = FetchType.EAGER)
//    @CollectionTable(
//            name = "azure_vm_public_nework_interfaces",
//            joinColumns = @JoinColumn(name = "ws_azure_vm_id")
//    )
//    Set<String> publicNetworkInterfaces;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_resource_group_id", referencedColumnName = "id")
    AzureResourceGroup azureResourceGroup;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "ws_azure_subscription_id", referencedColumnName = "id")
    AzureSubscription azureSubscription;

    @JsonIgnore
    @OneToMany(mappedBy = "azureVM", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureNetworkInterface> azureNetworkInterfaces = new ArrayList<>();


//    @JsonIgnore
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
//    AzureTenant azureTenant;
}
