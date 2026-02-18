package com.ws.azureResourcesIntegration.dto;

import com.ws.azureResourcesIntegration.constant.RequestStatus;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;


@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CustomRoleAssignmentDTO {
    Integer id;
    String azureId;
    String azureRoleAssignmentPathId;
    String description;
    String assignee;
    String principalType;
    String scope;
    String scopeType;
    String condition;
    String azureRoleDefinitionPathId;
    String wsTenantName;
    RequestStatus status;
    Date requestedAt;
    Date updatedAt;
    LocalDateTime validFrom;
    LocalDateTime validTo;
    Long expiryTimeAmount;
    String userEmail;
    String assigneeName;
    String roleName;

    String updatedBy;
    String createdBy;
    OffsetDateTime createdOn;
    OffsetDateTime updatedOn;
    String resourceName; /* name of the Scope (basically the resource itself) */

    List<String> roleNames;
    List<String> assignmentIds;


    public CustomRoleAssignmentDTO(Integer id, String azureId, String azureRoleAssignmentPathId,
                                   String description, String assignee, String principalType,
                                   String scope, String scopeType, String condition,
                                   String azureRoleDefinitionPathId, String wsTenantName,
                                   RequestStatus status, Date requestedAt, Date updatedAt,
                                   LocalDateTime validFrom, LocalDateTime validTo,
                                   Long expiryTimeAmount, String userEmail,
                                   String assigneeName, String roleName) {
        this.id = id;
        this.azureId = azureId;
        this.azureRoleAssignmentPathId = azureRoleAssignmentPathId;
        this.description = description;
        this.assignee = assignee;
        this.principalType = principalType;
        this.scope = scope;
        this.scopeType = scopeType;
        this.condition = condition;
        this.azureRoleDefinitionPathId = azureRoleDefinitionPathId;
        this.wsTenantName = wsTenantName;
        this.status = status;
        this.requestedAt = requestedAt;
        this.updatedAt = updatedAt;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.expiryTimeAmount = expiryTimeAmount;
        this.userEmail = userEmail;
        this.assigneeName = assigneeName;
        this.roleName = roleName;
    }

    public CustomRoleAssignmentDTO(Integer id, String azureId, String azureRoleAssignmentPathId,
                                   String description, String assignee, String principalType,
                                   String scope, String scopeType, String condition,
                                   String azureRoleDefinitionPathId, String wsTenantName,
                                   String assigneeName, String roleName) {
        this.id = id;
        this.azureId = azureId;
        this.azureRoleAssignmentPathId = azureRoleAssignmentPathId;
        this.description = description;
        this.assignee = assignee;
        this.principalType = principalType;
        this.scope = scope;
        this.scopeType = scopeType;
        this.condition = condition;
        this.azureRoleDefinitionPathId = azureRoleDefinitionPathId;
        this.wsTenantName = wsTenantName;
        this.assigneeName = assigneeName;
        this.roleName = roleName;
    }
}




