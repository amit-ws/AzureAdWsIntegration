package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "custom_azure_role_assignment", schema = "azure_test")
public class CustomRoleAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String azureId; /* Azure ID of RoleAssignment*/
    String azureRoleAssignmentPathId;
    String description;
    String assignee;
    String principalType;
    String scope;
    String scopeType;
    String condition;
    String azureRoleDefinitionPathId;
    String wsTenantName;

    @Enumerated(EnumType.STRING)
    RequestStatus status;

    OffsetDateTime createdOn;
    OffsetDateTime updatedOn;
    LocalDateTime validFrom;
    LocalDateTime validTo;
    Long expiryTimeAmount;

    @Transient
    String updatedBy;
    @Transient
    String createdBy;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_subscription_id", referencedColumnName = "id")
    AzureSubscription azureSubscription; /* subscription-id associated for this Resource (scope) on which role has been assigned */

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;
}
