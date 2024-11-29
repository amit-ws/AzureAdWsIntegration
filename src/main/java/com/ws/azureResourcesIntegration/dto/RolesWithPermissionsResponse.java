package com.ws.azureResourcesIntegration.dto;

import com.azure.resourcemanager.authorization.models.Permission;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.HashSet;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class RolesWithPermissionsResponse {
    String roleId;
    String roleName;
    String type;
    boolean isCustom;
    Set<Permission> permissions = new HashSet<>();
}
