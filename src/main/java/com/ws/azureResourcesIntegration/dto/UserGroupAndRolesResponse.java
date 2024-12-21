package com.ws.azureResourcesIntegration.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import lombok.experimental.FieldDefaults;

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
    String displayName;
    Date syncedAt;
    List<String> groups;
    List<String> roleDefinitions;
}
