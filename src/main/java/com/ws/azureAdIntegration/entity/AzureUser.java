package com.ws.azureAdIntegration.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.microsoft.graph.models.User;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.springframework.util.StringUtils;

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
    String mail;
    String userPrincipalName;
    String mobilePhone;
    String jobTitle;
    String department;
    String officeLocation;
    String preferredLanguage;

    Date createdAt;
    Integer wsTenantId; // Whiteswan account organization id

    Date updatedAt;

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


    public static AzureUser fromUser(User user, AzureUser azureUser) {
        azureUser.setAzureId(user.id);
        azureUser.setDisplayName(user.displayName);
        azureUser.setGivenName(user.givenName);
        azureUser.setSurname(user.surname);
        azureUser.setMail(user.mail);
        azureUser.setUserPrincipalName(user.userPrincipalName);
        azureUser.setMobilePhone(user.mobilePhone);
        azureUser.setJobTitle(user.jobTitle);
        azureUser.setDepartment(user.department);
        azureUser.setOfficeLocation(user.officeLocation);
        azureUser.setUpdatedAt(StringUtils.isEmpty(azureUser.getId().toString()) ? null : new Date());
        azureUser.setCreatedAt(StringUtils.isEmpty(azureUser.getId().toString()) ? new Date() : azureUser.getCreatedAt());
        return azureUser;
    }
}

