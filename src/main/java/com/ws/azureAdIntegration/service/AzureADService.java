package com.ws.azureAdIntegration.service;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.dto.AzureGroupResponse;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.entity.*;
import com.ws.azureAdIntegration.repository.*;
import com.ws.azureAdIntegration.util.GenericUtil;
import com.ws.azureKuberntesJIT.service.K8CustomResourceRequestService;
import com.ws.azureKuberntesJIT.service.K8ResourcesDataService;
import com.ws.azureResourcesIntegration.dto.AzureRoleAssignmentResponse;
import com.ws.azureResourcesIntegration.dto.UserGroupAndRolesResponse;
import com.ws.azureResourcesIntegration.repository.AzureUserConfigureRepository;
import com.ws.azureResourcesIntegration.repository.PublishedResourcesRepository;
import com.ws.azureResourcesIntegration.service.CustomRoleAssignmentService;
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
    final CustomRoleAssignmentService customRoleAssignmentService;
    final PublishedResourcesRepository publishedResourcesRepository;
    final AzureUserConfigureRepository azureUserConfigureRepository;
    final BackendApplicationLogservice backendApplicationLogservice;
    final AzureTenantService azureTenantService;
    final AzureUserCredentialService azureUserCredentialService;
    final AzureADInitializerService azureADInitializerService;
    final K8ResourcesDataService k8ResourcesDataService;
    final K8CustomResourceRequestService k8CustomResourceRequestService;

    @Autowired
    public AzureADService(AzureUserRepository azureUserRepository, AzureApplicationRepository azureApplicationRepository, AzureAppRolesRepository azureAppRolesRepository, AzureTenantRepository azureTenantRepository, AzureGroupRepository azureGroupRepository, AzureUserGroupMembershipRepository azureUserGroupMembershipRepository, AzureDeviceRepository azureDeviceRepository, AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository, AzureUserCredentialRepository azureUserCredentialRepository, CustomRoleAssignmentService customRoleAssignmentService, PublishedResourcesRepository publishedResourcesRepository, AzureUserConfigureRepository azureUserConfigureRepository, BackendApplicationLogservice backendApplicationLogservice, AzureTenantService azureTenantService, AzureUserCredentialService azureUserCredentialService, AzureADInitializerService azureADInitializerService, K8ResourcesDataService k8ResourcesDataService, K8CustomResourceRequestService k8CustomResourceRequestService) {
        this.azureUserRepository = azureUserRepository;
        this.azureApplicationRepository = azureApplicationRepository;
        this.azureAppRolesRepository = azureAppRolesRepository;
        this.azureTenantRepository = azureTenantRepository;
        this.azureGroupRepository = azureGroupRepository;
        this.azureUserGroupMembershipRepository = azureUserGroupMembershipRepository;
        this.azureDeviceRepository = azureDeviceRepository;
        this.azureUserDeviceRelationshipRepository = azureUserDeviceRelationshipRepository;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.customRoleAssignmentService = customRoleAssignmentService;
        this.publishedResourcesRepository = publishedResourcesRepository;
        this.azureUserConfigureRepository = azureUserConfigureRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
        this.azureTenantService = azureTenantService;
        this.azureUserCredentialService = azureUserCredentialService;
        this.azureADInitializerService = azureADInitializerService;
        this.k8ResourcesDataService = k8ResourcesDataService;
        this.k8CustomResourceRequestService = k8CustomResourceRequestService;
    }

    public List<AzureUser> fetchUsers(String wsTenantName) {
        return azureUserRepository.findAllByAzureTenant(azureTenantService.getAzureTenantUsingWsTenantName(wsTenantName));
    }

    public List<AzureGroup> fetchGroups(String wsTenantName) {
        return azureGroupRepository.findAllByAzureTenant(azureTenantService.getAzureTenantUsingWsTenantName(wsTenantName));
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
        return azureApplicationRepository.findAllByAzureTenant(azureTenantService.getAzureTenantUsingWsTenantName(wsTenantName));
    }


    public List<AzureAppRoles> getAppRolesForApplication(Integer appId) {
        AzureApplication azureApplication = azureApplicationRepository.findById(appId).orElseThrow(() -> new RuntimeException("No Azure application found with provided id!"));
        return azureAppRolesRepository.findAllByApplication(azureApplication);
    }

    public List<AzureDevice> fetchAzureDevices(String wsTenantName) {
        return azureDeviceRepository.findAllByAzureTenant(azureTenantService.getAzureTenantUsingWsTenantName(wsTenantName));
    }

