package com.ws.azureResourcesIntegration.constant;

import java.util.Map;
import java.util.Set;

public class StateChangeConstants {
    public static final Map<RequestStatus, Set<RequestStatus>> CUSTOM_ROLE_ASSIGNMENT_VALID_STATE_TRANSITIONS = Map.of(
            RequestStatus.PENDING, Set.of(RequestStatus.DECLINE, RequestStatus.APPROVED),
            RequestStatus.DECLINE, Set.of(RequestStatus.APPROVED),
            RequestStatus.APPROVED, Set.of(RequestStatus.EXPIRED)
    );
}
