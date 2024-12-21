package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import jakarta.persistence.*;

import java.util.Date;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_vm", schema = "azure_test")
public class AzureVM {
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
    String type;
    String zones;
    String resourceIdentityType;
    String ipAddress;
    Boolean isPublished;
    Date syncedAt;
    String wsTenantName; // WhiteSwan account organization name

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_subscription_id", referencedColumnName = "id")
    AzureSubscription azureSubscription;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;
}
