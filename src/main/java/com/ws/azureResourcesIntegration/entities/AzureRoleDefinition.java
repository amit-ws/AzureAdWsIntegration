package com.ws.azureResourcesIntegration.entities;

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
@Table(name = "azure_role_definition", schema = "azure_test")
public class AzureRoleDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String roleId;
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
