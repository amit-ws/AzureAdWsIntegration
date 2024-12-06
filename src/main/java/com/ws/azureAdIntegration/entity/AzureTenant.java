package com.ws.azureAdIntegration.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.microsoft.graph.models.Organization;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Entity
@Table(name = "azure_tenant", schema = "azure_test")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureTenant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String azureId;
    String displayName;
    String countryLetterCode;
    String postalCode;
    String preferredLanguage;
    String state;
    String street;
    String tenantType;
    OffsetDateTime azureCreatedDateTime;

    Date syncedAt;
    String wsTenantName; // Whiteswan account organization name

    Boolean isSSOEnabled;

    @JsonIgnore
    @OneToMany(mappedBy = "azureTenant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureApplication> azureApplications = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "azureTenant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureGroup> azureGroups = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "azureTenant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureUser> azureUsers = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "azureTenant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureDevice> azureDevices = new ArrayList<>();
}

