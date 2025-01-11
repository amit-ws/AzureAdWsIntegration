package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import jakarta.persistence.*;

import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;


@EqualsAndHashCode(callSuper = true)
@Entity
@Data
@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_vm", schema = "azure_test")
public class AzureVM extends BaseAzureResource{
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
    String resourceGroupName;
    Integer osDiskSize;
    String region;
    String securityType;
    String resourceType;
    String zones;
    String resourceIdentityType;
    String ipAddress;
    Boolean isPublished;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_resource_group_id", referencedColumnName = "id")
    AzureResourceGroup azureResourceGroup;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_subscription_id", referencedColumnName = "id")
    AzureSubscription azureSubscription;

//    @JsonIgnore
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
//    AzureTenant azureTenant;
}
