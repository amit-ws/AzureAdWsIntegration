package com.ws.azureAdIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;

@Data
@Getter
@Setter
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureApplicationDto {
    private Integer id;
    private String objectId;
    private String displayName;
    private String description;
    private String homepage;
    private String publisher;
    private String disabledByMicrosoftStatus;
    private Boolean isDeviceOnlyAuthSupported;
    private String publisherDomain;
    private OffsetDateTime azureCreatedDateTime;
    private Date createdAt;
    private Integer wsTenantId;
    private List<AzureAppRolesDto> appRoles;

    public AzureApplicationDto(Integer id, String objectId, String displayName, String description,
                               String homepage, String publisher, String disabledByMicrosoftStatus,
                               Boolean isDeviceOnlyAuthSupported, String publisherDomain,
                               OffsetDateTime azureCreatedDateTime, Date createdAt, Integer wsTenantId,
                               List<AzureAppRolesDto> appRoles) {
        this.id = id;
        this.objectId = objectId;
        this.displayName = displayName;
        this.description = description;
        this.homepage = homepage;
        this.publisher = publisher;
        this.disabledByMicrosoftStatus = disabledByMicrosoftStatus;
        this.isDeviceOnlyAuthSupported = isDeviceOnlyAuthSupported;
        this.publisherDomain = publisherDomain;
        this.azureCreatedDateTime = azureCreatedDateTime;
        this.createdAt = createdAt;
        this.wsTenantId = wsTenantId;
        this.appRoles = appRoles;
    }
}

