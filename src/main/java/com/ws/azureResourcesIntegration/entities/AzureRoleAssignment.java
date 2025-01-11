package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.Date;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_role_assignment", schema = "azure_test")
public class AzureRoleAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String azureRoleAssignmentPathId;
    String azureId;
    String description;
    String assignee;
    String principalType; /* assignee type */
    String scope; /* path-id of the resource on which role has been assigned to eg: Subscription, ResourceGroup, VM etc.. */
    String scopeType; /* Eg: SUBSCRIPTION, RESOURCE-GROUP, VM, SERVER etc. To be decided from application */
    String condition;
    String azureRoleDefinitionPathId;
    Boolean isRoleInherited; /* Role inherited from Parent. If false: The role was specifically assigned on this Scope (resource)*/
    OffsetDateTime createdOn;
    String createdBy;
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
