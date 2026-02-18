package com.ws.projection;

public interface ApplicableRoleDefinitionProjection {
    String getAzureRolePathId();
    String getRoleName();
    String getRoleType();
    String getActionList();
    String getNotActionList();
}