//    public AzureTenant getAzureTenantUsingWsTenantName(String wsTenantName) {
//        return azureTenantService.getAzureTenantUsingWsTenantName(wsTenantName);
//    }

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

    @Transactional
    public void deleteTenantV2(String wsTenantName) {
        GenericUtil.ensureNotNull(wsTenantName, "WhiteSwan tenant name cannot be null");
        azureTenantService.getAzureTenantUsingWsTenantName(wsTenantName);
        customRoleAssignmentService.revokeApprovedRolesInAzureAndDeleteAllForWsTenant(wsTenantName, null);
        k8CustomResourceRequestService.revokeCustomRequestsByWsTenantNameAndSubscriptionIds(wsTenantName, CloudProviderType.AZURE, null);
        publishedResourcesRepository.deleteAllByWsTenantName(wsTenantName, null);
        azureUserConfigureRepository.deleteAllByWsTenantName(wsTenantName);
        azureUserCredentialRepository.deleteByWsTenantName(wsTenantName);
        azureTenantRepository.deleteByWsTenantName(wsTenantName);
        k8ResourcesDataService.deleteByWsTenantNameAndSubscriptionIds(wsTenantName, CloudProviderType.AZURE, null);
        backendApplicationLogservice.deleteLogsForTenant(wsTenantName);
    }

    public List<UserGroupAndRolesResponse> getUserDetailsWithGroupNamesAndRoleNamesUsingTenantName(String wsTenantName, String azureId) {
        AzureTenant azureTenant = azureTenantService.getAzureTenantUsingWsTenantName(wsTenantName);
        List<UserGroupAndRolesProjection> resultSets = azureUserRepository.getUserDetailsWithGroupNamesAndRoleNamesUsingTenantName(wsTenantName, azureTenant.getId(), azureId);
        if (CollectionUtils.isEmpty(resultSets)) {
            throw new RuntimeException("No data found");
        }
        return resultSets.stream()
                .map(resultSet -> UserGroupAndRolesResponse.builder()
                        .id(resultSet.getId())
                        .azureUserId(resultSet.getAzureUserId())
                        .userPrincipalName(resultSet.getUserPrincipalName())
                        .displayName(resultSet.getDisplayName())
                        .createdDateTime(resultSet.getCreatedDateTime())
                        .syncedAt(resultSet.getSyncedAt())
//                        .groups(GenericUtil.splitStringConvertToList(resultSet.getGroups()))
                        .groupResponses(Optional.ofNullable(resultSet.getGroups()).map(this::convertGroupsToDTO).orElse(Collections.emptyList()))
//                        .groups(GenericUtil.getOrEmptyList(() -> Collections.singletonList(resultSet.getGroups())))
                        .roleDefinitions(GenericUtil.splitStringConvertToList(resultSet.getRoles()))
                        .roleDefinitionList(Optional.ofNullable(resultSet.getRoles()).map(this::convertRoleAssignmentsToDTOs).orElse(Collections.emptyList()))
                        .build())
                .collect(Collectors.toList());

    }

    private List<AzureGroupResponse> convertGroupsToDTO(String groupsString) {
        List<AzureGroupResponse> groupDTOs = new ArrayList<>();
        String[] groupEntries = groupsString.split(",");
        for (String groupEntry : groupEntries) {
            String[] parts = groupEntry.split(":");
            if (parts.length == 2) {
                groupDTOs.add(AzureGroupResponse.builder().azureId(parts[0]).displayName(parts[1]).build());
            }
        }
        return groupDTOs;
    }

    private List<AzureRoleAssignmentResponse> convertRoleAssignmentsToDTOs(String rolesString) {
        List<AzureRoleAssignmentResponse> roleDTOs = new ArrayList<>();
        String[] roleEntries = rolesString.split(",");
        for (String roleEntry : roleEntries) {
            String[] parts = roleEntry.split(":");
            if (parts.length == 2) {
                roleDTOs.add(AzureRoleAssignmentResponse.builder().assignmentAzureId(parts[0]).roleName(parts[1]).build());
            }
        }
        return roleDTOs;
    }


    public AzureUser getAzureUserById(String azureUserId) {
        return azureUserRepository.findByAzureId(azureUserId).orElseThrow(() -> new RuntimeException("No azure user found with provided id: " + azureUserId));
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

    @Transactional
    public void removeUserGroupMembership(String userId, String groupId, String wsTenantName) {
        AzureUserCredentialDTO credentialDTO = azureUserCredentialService.findWSTenantIdWithDecryptedSecret(wsTenantName);
        Integer id = azureUserGroupMembershipRepository
                .findMemberShipUsingAzureUserIdAndAzureGroupId(userId, groupId)
                .map(AzureUserGroupMembership::getId)
                .orElseThrow(() -> new RuntimeException(String.format("No membership found for User ID: %s and Group ID: %s", userId, groupId)));
        try {
            azureADInitializerService.initializeGraphClient(credentialDTO, null);
            azureADInitializerService.removeUserGroupMembership(userId, groupId);
            azureUserGroupMembershipRepository.deleteById(id);
        } catch (Exception exp) {
            if (exp.getMessage().contains("404")) {
                throw new RuntimeException(String.format("No group membership found in Azure for provided user: %s and group: %s details", userId, groupId));
            } else if (exp.getMessage().contains("403")) {
                throw new RuntimeException(String.format("Insufficient privileges to perform this action. Please ensure that your Azure account has the required permission: %s", "Group.ReadWrite.All"));
            }
            throw exp;
        }
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
