package com.ws.azureResourcesIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class RoleDefinitionDTO {
    String roleId;
    String name;
    String roleName;
    String description;
    Boolean isCustomRole;
    List<PermissionDTO> permissions;
    Set<String> assignableScopes;
    String type;
    String roleType;
    String createdBy;
}
