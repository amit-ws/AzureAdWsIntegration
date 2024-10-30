package com.ws.service;


import com.microsoft.graph.models.DirectoryObject;
import com.microsoft.graph.models.Organization;
import com.microsoft.graph.requests.*;
import com.ws.azureAdIntegration.entity.*;
import com.ws.azureAdIntegration.repository.*;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import com.ws.cofiguration.azure.GraphServiceClientFactory;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureADSyncService {
    Integer wsTenantId;
    GraphServiceClient graphClient;
    final GraphServiceClientFactory graphServiceClientFactory;
    final AzureUserRepository azureUserRepository;
    final AzureGroupRepository azureGroupRepository;
    final AzureDeviceRepository azureDeviceRepository;
    final AzureTenantRepository azureTenantRepository;
    final AzureApplicationRepository azureApplicationRepository;
    final AzureUserGroupMembershipRepository azureUserGroupMembershipRepository;
    final AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository;
    final AzureUserCredentialRepository azureUserCredentialRepository;

    @Autowired
    public AzureADSyncService(GraphServiceClientFactory graphServiceClientFactory, AzureUserRepository azureUserRepository, AzureGroupRepository azureGroupRepository, AzureDeviceRepository azureDeviceRepository,
                              AzureTenantRepository azureTenantRepository, AzureApplicationRepository azureApplicationRepository, AzureUserGroupMembershipRepository azureUserGroupMembershipRepository,
                              AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository, AzureUserCredentialRepository azureUserCredentialRepository) {
        this.graphServiceClientFactory = graphServiceClientFactory;
        this.azureUserRepository = azureUserRepository;
        this.azureGroupRepository = azureGroupRepository;
        this.azureDeviceRepository = azureDeviceRepository;
        this.azureTenantRepository = azureTenantRepository;
        this.azureApplicationRepository = azureApplicationRepository;
        this.azureUserGroupMembershipRepository = azureUserGroupMembershipRepository;
        this.azureUserDeviceRelationshipRepository = azureUserDeviceRelationshipRepository;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncAzureData(Integer wsTenantId, String tenantId, String clientId, String clientSecret) {
        this.graphClient = graphServiceClientFactory.createClient(clientId, clientSecret, tenantId);
        this.wsTenantId = wsTenantId;
        AzureTenant azureTenant = syncTenantData(tenantId);
        syncApplications(azureTenant);
        List<AzureUser> azureUsers = syncUsersData(azureTenant);
        List<AzureGroup> azureGroups = syncGroupsData(azureTenant);
        List<AzureDevice> azureDevices = syncDevicesData(azureTenant);
        syncUsersGroupsMembershipData(azureUsers, azureGroups);
        syncUsersDeviceRelationshipData(azureUsers, azureDevices);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncAzureData(String wsTenantEmail) {
        /**
         * Add logic to fetch the ws_teanant_id using email address
         */
        Integer wsTenantId = 1;
        AzureUserCredential azureUserCredential = Optional.ofNullable(azureUserCredentialRepository.findByWsTenantId(wsTenantId).get())
                .orElseThrow(() -> new RuntimeException("No Azure AD configuration found!"));
        String tenantId = azureUserCredential.getTenantId();
        String clientSecret = Optional.ofNullable(azureUserCredential.getClientSecret())
                .map(secret -> {
                    try {
                        return EncryptionUtil.decrypt(secret);
                    } catch (Exception e) {
                        log.error("Decryption error: ", e.getMessage());
                        throw new RuntimeException("Failed to decrypt client secret");
                    }
                })
                .orElse(null);
        this.graphClient = graphServiceClientFactory.createClient(azureUserCredential.getClientId(), clientSecret, tenantId);
        this.wsTenantId = wsTenantId;
        AzureTenant azureTenant = syncTenantData(tenantId);
        syncApplications(azureTenant);
        List<AzureUser> azureUsers = syncUsersData(azureTenant);
        List<AzureGroup> azureGroups = syncGroupsData(azureTenant);
        List<AzureDevice> azureDevices = syncDevicesData(azureTenant);
        syncUsersGroupsMembershipData(azureUsers, azureGroups);
        syncUsersDeviceRelationshipData(azureUsers, azureDevices);
    }

    private AzureTenant syncTenantData(String tenantId) {
        Organization organization = this.graphClient.organization(tenantId)
                .buildRequest()
                .get();

        // delete the existing tenant and re-create whatever has been fetched this time
        azureTenantRepository.deleteByAzureId(tenantId);

        AzureTenant azureTenant = AzureTenant.createFromGraphOrganization(organization, AzureTenant.builder().wsTenantId(this.wsTenantId).build());
        return azureTenantRepository.save(azureTenant);
    }

    public void syncApplications(AzureTenant azureTenant) {
        ApplicationCollectionPage result = this.graphClient.applications()
                .buildRequest()
                .get();

        // Delete all applications for this azureTenant and re-create the new ones
        azureApplicationRepository.deleteAllByAzureTenant(azureTenant);

        List<AzureApplication> azureApplications = new ArrayList<>();

        result.getCurrentPage().stream().forEach((graphApp) -> {
            AzureApplication azureApp = AzureApplication.createFromGraphApplication(graphApp, AzureApplication.builder().wsTenantId(this.wsTenantId).azureTenant(azureTenant).build());
            List<AzureAppRoles> azureAppRoles = new ArrayList<>();
            graphApp.appRoles.stream().forEach((graphAppRoles) -> {
                AzureAppRoles appRoles = AzureAppRoles.createFromGraphAppRoles(graphAppRoles, AzureAppRoles.builder().build());
                appRoles.setApplication(azureApp);
                azureAppRoles.add(appRoles);
            });
            azureApp.setAppRoles(azureAppRoles);
            azureApplications.add(azureApp);
        });

        azureApplicationRepository.saveAll(azureApplications);
    }

    private List<AzureUser> syncUsersData(AzureTenant azureTenant) {
        UserCollectionPage result = this.graphClient.users()
                .buildRequest()
                .get();

        // delete all the users associated with azureTenant
        azureUserRepository.deleteAllByAzureTenant(azureTenant);

        List<AzureUser> azureUsers = result.getCurrentPage().stream()
                .map(graphUser -> AzureUser.createFromGraphUser(graphUser, AzureUser.builder()
                        .wsTenantId(this.wsTenantId)
                        .azureTenant(azureTenant)
                        .build()))
                .collect(Collectors.toList());

        return azureUserRepository.saveAll(azureUsers);
    }


    private List<AzureGroup> syncGroupsData(AzureTenant azureTenant) {
        GroupCollectionPage result = this.graphClient.groups()
                .buildRequest()
                .get();

        // Delete the existing groups and create a fresh ones from whatever has been fetched
        azureGroupRepository.deleteAllByAzureTenant(azureTenant);

        List<AzureGroup> azureGroups = result.getCurrentPage().stream()
                .map(group -> AzureGroup.createFromGraphGroup(group, AzureGroup.builder()
                        .wsTenantId(this.wsTenantId)
                        .azureTenant(azureTenant)
                        .build()))
                .collect(Collectors.toList());

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

        azureUserGroupMembershipRepository.saveAll(memberships);
    }


    private List<AzureDevice> syncDevicesData(AzureTenant azureTenant) {
        DeviceCollectionPage result = this.graphClient.devices()
                .buildRequest()
                .get();

        // Delete the existing devices for this azureTenant and re-create the fresh ones
        azureDeviceRepository.deleteAllByAzureTenant(azureTenant);

        List<AzureDevice> azureDevices = result.getCurrentPage().stream()
                .map(device -> AzureDevice.createFromGraphDevice(device, AzureDevice.builder()
                        .wsTenantId(this.wsTenantId)
                        .azureTenant(azureTenant)
                        .build()))
                .collect(Collectors.toList());

        return azureDeviceRepository.saveAll(azureDevices);
    }

    public void syncUsersDeviceRelationshipData(List<AzureUser> azureUsers, List<AzureDevice> azureDevices) {
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
        azureUserDeviceRelationshipRepository.saveAll(userDevices);
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
}
