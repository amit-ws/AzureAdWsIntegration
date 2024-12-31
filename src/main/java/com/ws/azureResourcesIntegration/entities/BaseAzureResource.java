package com.ws.azureResourcesIntegration.entities;

import jakarta.persistence.MappedSuperclass;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

import java.util.Date;

@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@MappedSuperclass
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BaseAzureResource {
    Boolean isPublished = Boolean.FALSE;
    Date updatedAt;
    Date syncedAt;
    String wsTenantName; // WhiteSwan account organization name
}
