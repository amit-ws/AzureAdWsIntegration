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
    String storageAccountId;
    String storageAccountName;
    String region;
    OffsetDateTime createdDate;
    String kind;
    String customDomainName;
    Boolean blobPublicAccessAllowed;
    Boolean sharedKeyAccessAllowed;
    Boolean isAccessAllowedFromAllNetworks;
    String publicNetworkAccess;

    String containerType;
    String containerName;
    String publicAccess;
}
