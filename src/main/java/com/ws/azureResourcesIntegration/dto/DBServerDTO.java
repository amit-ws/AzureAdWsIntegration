package com.ws.azureResourcesIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class DBServerDTO {
    String serverId;
    String serverName;
    String serverType;
    String serverVersion;
    String region;
    String resourceGroup;
    LocalDateTime createdDate;
    String status;
    List<DatabaseDTO> databases = new ArrayList<>();
}
