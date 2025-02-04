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
    Integer id;
    String azureId;
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
    boolean isPublished;


    public RoleDefinitionDTO(Integer id, String azureId, String roleName, String roleType, boolean isPublished) {
        this.id = id;
        this.azureId = azureId;
        this.roleName = roleName;
        this.roleType = roleType;
        this.isPublished = isPublished;
    }
}
