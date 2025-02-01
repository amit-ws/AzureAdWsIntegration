package com.ws.projection;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

public interface CustomRoleAssignmentProjection {
    List<String> getRoles();

    List<String> getAssignmentIds();

    String getStatus();

    String getUserEmail();

    String getAssignee();

    String getDisplayName();

    String getScope();

    Date getRequestedAt();

    String getWsTenantName();

    Long getExpirtyTime();

    LocalDateTime getValidFrom();

    LocalDateTime getValidTo();
}
