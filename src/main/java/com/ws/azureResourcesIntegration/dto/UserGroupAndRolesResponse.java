package com.ws.azureResourcesIntegration.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserGroupAndRolesResponse {
    Integer id;
    String azureUserId;
    String userPrincipalName;
    String displayName;
    OffsetDateTime createdDateTime;
    Date syncedAt;
    List<String> groups;
    List<String> roleDefinitions;
}
