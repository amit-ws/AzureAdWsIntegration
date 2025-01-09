package com.ws.projection;

public interface ApplicableRoleDefinitionProjection {
    Integer getId();
    String getAzureRolePathId();
    String getRoleName();
    String getRoleType();
    String getActionList();
}
