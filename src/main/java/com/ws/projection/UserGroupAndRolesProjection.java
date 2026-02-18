package com.ws.projection;

import java.time.OffsetDateTime;
import java.util.Date;


public interface UserGroupAndRolesProjection {
    Integer getId();

    String getAzureUserId();

    String getUserPrincipalName();

    String getDisplayName();

    OffsetDateTime getCreatedDateTime();

    Date getSyncedAt();

    String getGroups();

    String getRoles();
}
