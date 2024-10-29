package com.ws.azureAdIntegration.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.microsoft.graph.models.AppRole;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.Date;
import java.util.UUID;

@Entity
@Table(name = "azure_app_role", schema = "azure_test")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureAppRoles {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;

    UUID azureId;
    String displayName;
    String description;
    Boolean isEnabled;
    String origin;
    String value;

    Date createdAt;


    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "application_id", referencedColumnName = "id")
    AzureApplication application;

    public static AzureAppRoles createFromGraphAppRoles(AppRole graphAppRole, AzureAppRoles azureAppRole) {
        azureAppRole.setAzureId(graphAppRole.id);
        azureAppRole.setDisplayName(graphAppRole.displayName);
        azureAppRole.setDescription(graphAppRole.description);
        azureAppRole.setIsEnabled(graphAppRole.isEnabled);
        azureAppRole.setOrigin(graphAppRole.origin);
        azureAppRole.setValue(graphAppRole.value);
        azureAppRole.setCreatedAt(new Date());
        return azureAppRole;
    }
}
