package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.constant.CustomRoleAssignmentStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.Date;

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
    String azureId;
    String azureRoleAssignmentPathId;
    String description;
    String assignee;
    String principalType;
    String scope;
    String scopeType;
    String condition;
    String azureRoleDefinitionPathId;
    String wsTenantName;
    String subscriptionId; /* subscription-id associated for this Resource (scope) on which role has been assigned*/

    @Enumerated(EnumType.STRING)
    CustomRoleAssignmentStatus status;

    Date createdAt;
    Date updatedAt;
    Date validFrom;
    Date validTo;
    Long expiryTimeAmount;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;
}
