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
public class UserGroupRolePermissionResponse {
    String userId;
    String displayName;
    String groupName;
    String role;
    Set<String> permissions;
}
