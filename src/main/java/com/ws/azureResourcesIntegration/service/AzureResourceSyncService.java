package com.ws.azureResourcesIntegration.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.entity.*;
import com.ws.azureAdIntegration.repository.*;
import com.ws.azureAdIntegration.service.BackendApplicationLogservice;
import com.ws.azureAdIntegration.util.AzureAuthUtil;
import com.ws.azureResourcesIntegration.configuration.AzureAuthConfigurationFactory;
import com.ws.azureResourcesIntegration.dto.VmDTO;
import com.ws.azureResourcesIntegration.entities.AzureVM;
import com.ws.azureResourcesIntegration.repository.*;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourceSyncService {
    String wsTenantName;
    String tenantEmail = "dummy@gmail.com";
    AzureResourceManager azureResourceManager;
    final AzureSubscriptionRepository azureSubscriptionRepository;
    final AzureResourceGroupRepository azureResourceGroupRepository;
    final AzureServerRepository azureServerRepository;
    final AzureDatabaseRepository azureDatabaseRepository;
    final AzureRoleDefinitionRepository azureRoleDefinitionRepository;
    final AzureRoleAssignmentRepository azureRoleAssignmentRepository;
    final AzureVMRepository azureVMRepository;
    final AzureStorageRepository azureStorageRepository;
    final AzureAuthConfigurationFactory azureAuthConfigurationFactory;
    final AzureUserCredentialRepository azureUserCredentialRepository;
    final BackendApplicationLogservice backendApplicationLogservice;
    final AzureAuthUtil azureAuthUtil;


    @Autowired
    public AzureResourceSyncService(AzureSubscriptionRepository azureSubscriptionRepository, AzureResourceGroupRepository azureResourceGroupRepository, AzureServerRepository azureServerRepository, AzureDatabaseRepository azureDatabaseRepository, AzureRoleDefinitionRepository azureRoleDefinitionRepository, AzureRoleAssignmentRepository azureRoleAssignmentRepository, AzureVMRepository azureVMRepository, AzureStorageRepository azureStorageRepository, AzureAuthConfigurationFactory azureAuthConfigurationFactory, AzureUserCredentialRepository azureUserCredentialRepository, BackendApplicationLogservice backendApplicationLogservice, AzureAuthUtil azureAuthUtil) {
        this.azureSubscriptionRepository = azureSubscriptionRepository;
        this.azureResourceGroupRepository = azureResourceGroupRepository;
        this.azureServerRepository = azureServerRepository;
        this.azureDatabaseRepository = azureDatabaseRepository;
        this.azureRoleDefinitionRepository = azureRoleDefinitionRepository;
        this.azureRoleAssignmentRepository = azureRoleAssignmentRepository;
        this.azureVMRepository = azureVMRepository;
        this.azureStorageRepository = azureStorageRepository;
        this.azureAuthConfigurationFactory = azureAuthConfigurationFactory;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
        this.azureAuthUtil = azureAuthUtil;
    }

    private void getAzureResourceManager(AzureUserCredential azureUserCredential) {
        this.azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredential);
    }


    public void syncAzureResourceData(AzureTenant azureTenant, String wsTenantName, AzureUserCredential azureUserCredential) {
        try {
            this.wsTenantName = wsTenantName;
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_RESOURCE_DATA_SYNC_START, "Info");
            getAzureResourceManager(azureUserCredential);
            syncAzureVMs(azureTenant);
            /* sync below data's too
             * resource group
             * subscription
             * azure roles
             * azure role assignments
             * servers
             * databases
             * storages
             * */
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_RESOURCE_DATA_SYNC_END, "Info");
        } catch (Exception ex) {
            log.error("Error occurred in syncing data from Azure Resources");
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.wsTenantName, Constant.AZURE_SYNC_FAILURE, ex.getMessage(), "Error");
            throw new RuntimeException(ex.getMessage());
        }
    }


    private void syncAzureVMs(AzureTenant azureTenant) {
        // Delete all VMs for this azureTenant and re-create the new ones
        azureVMRepository.deleteAllByAzureTenant(azureTenant);
        PagedIterable<VirtualMachine> vms = this.azureResourceManager.virtualMachines().list();
        List<AzureVM> azureVMs = StreamSupport.stream(vms.spliterator(), false)
                .map(vm -> AzureVM.builder()
                        .azureVmId(vm.vmId())
                        .instanceId(vm.id())
                        .name(vm.name())
                        .computerName(vm.computerName())
                        .powerState(vm.powerState().toString())
                        .size(vm.size().getValue())
                        .osType(vm.osType().toString())
                        .publicIpInstanceId(vm.getPrimaryPublicIPAddressId())
                        .resourceGroupName(vm.resourceGroupName())
                        .osDiskSize(vm.osDiskSize())
                        .region(vm.region().name())
                        .securityType(vm.securityType().toString())
                        .type(vm.type())
                        .resourceIdentityType(vm.innerModel().identity() != null ? vm.innerModel().identity().type().name() : null)
                        .ipAddress(vm.getPrimaryPublicIPAddress().ipAddress())
                        .syncedAt(new Date())
                        .azureTenant(azureTenant)
                        .wsTenantName(wsTenantName)
                        .build())
                .collect(Collectors.toList());
        azureVMRepository.saveAll(azureVMs);
        backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_TENANT_SAVED, "Info");
    }

}
