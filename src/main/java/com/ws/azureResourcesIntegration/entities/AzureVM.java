package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import jakarta.persistence.*;

import java.util.Date;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
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
    Date syncedAt;
    String wsTenantName; // WhiteSwan account organization name
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;
}
