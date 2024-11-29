package com.ws.azureResourcesIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;

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
}
