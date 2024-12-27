package com.ws.azureAdIntegration.service;

import com.ws.azureAdIntegration.entity.*;
import com.ws.azureAdIntegration.repository.*;
import com.ws.azureResourcesIntegration.dto.UserGroupAndRolesResponse;
import com.ws.projection.UserGroupAndRolesProjection;
import com.ws.projection.UserGroupsNameProjection;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureADService {
    final AzureUserRepository azureUserRepository;
    final AzureApplicationRepository azureApplicationRepository;
    final AzureAppRolesRepository azureAppRolesRepository;
    final AzureTenantRepository azureTenantRepository;
    final AzureGroupRepository azureGroupRepository;
    final AzureDeviceRepository azureDeviceRepository;
    final AzureUserGroupMembershipRepository azureUserGroupMembershipRepository;
    final AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository;
    final AzureUserCredentialRepository azureUserCredentialRepository;
    final BackendApplicationLogservice backendApplicationLogservice;

    @Autowired
    public AzureADService(AzureUserRepository azureUserRepository, AzureApplicationRepository azureApplicationRepository, AzureAppRolesRepository azureAppRolesRepository, AzureTenantRepository azureTenantRepository, AzureGroupRepository azureGroupRepository, AzureUserGroupMembershipRepository azureUserGroupMembershipRepository, AzureDeviceRepository azureDeviceRepository, AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository, AzureUserCredentialRepository azureUserCredentialRepository, BackendApplicationLogservice backendApplicationLogservice) {
        this.azureUserRepository = azureUserRepository;
        this.azureApplicationRepository = azureApplicationRepository;
        this.azureAppRolesRepository = azureAppRolesRepository;
        this.azureTenantRepository = azureTenantRepository;
        this.azureGroupRepository = azureGroupRepository;
        this.azureUserGroupMembershipRepository = azureUserGroupMembershipRepository;
        this.azureDeviceRepository = azureDeviceRepository;
        this.azureUserDeviceRelationshipRepository = azureUserDeviceRelationshipRepository;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
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

    @Transactional(readOnly = true)
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

    public List<UserGroupAndRolesResponse> getUserDetailsWithGroupNamesAndRoleNamesUsingTenantName(String wsTenantName) {
        AzureTenant azureTenant = getAzureTenantUsingwsTenantEmail(wsTenantName);
        List<UserGroupAndRolesProjection> resultSets = azureUserRepository.getUserDetailsWithGroupNamesAndRoleNamesUsingTenantName(wsTenantName, azureTenant.getId());
        if (CollectionUtils.isEmpty(resultSets)) {
            throw new RuntimeException("No data found");
        }
        return resultSets.stream()
                .map((resultSet -> UserGroupAndRolesResponse.builder()
                        .id(resultSet.getId())
                        .azureUserId(resultSet.getAzureUserId())
                        .displayName(resultSet.getDisplayName())
                        .syncedAt(resultSet.getSyncedAt())
                        .groups(new ArrayList<>(List.of(resultSet.getGroups().split(","))))
                        .roleDefinitions(new ArrayList<>(List.of(resultSet.getRoles().split(","))))
                        .build())).collect(Collectors.toList());
    }

    public AzureUser getAzureUserById(String azureUserId) {
        return azureUserRepository.findByAzureId(azureUserId).orElseThrow(() -> new RuntimeException("No azuer user found with provided id: " + azureUserId));
    }

    public List<Map<String, Object>> getGroupNamesForUser(Integer userId) {
        List<UserGroupsNameProjection> userGroups = azureGroupRepository.getGroupNamesForUser(userId);
        if (userGroups.isEmpty()) {
            throw new RuntimeException("No data found");
        }
        return userGroups.stream()
                .map(userGroup -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", userGroup.getId());
                    map.put("azureGroupId", userGroup.getAzureGroupId());
                    map.put("displayName", userGroup.getDisplayName());
                    return map;
                })
                .collect(Collectors.toList());
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
