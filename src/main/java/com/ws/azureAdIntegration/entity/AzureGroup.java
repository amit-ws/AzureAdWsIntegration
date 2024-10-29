package com.ws.azureAdIntegration.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.microsoft.graph.models.Group;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;

@Entity
@Table(name = "azure_group", schema = "azure_test")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String azureId;
    String displayName;
    String description;
    String mail;
    String mailNickname;
    boolean mailEnabled;
    boolean securityEnabled;
    String visibility;
    OffsetDateTime azureCreatedDateTime;
    String securityIdentifier;

    Date createdAt;
    Integer wsTenantId; // Whiteswan account organization id

    @JsonIgnore
    @OneToMany(mappedBy = "azureGroup", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureUserGroupMembership> azureUserGroupMemberships;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;
//    Integer azureTenantId;


    public static AzureGroup createFromGraphGroup(Group graphGroup, AzureGroup azureGroup) {
        azureGroup.setAzureId(graphGroup.id);
        azureGroup.setDisplayName(graphGroup.displayName);
        azureGroup.setDescription(graphGroup.description);
        azureGroup.setMail(graphGroup.mail);
        azureGroup.setMailNickname(graphGroup.mailNickname);
        azureGroup.setMailEnabled(graphGroup.mailEnabled);
        azureGroup.setSecurityEnabled(graphGroup.securityEnabled);
        azureGroup.setVisibility(graphGroup.visibility);
        azureGroup.setAzureCreatedDateTime(graphGroup.createdDateTime);
        azureGroup.setSecurityIdentifier(graphGroup.securityIdentifier);
        azureGroup.setCreatedAt(new Date());
        return azureGroup;
    }
}
