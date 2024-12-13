package com.ws.azureAdIntegration.service;


import com.microsoft.graph.models.DirectoryObject;
import com.microsoft.graph.models.Organization;
import com.microsoft.graph.requests.*;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.entity.*;
import com.ws.azureAdIntegration.repository.*;
import com.ws.azureAdIntegration.util.AzureAuthUtil;
import com.ws.azureAdIntegration.util.AzureEntityUtil;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureADSyncService {
    String wsTenantName;
    String tenantEmail = "dummy@gmail.com";
    GraphServiceClient graphClient;
    final AzureUserRepository azureUserRepository;
    final AzureGroupRepository azureGroupRepository;
    final AzureDeviceRepository azureDeviceRepository;
    final AzureTenantRepository azureTenantRepository;
    final AzureApplicationRepository azureApplicationRepository;
    final AzureUserGroupMembershipRepository azureUserGroupMembershipRepository;
    final AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository;
    final AzureUserCredentialRepository azureUserCredentialRepository;
    final BackendApplicationLogservice backendApplicationLogservice;
    final AzureAuthUtil azureAuthUtil;

    @Autowired
    public AzureADSyncService(AzureUserRepository azureUserRepository, AzureGroupRepository azureGroupRepository, AzureDeviceRepository azureDeviceRepository,
                              AzureTenantRepository azureTenantRepository, AzureApplicationRepository azureApplicationRepository, AzureUserGroupMembershipRepository azureUserGroupMembershipRepository,
                              AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository, AzureUserCredentialRepository azureUserCredentialRepository, BackendApplicationLogservice backendApplicationLogservice, AzureAuthUtil azureAuthUtil
    ) {
        this.azureUserRepository = azureUserRepository;
        this.azureGroupRepository = azureGroupRepository;
        this.azureDeviceRepository = azureDeviceRepository;
        this.azureTenantRepository = azureTenantRepository;
        this.azureApplicationRepository = azureApplicationRepository;
        this.azureUserGroupMembershipRepository = azureUserGroupMembershipRepository;
        this.azureUserDeviceRelationshipRepository = azureUserDeviceRelationshipRepository;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
        this.azureAuthUtil = azureAuthUtil;
    }

    protected void syncAzureADData(AzureTenant azureTenant) {
        try {
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_AD_DATA_SYNC_START, "Info");
            syncApplications(azureTenant);
            List<AzureUser> azureUsers = syncUsersData(azureTenant);
            List<AzureGroup> azureGroups = syncGroupsData(azureTenant);
            List<AzureDevice> azureDevices = syncDevicesData(azureTenant);
            syncUsersGroupsMembershipData(azureUsers, azureGroups);
            syncUsersDeviceRelationshipData(azureUsers, azureDevices);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_AD_DATA_SYNC_END, "Info");
        } catch (Exception ex) {
            log.error("Error occurred in syncing data from Azure AD");
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.wsTenantName, Constant.AZURE_SYNC_FAILURE, ex.getMessage(), "Error");
            throw new RuntimeException(ex.getMessage());
        }
    }


    private AzureTenant syncTenantData(String tenantId) {
        Organization organization = this.graphClient.organization(tenantId)
                .buildRequest()
                .get();

        // delete the existing tenant and re-create whatever has been fetched this time
        azureTenantRepository.deleteByAzureIdAndWsTenantName(tenantId, wsTenantName);

        AzureTenant azureTenant = AzureEntityUtil.createAzureTenantFromGraphOrganization(organization, AzureTenant.builder().wsTenantName(this.wsTenantName).build());
        backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_TENANT_SAVED, "Info");
        return azureTenantRepository.save(azureTenant);
    }

    private void syncApplications(AzureTenant azureTenant) {
        ApplicationCollectionPage result = this.graphClient.applications()
                .buildRequest()
                .get();

        // Delete all applications for this azureTenant and re-create the new ones
        azureApplicationRepository.deleteAllByAzureTenant(azureTenant);

        List<AzureApplication> azureApplications = result.getCurrentPage().stream()
                .map(graphApp -> {
                    AzureApplication azureApp = AzureEntityUtil.createAzureApplicationFromGraphApplication(
                            graphApp,
                            AzureApplication.builder().wsTenantName(this.wsTenantName).azureTenant(azureTenant).build()
                    );
                    List<AzureAppRoles> azureAppRoles = graphApp.appRoles.stream()
                            .map(graphAppRoles -> {
                                AzureAppRoles appRoles = AzureEntityUtil.createAzureAppRolesFromGraphAppRoles(
                                        graphAppRoles,
                                        AzureAppRoles.builder().build()
                                );
                                appRoles.setApplication(azureApp);
                                return appRoles;
                            })
                            .collect(Collectors.toList());

                    azureApp.setAppRoles(azureAppRoles);
                    return azureApp;
                })
                .collect(Collectors.toList());

        backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_APPLICATION__SAVED, "Info");
        azureApplicationRepository.saveAll(azureApplications);
    }

    private List<AzureUser> syncUsersData(AzureTenant azureTenant) {
        UserCollectionPage result = this.graphClient.users()
                .buildRequest()
                .get();

        // delete all the users associated with azureTenant
        azureUserRepository.deleteAllByAzureTenant(azureTenant);

        List<AzureUser> azureUsers = result.getCurrentPage().stream()
                .map(graphUser -> AzureEntityUtil.createAzureUserFromGraphUser(graphUser, AzureUser.builder()
                        .wsTenantName(this.wsTenantName)
                        .azureTenant(azureTenant)
                        .build()))
                .collect(Collectors.toList());

        backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_USERS_SAVED, "Info");
        return azureUserRepository.saveAll(azureUsers);
    }


    private List<AzureGroup> syncGroupsData(AzureTenant azureTenant) {
        GroupCollectionPage result = this.graphClient.groups()
                .buildRequest()
                .get();

        // Delete the existing groups and create a fresh ones from whatever has been fetched
        azureGroupRepository.deleteAllByAzureTenant(azureTenant);

        List<AzureGroup> azureGroups = result.getCurrentPage().stream()
                .map(group -> AzureEntityUtil.createAzureGroupFromGraphGroup(group, AzureGroup.builder()
                        .wsTenantName(this.wsTenantName)
                        .azureTenant(azureTenant)
                        .build()))
                .collect(Collectors.toList());

        backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_GROUP_SAVED, "Info");
        return azureGroupRepository.saveAll(azureGroups);
    }

    private void syncUsersGroupsMembershipData(List<AzureUser> azureUsers, List<AzureGroup> azureGroups) {
        Map<String, AzureUser> userIdMap = azureUsers.stream().collect(Collectors.toMap(AzureUser::getAzureId, n -> n));
        Map<String, AzureGroup> groupIdMap = azureGroups.stream().collect(Collectors.toMap(AzureGroup::getAzureId, n -> n));
        mapUsersGroupsMembershipData(userIdMap, groupIdMap);
    }

    private void mapUsersGroupsMembershipData(Map<String, AzureUser> azureUserMap, Map<String, AzureGroup> azureGroupMap) {
        List<AzureUserGroupMembership> memberships = new ArrayList<>();

        List<String> azureUserIds = azureUserMap.keySet().stream().collect(Collectors.toList());
        azureUserIds.stream().forEach((userId) -> {
            List<DirectoryObject> groups = this.graphClient.users(userId)
                    .memberOf()
                    .buildRequest()
                    .get()
                    .getCurrentPage()
                    .stream()
                    .filter(member -> "#microsoft.graph.group".equals(member.oDataType))
                    .collect(Collectors.toList());

            // Delete the existing user-group mappings of this userId and create a fresh mappings instead
            azureUserGroupMembershipRepository.deleteByAzureUser(azureUserMap.get(userId));

            groups.stream().forEach((group) -> {
                AzureUserGroupMembership userGroup = AzureUserGroupMembership.builder()
                        .azureUser(azureUserMap.get(userId))
                        .azureUserId(userId)
                        .azureGroup(azureGroupMap.get(group.id))
                        .azureGroupId(group.id)
                        .build();
                memberships.add(userGroup);
            });
        });

        backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_USERS_GROUPS_MAPPED, "Info");
        azureUserGroupMembershipRepository.saveAll(memberships);
    }


    private List<AzureDevice> syncDevicesData(AzureTenant azureTenant) {
        DeviceCollectionPage result = this.graphClient.devices()
                .buildRequest()
                .get();

        // Delete the existing devices for this azureTenant and re-create the fresh ones
        azureDeviceRepository.deleteAllByAzureTenant(azureTenant);

        List<AzureDevice> azureDevices = result.getCurrentPage().stream()
                .map(device -> AzureEntityUtil.createAzureDeviceFromGraphDevice(device, AzureDevice.builder()
                        .wsTenantName(this.wsTenantName)
                        .azureTenant(azureTenant)
                        .build()))
                .collect(Collectors.toList());

        backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_DEVICE_SAVED, "Info");
        return azureDeviceRepository.saveAll(azureDevices);
    }

    private void syncUsersDeviceRelationshipData(List<AzureUser> azureUsers, List<AzureDevice> azureDevices) {
        Map<String, AzureUser> azureUserMap = azureUsers.stream().collect(Collectors.toMap(AzureUser::getAzureId, n -> n));
        Map<String, AzureDevice> azureDeviceMap = azureDevices.stream().collect(Collectors.toMap(AzureDevice::getAzureId, n -> n));
        mapUsersDeviceRelationshipData(azureUserMap, azureDeviceMap);
    }


    private void mapUsersDeviceRelationshipData(Map<String, AzureUser> azureUserMap, Map<String, AzureDevice> azureDeviceMap) {
        List<AzureUserDeviceRelationship> userDevices = new ArrayList<>();

        List<String> azureUserIds = azureUserMap.keySet().stream().collect(Collectors.toList());
        azureUserIds.stream().forEach((userId) -> {
            List<DirectoryObject> devices = this.graphClient.users(userId)
                    .registeredDevices()
                    .buildRequest()
                    .get()
                    .getCurrentPage()
                    .stream()
                    .collect(Collectors.toList());

            // Delete the existing user-device mappings of this userId and create a fresh mappings instead
            azureUserDeviceRelationshipRepository.deleteByAzureUser(azureUserMap.get(userId));

            devices.stream().forEach((device) -> {
                AzureUserDeviceRelationship userDevice = AzureUserDeviceRelationship.builder()
                        .azureUser(azureUserMap.get(userId))
                        .azureUserId(userId)
                        .azureDevice(azureDeviceMap.get(device.id))
                        .azureDeviceId(device.id)
                        .build();
                userDevices.add(userDevice);
            });
        });

        backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, Constant.AZURE_USERS_DEVICES_MAPPED, "Info");
        azureUserDeviceRelationshipRepository.saveAll(userDevices);
    }
}


