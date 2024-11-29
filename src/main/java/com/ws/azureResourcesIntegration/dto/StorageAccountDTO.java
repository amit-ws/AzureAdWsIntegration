package com.ws.azureResourcesIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class StorageAccountDTO {
    Long id;
    String storageAccountId;
    String storageAccountName;
    String storageAccountRegion;
    OffsetDateTime createdDate;
    String containerType;
    String containerName;
    String publicAccess;
}
