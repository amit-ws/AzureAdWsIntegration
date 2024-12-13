package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.Date;
import java.util.UUID;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_storage", schema = "azure_test")
public class AzureStorage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    UUID azureStorageAccountId;
    String storageAccountName;
    String region;
    OffsetDateTime createdDate;
    String kind;
    String customDomainName;
    Boolean blobPublicAccessAllowed;
    Boolean sharedKeyAccessAllowed;
    Boolean isAccessAllowedFromAllNetworks;
    String publicNetworkAccess;
    String containerType;
    String containerName;
    String publicAccess;
    Date syncedAt;
    String wsTenantName; // WhiteSwan account organization name
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;
}
