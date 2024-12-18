package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.Date;
import java.util.Map;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_storage_account", schema = "azure_test_backup")
public class AzureStorageAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String azureStorageAccountId;
    String storageAccountName;
    String region;
    OffsetDateTime createdDate;
    String kind;
    String customDomainName;
    Boolean blobPublicAccessAllowed;
    Boolean sharedKeyAccessAllowed;
    Boolean isAccessAllowedFromAllNetworks;
    String publicNetworkAccess;
    String publicAccess;
    String skuTier;
    String accessTier;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "azure_storage_account_tags",
            joinColumns = @JoinColumn(name = "ws_storage_account_id")
    )
    @MapKeyColumn(name = "tag_key")
    @Column(name = "tag_value")
    private Map<String, String> tags;
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
