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

    public void revokeApprovedRolesInAzureAndDeleteAllForWsTenant(String wsTenantName) {
        List<CustomRoleAssignment> customRoleAssignments = customRoleAssignmentRepository.findAllByWsTenantNameAndStatus(wsTenantName, RequestStatus.APPROVED);
        if (CollectionUtils.isEmpty(customRoleAssignments)) {
            log.info("No APPROVED custom roles found for the WS tenant: {}", wsTenantName);
        } else {
            azureResourceService.revokeAzureResourcesAccess(customRoleAssignments, azureUserCredentialService.findAuthenticationCredentialByWSTenantName(wsTenantName));
            customRoleAssignmentRepository.deleteAllByWsTenantName(wsTenantName);
        }
    }

}
