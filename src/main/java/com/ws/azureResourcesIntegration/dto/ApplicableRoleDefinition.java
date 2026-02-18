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
    String azureRolePathId;
    String roleName;
    String roleType;
    List<?> actionList;
    List<?> notActionList;
//    boolean flag;
}
