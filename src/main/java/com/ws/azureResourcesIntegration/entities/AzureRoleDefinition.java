package com.ws.azureResourcesIntegration.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.test.MyEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.*;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
@MyEntity
@FieldDefaults(level = AccessLevel.PRIVATE)
@EqualsAndHashCode(exclude = {"azureRoleDefinitionPermissions"})
@Table(name = "azure_role_definition", schema = "public")
public class AzureRoleDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String rolePathId;
    String azureId;
    String roleName;
    String description;
    String roleType;
    String createdBy;
    OffsetDateTime createdOn;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "azure_role_definition_assignable_scopes",
            joinColumns = @JoinColumn(name = "ws_azure_role_definition_id")
    )
    Set<String> assignableScope;

    Date syncedAt;
    String wsTenantName; // WhiteSwan account organization name
    String subscriptionId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;

    @OneToMany(mappedBy = "azureRoleDefinition", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    Set<AzureRoleDefinitionPermission> azureRoleDefinitionPermissions = new LinkedHashSet<>();
}
