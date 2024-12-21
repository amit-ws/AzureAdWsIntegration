package com.ws.azureResourcesIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.*;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureRoleDefinitionDTO {
    Integer id;
    String azureId;
    String rolePathId;
    String roleName;
    String roleType;
    String description;
    Boolean isPublished;
    Set<String> assignableScope = new LinkedHashSet<>();
    Date syncedAt;
    String wsTenantName;
    List<String> actions = new ArrayList<>();
    List<String> notActions = new ArrayList<>();
}
