package com.ws.azureAdIntegration.service;

import com.ws.azureAdIntegration.entity.*;
import com.ws.azureAdIntegration.repository.*;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureUserService {
    final AzureUserRepository azureUserRepository;
    final AzureApplicationRepository azureApplicationRepository;
    final AzureAppRolesRepository azureAppRolesRepository;
    final AzureTenantRepository azureTenantRepository;
    final AzureGroupRepository azureGroupRepository;
    final AzureDeviceRepository azureDeviceRepository;
    final AzureUserGroupMembershipRepository azureUserGroupMembershipRepository;
    final AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository;
    final AzureUserCredentialRepository azureUserCredentialRepository;

    @Autowired
    public AzureUserService(AzureUserRepository azureUserRepository, AzureApplicationRepository azureApplicationRepository, AzureAppRolesRepository azureAppRolesRepository, AzureTenantRepository azureTenantRepository, AzureGroupRepository azureGroupRepository, AzureUserGroupMembershipRepository azureUserGroupMembershipRepository, AzureDeviceRepository azureDeviceRepository, AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository, AzureUserCredentialRepository azureUserCredentialRepository) {
        this.azureUserRepository = azureUserRepository;
        this.azureApplicationRepository = azureApplicationRepository;
        this.azureAppRolesRepository = azureAppRolesRepository;
        this.azureTenantRepository = azureTenantRepository;
        this.azureGroupRepository = azureGroupRepository;
        this.azureUserGroupMembershipRepository = azureUserGroupMembershipRepository;
        this.azureDeviceRepository = azureDeviceRepository;
        this.azureUserDeviceRelationshipRepository = azureUserDeviceRelationshipRepository;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
    }

    public List<AzureUser> fetchUsers(String email) {
        return azureUserRepository.findAllByAzureTenant(getAzureTenantUsingwsTenantEmail(email));
    }

    public List<AzureGroup> fetchGroups(String email) {
        return azureGroupRepository.findAllByAzureTenant(getAzureTenantUsingwsTenantEmail(email));
    }
//
//    @Transactional
//    public List<AzureApplication> fetchApplications(String email) {
//        AzureTenant tenant = getAzureTenantUsingwsTenantEmail(email);
//        List<AzureApplication> applications = azureApplicationRepository.findAllByAzureTenant(tenant);
//        applications.forEach(app -> Hibernate.initialize(app.getAppRoles()));
//        return applications;
//    }

    public List<AzureApplication> fetchApplications(String email) {
        return azureApplicationRepository.findAllByAzureTenant(getAzureTenantUsingwsTenantEmail(email));
    }


    public List<AzureAppRoles> getAppRolesForApplication(Integer appId) {
        AzureApplication azureApplication = azureApplicationRepository.findById(appId).orElseThrow(() -> new RuntimeException("No Azure application found with provided id!"));
        return azureAppRolesRepository.findAllByApplication(azureApplication);
    }

    public List<AzureDevice> fetchAzureDevices(String email) {
        return azureDeviceRepository.findAllByAzureTenant(getAzureTenantUsingwsTenantEmail(email));
    }

    public AzureTenant getAzureTenantUsingwsTenantEmail(String email) {
        Optional<AzureTenant> azureTenantOpt = azureTenantRepository.findByWsTenantId(1);
        return azureTenantOpt.isPresent() ? azureTenantOpt.get() : null;
    }

    public List<Map<String, Object>> fetchUsersOfGroup(Integer groupId) {
        return azureUserGroupMembershipRepository.fetchUsersForGroup(groupId);
    }

    public List<Map<String, Object>> fetchAzureDevicesForUser(Integer userId) {
        return azureUserDeviceRelationshipRepository.fetchDevicesForUser(userId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteTenant(String tenantId) {
        azureUserCredentialRepository.deleteByTenantId(tenantId);
        azureTenantRepository.deleteByAzureId(tenantId);
    }



}