//        Map<String, AzureApplication> existingAzureAppMap = azureApplicationRepository.findAllByAzureTenant(azureTenant).stream()
//                .collect(Collectors.toMap(AzureApplication::getObjectId, Function.identity()));
//
//        List<AzureApplication> azureApplications = result.getCurrentPage().stream()
//                .map(application -> existingAzureAppMap.containsKey(application.id)
//                        ? AzureApplication.createFromGraphApplication(application, existingAzureAppMap.get(application.id))
//                        : AzureApplication.createFromGraphApplication(application, AzureApplication.builder().wsTenantId(this.wsTenantId).azureTenant(azureTenant).build()))
//                .collect(Collectors.toList());
//
//
//        result.getCurrentPage().stream().forEach((graphApp) -> {
//            if (existingAzureAppMap.containsKey(graphApp.id)) {
//                AzureApplication azureApp = AzureApplication.createFromGraphApplication(graphApp, existingAzureAppMap.get(graphApp.id));
////                AzureAppRoles.fromGraphAppRoles(graphApp.appRoles, azureApp.getAppRoles());
//
//                mapAppRoles(graphApp.appRoles, azureApp);
//            } else {
//
//            }
//        });


//
//        result.getCurrentPage().stream().forEach((graphApplication) -> {
//            AzureApplication application = AzureApplication.builder()
//                    .objectId(graphApplication.id)
//                    .displayName(graphApplication.displayName)
//                    .description(graphApplication.description)
//                    .publisher(graphApplication.publisherDomain)
//                    .isDeviceOnlyAuthSupported(graphApplication.isDeviceOnlyAuthSupported)
//                    .disabledByMicrosoftStatus(graphApplication.disabledByMicrosoftStatus)
//                    .publisherDomain(graphApplication.publisherDomain)
//                    .azureCreatedDateTime(graphApplication.createdDateTime)
//                    .tags(graphApplication.tags)
//                    .wsTenantId(this.wsTenantId)
//                    .createdAt(new Date())
//                    .azureTenant(azureTenant)
//                    .build();
//            application = azureApplicationRepository.save(application);
//            mapAppRoles(graphApplication.appRoles, application);
//        });
//    }
//
//
//    private void mapAppRoles(List<AppRole> graphAppRoles, AzureApplication azureApplication) {
//        graphAppRoles.stream()
//                .map(role -> AzureAppRoles.builder()
//                        .azureId(role.id)
//                        .displayName(role.displayName)
//                        .description(role.description)
//                        .isEnabled(role.isEnabled)
//                        .origin(role.origin)
//                        .value(role.value)
//                        .application(azureApplication)
//                        .createdAt(new Date())
//                        .build())
//                .collect(Collectors.toList());
//    }
//}
