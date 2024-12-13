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
@Table(name = "azure_role_definition", schema = "azure_test")
public class AzureRoleDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    UUID roleId;
    String name;
    String roleName;
    String description;
    Boolean isCustomRole;
    @Column(columnDefinition = "jsonb")
    String permissions;
    @Column(columnDefinition = "jsonb")
    String assignableScopes;
    String type;
    String roleType;
    String createdBy;
    Date syncedAt;
    String wsTenantName; // WhiteSwan account organization name
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;
}
