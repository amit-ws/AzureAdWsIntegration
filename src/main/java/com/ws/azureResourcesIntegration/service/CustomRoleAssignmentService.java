package com.ws.azureResourcesIntegration.service;

import com.ws.azureAdIntegration.service.AzureUserCredentialService;
import com.ws.azureAdIntegration.service.BackendApplicationLogservice;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import com.ws.azureResourcesIntegration.entities.CustomRoleAssignment;
import com.ws.azureResourcesIntegration.repository.CustomRoleAssignmentRepository;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.util.Collection;
import java.util.List;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CustomRoleAssignmentService {
    final CustomRoleAssignmentRepository customRoleAssignmentRepository;
    final AzureUserCredentialService azureUserCredentialService;
    final AzureResourceService azureResourceService;
    final BackendApplicationLogservice backendApplicationLogservice;

    @Autowired
    public CustomRoleAssignmentService(CustomRoleAssignmentRepository customRoleAssignmentRepository,
                                       AzureUserCredentialService azureUserCredentialService, AzureResourceService azureResourceService,
                                       BackendApplicationLogservice backendApplicationLogservice) {
        this.customRoleAssignmentRepository = customRoleAssignmentRepository;
        this.azureUserCredentialService = azureUserCredentialService;
        this.azureResourceService = azureResourceService;
        this.backendApplicationLogservice = backendApplicationLogservice;
    }

    public void revokeApprovedRolesInAzureAndDeleteAllForWsTenant(String wsTenantName, Collection<String> subscriptionIDs) {
        List<CustomRoleAssignment> customRoleAssignments = customRoleAssignmentRepository.findAllByWsTenantNameAndStatus(wsTenantName, RequestStatus.APPROVED, subscriptionIDs);
        log.info("Totsl {} CustomRoleAssignment found for the WS tenant: {} to be revoked", customRoleAssignments.size(), wsTenantName);
        if (!CollectionUtils.isEmpty(customRoleAssignments)) {
            azureResourceService.revokeAzureResourceAccess(customRoleAssignments, azureUserCredentialService.findAuthenticationCredentialByWSTenantName(wsTenantName));
        }
        customRoleAssignmentRepository.deleteAllByWsTenantName(wsTenantName);
    }

    public boolean checkIfCustomRoleAssignmentExistsForAssignee(String wsTenantName, String subscriptionId, String scope, String assignee) {
        return ObjectUtils.isEmpty(findByScopeAndAssigneeAndWsTenantNameAndSubscriptionId(scope, assignee, wsTenantName, subscriptionId));
    }


    private CustomRoleAssignment findByScopeAndAssigneeAndWsTenantNameAndSubscriptionId(String scope, String assignee, String wsTenantName, String subscriptionId) {
        return customRoleAssignmentRepository.findByScopeAndAssigneeAndWsTenantNameAndSubscriptionId(scope, assignee, wsTenantName, subscriptionId)
                .orElse(null);
    }

}
