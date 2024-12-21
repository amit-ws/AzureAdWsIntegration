package com.ws.projection;

import java.util.Date;


public interface UserGroupAndRolesProjection {
    Integer getId();
    String getAzureUserId();
    String getDisplayName();
    Date getSyncedAt();
    String getGroups();
    String getRoles();
}
