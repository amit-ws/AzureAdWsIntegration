package com.ws.azureAdIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.Date;
import java.util.UUID;

@Data
@Getter
@Setter
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureAppRolesDto {
    Integer id;

    UUID azureId;
    String displayName;
    String description;
    Boolean isEnabled;
    String origin;
    String value;

    Date createdAt;


    public AzureAppRolesDto(Integer id, UUID azureId, String displayName, String description,
                            Boolean isEnabled, String origin, String value, Date createdAt) {
        this.id = id;
        this.azureId = azureId;
        this.displayName = displayName;
        this.description = description;
        this.isEnabled = isEnabled;
        this.origin = origin;
        this.value = value;
        this.createdAt = createdAt;
    }
}
