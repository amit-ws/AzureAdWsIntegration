package com.ws.azureResourcesIntegration.dto;

import java.util.*;

import lombok.*;
import lombok.experimental.FieldDefaults;

@NoArgsConstructor
@AllArgsConstructor
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class ApplicableRoleDefinition {
    Integer id;
    String azureRolePathId;
    String roleName;
    String roleType;
    List<String> actionList;
}
