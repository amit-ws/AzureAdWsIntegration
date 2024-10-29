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

    Date createdAt;
    Integer wsTenantId; // Whiteswan account organization id

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


    public static AzureTenant createFromGraphOrganization(Organization organization, AzureTenant azureTenant) {
        azureTenant.setAzureId(organization.id);
        azureTenant.setDisplayName(organization.displayName);
        azureTenant.setCountryLetterCode(organization.countryLetterCode);
        azureTenant.setAzureCreatedDateTime(organization.createdDateTime);
        azureTenant.setPostalCode(organization.postalCode);
        azureTenant.setPreferredLanguage(organization.preferredLanguage);
        azureTenant.setState(organization.state);
        azureTenant.setStreet(organization.street);
        azureTenant.setTenantType(organization.tenantType);
        azureTenant.setCreatedAt(new Date());
        return azureTenant;
    }
}

