package com.ws.scheduler;

import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.service.BackendApplicationLogservice;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import com.ws.azureResourcesIntegration.entities.CustomRoleAssignment;
import com.ws.azureResourcesIntegration.repository.AzureRoleAssignmentRepository;
import com.ws.azureResourcesIntegration.repository.CustomRoleAssignmentRepository;
import com.ws.azureResourcesIntegration.service.AzureResourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class AzureResourcesScheduler {
    final CustomRoleAssignmentRepository customRoleAssignmentRepository;
    final AzureRoleAssignmentRepository azureRoleAssignmentRepository;
    final BackendApplicationLogservice backendApplicationLogservice;
    final AzureResourceService azureResourceService;

    @Autowired
    public AzureResourcesScheduler(CustomRoleAssignmentRepository customRoleAssignmentRepository, AzureRoleAssignmentRepository azureRoleAssignmentRepository, BackendApplicationLogservice backendApplicationLogservice, AzureResourceService azureResourceService) {
        this.customRoleAssignmentRepository = customRoleAssignmentRepository;
        this.azureRoleAssignmentRepository = azureRoleAssignmentRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
        this.azureResourceService = azureResourceService;
    }


    @Scheduled(cron = "*/20 * * * * *")
    private void removeAzureResourcesAccess() {
        List<CustomRoleAssignment> customRoleAssignments = customRoleAssignmentRepository.findAllByStatus(RequestStatus.APPROVED);
        long count = customRoleAssignments.stream()
                .filter(customRoleAssignment -> LocalDateTime.now().isAfter(customRoleAssignment.getValidTo()))
                .peek(azureResourceService::revokeAzureResourceAccess)
                .count();
        if (count > 0) {
            backendApplicationLogservice.saveAuditLog("WhiteSwan", "whiteswan", Constant.ADD, String.format(Constant.TOTAL_FOUND_ROLE_ASSIGNMENTS_TO_BE_REMOVED, count), "Info");
        }
    }

}
