package com.ws.azureResourcesIntegration.constant;

import java.util.Map;
import java.util.Set;

public class StateChangeConstants {
    public static final Map<CustomRoleAssignmentStatus, Set<CustomRoleAssignmentStatus>> CUSTOM_ROLE_ASSIGNMENT_VALID_STATE_TRANSITIONS = Map.of(
            CustomRoleAssignmentStatus.REQUESTED, Set.of(CustomRoleAssignmentStatus.DENIED, CustomRoleAssignmentStatus.APPROVED),
            CustomRoleAssignmentStatus.DENIED, Set.of(CustomRoleAssignmentStatus.APPROVED),
            CustomRoleAssignmentStatus.APPROVED, Set.of(CustomRoleAssignmentStatus.EXPIRED)
    );
}
