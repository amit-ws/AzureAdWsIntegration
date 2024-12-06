package com.ws.azureAdIntegration.service;

import com.ws.azureAdIntegration.entity.*;
import com.ws.azureAdIntegration.repository.*;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    public List<AzureUser> fetchUsers(String wsTenantName) {
        return azureUserRepository.findAllByAzureTenant(getAzureTenantUsingwsTenantEmail(wsTenantName));
    }

    public List<AzureGroup> fetchGroups(String wsTenantName) {
        return azureGroupRepository.findAllByAzureTenant(getAzureTenantUsingwsTenantEmail(wsTenantName));
    }
//
//    @Transactional
//    public List<AzureApplication> fetchApplications(String email) {
//        AzureTenant tenant = getAzureTenantUsingwsTenantEmail(email);
//        List<AzureApplication> applications = azureApplicationRepository.findAllByAzureTenant(tenant);
//        applications.forEach(app -> Hibernate.initialize(app.getAppRoles()));
//        return applications;
//    }

    public List<AzureApplication> fetchApplications(String wsTenantName) {
        return azureApplicationRepository.findAllByAzureTenant(getAzureTenantUsingwsTenantEmail(wsTenantName));
    }


    public List<AzureAppRoles> getAppRolesForApplication(Integer appId) {
        AzureApplication azureApplication = azureApplicationRepository.findById(appId).orElseThrow(() -> new RuntimeException("No Azure application found with provided id!"));
        return azureAppRolesRepository.findAllByApplication(azureApplication);
    }

    public List<AzureDevice> fetchAzureDevices(String wsTenantName) {
        return azureDeviceRepository.findAllByAzureTenant(getAzureTenantUsingwsTenantEmail(wsTenantName));
    }

    public AzureTenant getAzureTenantUsingwsTenantEmail(String wsTenantName) {
        return azureTenantRepository.findByWsTenantName(wsTenantName).orElseThrow(() -> new RuntimeException("No tenant found with provided tenantName: " + wsTenantName));
    }

    public List<AzureUser> fetchUsersOfGroup(Integer groupId) {
        return azureUserGroupMembershipRepository.fetchUsersForGroup(getAzureGroupUsingId(groupId));
    }

    public List<AzureGroup> fetchGroupsOfUser(Integer userId) {
        return azureUserGroupMembershipRepository.fetchGroupsForUser(getAzureUserUsingId(userId));
    }

    public List<AzureDevice> fetchAzureDevicesForUser(Integer userId) {
        return azureUserDeviceRelationshipRepository.fetchDevicesForUser(getAzureUserUsingId(userId));
    }

    @Transactional
    public void deleteTenant(String tenantId) {
        azureUserCredentialRepository.deleteByTenantId(tenantId);
        azureTenantRepository.deleteByAzureId(tenantId);
    }

    private AzureUser getAzureUserUsingId(Integer userId) {
        return azureUserRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("No Azure User found with provided id: " + userId));
    }

    private AzureGroup getAzureGroupUsingId(Integer groupId) {
        return azureGroupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("No Azure User found with provided id: " + groupId));
    }


}
