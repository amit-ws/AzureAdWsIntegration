package com.ws.azureResourcesIntegration.entities;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
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
    @Column(columnDefinition = "boolean default false")
    @Builder.Default
    Boolean isPublished = Boolean.FALSE;
    Date updatedAt;
    Date syncedAt;
    String wsTenantName; // WhiteSwan account organization name

    @Transient
    Integer azureSubscriptionId;
}
