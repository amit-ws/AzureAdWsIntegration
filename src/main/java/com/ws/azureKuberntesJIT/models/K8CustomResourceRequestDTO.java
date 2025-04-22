package com.ws.azureKuberntesJIT.models;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import com.ws.azureKuberntesJIT.constant.K8RoleBindingType;
import com.ws.azureKuberntesJIT.constant.K8RoleKind;
import com.ws.azureKuberntesJIT.constant.K8SubjectKind;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@NoArgsConstructor
@AllArgsConstructor
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8CustomResourceRequestDTO {
    UUID id;

    String roleId;
    String roleName;
    K8RoleKind roleKind;
    List<String> verbs;
    String policyResourceName;
    boolean isRoleCustomCreated;

    String roleBindingName;
    String k8ResourceName;
    String resourceType;
    K8RoleBindingType bindingType;
    K8SubjectKind subjectKind;
    String userName;
    String namespace;
    K8ResourceLevel level;

    RequestStatus status;
    Date requestedAt;
    Long expiryTimeAmount;
    LocalDateTime validFrom;
    LocalDateTime validTo;
    String wsUserEmail;
    String message;
    String clusterId;
    String cloudResourceAccountId;
    CloudProviderType cloudType;
    String wsTenantName;
    String clusterName;
    String userDisplayName;

    public K8CustomResourceRequestDTO(UUID id, String clusterId,
                                      String roleId, String roleName, boolean isRoleCustomCreated,
                                      String namespace, String roleBindingName) {
        this.id = id;
        this.namespace = namespace;
        this.clusterId = clusterId;
        this.roleId = roleId;
        this.roleName = roleName;
        this.isRoleCustomCreated = isRoleCustomCreated;
        this.roleBindingName = roleBindingName;
    }
}
