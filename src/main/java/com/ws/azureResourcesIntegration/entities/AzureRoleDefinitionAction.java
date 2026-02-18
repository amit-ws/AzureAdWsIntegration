package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
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
@Table(name = "azure_role_definition_action", schema = "azure_test")
public class AzureRoleDefinitionAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String action;
    String type;
    Date createdAt;
    Date updatedAt;

    String subscriptionId;
    String wsTenantName;

    @JsonBackReference
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_role_definition_permission_id", referencedColumnName = "id")
    AzureRoleDefinitionPermission azureRoleDefinitionPermission;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_role_definition_id", referencedColumnName = "id")
    AzureRoleDefinition azureRoleDefinition;

//    @JsonIgnore
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
//    AzureTenant azureTenant;
}
