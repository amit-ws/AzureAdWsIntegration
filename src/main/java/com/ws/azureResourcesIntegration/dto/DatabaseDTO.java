package com.ws.azureResourcesIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class DatabaseDTO {
    String databaseId;
    String databaseName;
    String serverId;
    String databaseType;
    String version;
    String status;
    Integer sizeInGb;
    LocalDateTime lastBackupTime;
    LocalDateTime createdDate;

    String edition;
    Long maxSizeBytes;
    String region;
    String dbStatus;
    String readScale;
    Double minCapacity;
    OffsetDateTime pausedDate;
    OffsetDateTime resumedDate;
}
