package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;


@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@EqualsAndHashCode(exclude = {"azureRoleDefinition", "azureRoleDefinitionActions"})
@Table(name = "azure_role_definition_permission", schema = "azure_test")
public class AzureRoleDefinitionPermission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String permissionNameHash;
    Date updatedAt;
    Date syncedAt;
    String wsTenantName; // WhiteSwan account organization name

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_role_definition_id", referencedColumnName = "id")
    AzureRoleDefinition azureRoleDefinition;

    @OneToMany(mappedBy = "azureRoleDefinitionPermission", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    List<AzureRoleDefinitionAction> azureRoleDefinitionActions = new ArrayList<>();

}
