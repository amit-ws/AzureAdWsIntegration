package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.Date;
import java.util.UUID;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_subscription", schema = "azure_test")
public class AzureSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    UUID azureSubscriptionId;
    String subscriptionName;
    String subscriptionState;
    String spendingLimit;
    Date syncedAt;
    String wsTenantName; // WhiteSwan account organization name
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;
}
