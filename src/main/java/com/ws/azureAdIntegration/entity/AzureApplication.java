package com.ws.azureAdIntegration.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.microsoft.graph.models.Application;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Entity
@Table(name = "azure_application", schema = "azure_test")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;

    String objectId;
    String displayName;
    String description;
    String homepage;
    String publisher;
    String disabledByMicrosoftStatus;
    Boolean isDeviceOnlyAuthSupported;
    String publisherDomain;
    OffsetDateTime azureCreatedDateTime;

    Date syncedAt;
    String wsTenantName; // Whiteswan account organization name
    //    Integer azureTenantId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;

    @JsonIgnore
    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<AzureAppRoles> appRoles = new ArrayList<>();

    @ElementCollection
    List<String> tags;


//    @ElementCollection
//    private List<String> identifierUris;

//    @ElementCollection
//    private List<String> replyUrls;

//    @ElementCollection
//    private List<String> logoutUrl;

//    @ElementCollection
//    private List<String> oauth2Permissions;
}
