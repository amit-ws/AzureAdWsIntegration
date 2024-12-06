package com.ws.azureAdIntegration.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.microsoft.graph.models.User;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Entity
@Table(name = "azure_user", schema = "azure_test")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String azureId;
    String displayName;
    String givenName;
    String surname;
    Boolean accountEnabled;
    String mail;
    String userPrincipalName;
    String mobilePhone;
    String jobTitle;
    String department;
    String officeLocation;
    String preferredLanguage;

    Date syncedAt;
    String wsTenantName; // Whiteswan account organization name

    @Column(name = "is_sso_enabled")
    Boolean isSSOEnabled;


    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;
//    Integer azureTenantId;


    @JsonIgnore
    @OneToMany(mappedBy = "azureUser", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureUserGroupMembership> azureUserGroupMemberships = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "azureUser", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureUserDeviceRelationship> azureUserDeviceRelationships = new ArrayList<>();
}

