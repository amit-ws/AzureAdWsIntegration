package com.ws.azureAdIntegration.service;

import com.microsoft.graph.models.AppRole;
import com.microsoft.graph.models.DirectoryObject;
import com.microsoft.graph.models.Organization;
import com.microsoft.graph.requests.*;
import com.ws.azureAdIntegration.entity.*;
import com.ws.azureAdIntegration.repository.*;
import com.ws.cofiguration.azure.GraphServiceClientFactory;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureAdService {
    Integer wsTenantId;
    GraphServiceClient graphClient;
    final GraphServiceClientFactory graphServiceClientFactory;
    final AzureUserRepository azureUserRepository;
    final AzureGroupRepository azureGroupRepository;
    final AzureDeviceRepository azureDeviceRepository;
    final AzureTenantRepository azureTenantRepository;
    final AzureApplicationRepository azureApplicationRepository;
    final AzureAppRolesRepository azureAppRolesRepository;
    final AzureUserGroupMembershipRepository azureUserGroupMembershipRepository;
    final AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository;

    @Autowired
    public AzureAdService(GraphServiceClientFactory graphServiceClientFactory, AzureUserRepository azureUserRepository, AzureGroupRepository azureGroupRepository, AzureDeviceRepository azureDeviceRepository, AzureTenantRepository azureTenantRepository, AzureApplicationRepository azureApplicationRepository, AzureAppRolesRepository azureAppRolesRepository, AzureUserGroupMembershipRepository azureUserGroupMembershipRepository, AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository) {
        this.graphServiceClientFactory = graphServiceClientFactory;
        this.azureUserRepository = azureUserRepository;
        this.azureGroupRepository = azureGroupRepository;
        this.azureDeviceRepository = azureDeviceRepository;
        this.azureTenantRepository = azureTenantRepository;
        this.azureApplicationRepository = azureApplicationRepository;
        this.azureAppRolesRepository = azureAppRolesRepository;
        this.azureUserGroupMembershipRepository = azureUserGroupMembershipRepository;
        this.azureUserDeviceRelationshipRepository = azureUserDeviceRelationshipRepository;
    }

    @Async
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
    public AzureTenant syncTenantData(String tenantId) {
        Organization organization = this.graphClient.organization(tenantId)
                .buildRequest()
                .get();
        AzureTenant azureTenant = AzureTenant.builder()
                .azureId(organization.id)
                .displayName(organization.displayName)
                .countryLetterCode(organization.countryLetterCode)
                .azureCreatedDateTime(organization.createdDateTime)
                .createdAt(new Date())
                .postalCode(organization.postalCode)
                .preferredLanguage(organization.preferredLanguage)
                .state(organization.state)
                .street(organization.street)
                .tenantType(organization.tenantType)
                .wsTenantId(this.wsTenantId)
                .build();

        return azureTenantRepository.save(azureTenant);
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AzureUser> syncUsersData(AzureTenant azureTenant) {
        UserCollectionPage result = this.graphClient.users()
                .buildRequest()
                .get();
        List<AzureUser> azureUserList = result.getCurrentPage().stream()
                .map(graphUser -> AzureUser.builder()
                        .azureId(graphUser.id)
                        .displayName(graphUser.displayName)
                        .givenName(graphUser.givenName)
                        .surname(graphUser.surname)
                        .mail(graphUser.mail)
                        .userPrincipalName(graphUser.userPrincipalName)
                        .mobilePhone(graphUser.mobilePhone)
                        .jobTitle(graphUser.jobTitle)
                        .department(graphUser.department)
                        .officeLocation(graphUser.officeLocation)
                        .wsTenantId(this.wsTenantId)
                        .createdAt(new Date())
                        .azureTenant(azureTenant)
                        .build())
                .collect(Collectors.toList());
        return azureUserRepository.saveAll(azureUserList);
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AzureGroup> syncGroupsData(AzureTenant azureTenant) {
        GroupCollectionPage result = this.graphClient.groups()
                .buildRequest()
                .get();
        List<AzureGroup> azureGroupList = result.getCurrentPage().stream()
                .map(graphGroup -> AzureGroup.builder() // Use builder to create Group entity
                        .azureId(graphGroup.id)
                        .displayName(graphGroup.displayName)
                        .description(graphGroup.description)
                        .mail(graphGroup.mail)
                        .mailNickname(graphGroup.mailNickname)
                        .mailEnabled(graphGroup.mailEnabled)
                        .securityEnabled(graphGroup.securityEnabled)
                        .visibility(graphGroup.visibility)
                        .azureCreatedDateTime(graphGroup.createdDateTime)
                        .securityIdentifier(graphGroup.securityIdentifier)
                        .wsTenantId(this.wsTenantId)
                        .createdAt(new Date())
                        .azureTenant(azureTenant)
                        .build())
                .collect(Collectors.toList());
        return azureGroupRepository.saveAll(azureGroupList);
    }


    public void syncUsersGroupsMembershipData(List<AzureUser> azureUsers, List<AzureGroup> azureGroups) {
        Map<String, AzureUser> userIdMap = new HashMap<>();
        azureUsers.stream().forEach((n) -> {
            userIdMap.put(n.getAzureId(), n);
        });

        Map<String, AzureGroup> groupIdMaps = new HashMap<>();
        azureGroups.stream().forEach((n) -> {
            groupIdMaps.put(n.getAzureId(), n);
        });
        mapUsersGroupsMembershipData(userIdMap, groupIdMaps);
    }

    private void mapUsersGroupsMembershipData(Map<String, AzureUser> userIdMap, Map<String, AzureGroup> groupIdMaps) {
        List<AzureUserGroupMembership> memberships = new ArrayList<>();

        List<String> azureUserIds = new ArrayList<>();
        userIdMap.keySet().stream().forEach((n) -> {
            azureUserIds.add(n);
        });
        azureUserIds.stream().forEach((n) -> {
            DirectoryObjectCollectionWithReferencesPage members = this.graphClient.users(n)
                    .memberOf()
                    .buildRequest()
                    .get();
            List<DirectoryObject> groups = members.getCurrentPage()
                    .stream()
                    .filter(member -> "#microsoft.graph.group".equals(member.oDataType))
                    .collect(Collectors.toList());

            groups.stream().forEach((m) -> {
                AzureUserGroupMembership userGroup = AzureUserGroupMembership.builder()
                        .azureUser(userIdMap.get(n))
                        .azureUserId(n)
                        .azureGroup(groupIdMaps.get(m.id))
                        .azureGroupId(m.id)
                        .build();
                memberships.add(userGroup);
            });
        });

        azureUserGroupMembershipRepository.saveAll(memberships);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AzureDevice> syncDevicesData(AzureTenant azureTenant) {
        DeviceCollectionPage result = this.graphClient.devices()
                .buildRequest()
                .get();

        List<AzureDevice> azureDeviceList = result.getCurrentPage().stream()
                .map(graphDevice -> AzureDevice.builder()
                        .azureId(graphDevice.id)
                        .deviceId(graphDevice.deviceId)
                        .displayName(graphDevice.displayName)
                        .operatingSystem(graphDevice.operatingSystem)
                        .operatingSystemVersion(graphDevice.operatingSystemVersion)
                        .accountEnabled(Boolean.TRUE.equals(graphDevice.accountEnabled))
                        .deviceVersion(graphDevice.deviceVersion)
                        .azureRegistrationDateTime(graphDevice.registrationDateTime)
                        .wsTenantId(this.wsTenantId)
                        .createdAt(new Date())
                        .azureTenant(azureTenant)
                        .build())
                .collect(Collectors.toList());
        return azureDeviceRepository.saveAll(azureDeviceList);
    }


    public void syncApplications(AzureTenant azureTenant) {
        ApplicationCollectionPage applicationCollection = this.graphClient.applications()
                .buildRequest()
                .get();
        applicationCollection.getCurrentPage().stream().forEach((graphApplication) -> {
            AzureApplication application = AzureApplication.builder()
                    .objectId(graphApplication.id)
                    .displayName(graphApplication.displayName)
                    .description(graphApplication.description)
                    .publisher(graphApplication.publisherDomain)
                    .isDeviceOnlyAuthSupported(graphApplication.isDeviceOnlyAuthSupported)
                    .disabledByMicrosoftStatus(graphApplication.disabledByMicrosoftStatus)
                    .publisherDomain(graphApplication.publisherDomain)
                    .azureCreatedDateTime(graphApplication.createdDateTime)
                    .tags(graphApplication.tags)
                    .wsTenantId(this.wsTenantId)
                    .createdAt(new Date())
                    .azureTenant(azureTenant)
                    .build();
            application = azureApplicationRepository.save(application);
            mapAppRoles(graphApplication.appRoles, application);
        });
    }

    public void syncUsersDeviceRelationshipData(List<AzureUser> azureUsers, List<AzureDevice> azureDevices) {
        Map<String, AzureUser> azureUserMap = new HashMap<>();
        azureUsers.stream().forEach((n) -> {
            azureUserMap.put(n.getAzureId(), n);
        });

        Map<String, AzureDevice> azureDeviceMap = new HashMap<>();
        azureDevices.stream().forEach((n) -> {
            azureDeviceMap.put(n.getAzureId(), n);
        });
        mapUsersDeviceRelationshipData(azureUserMap, azureDeviceMap);
    }


    private void mapUsersDeviceRelationshipData(Map<String, AzureUser> azureUserMap, Map<String, AzureDevice> azureDeviceMap) {
        List<AzureUserDeviceRelationship> userDevices = new ArrayList<>();

        List<String> azureUserIds = new ArrayList<>();
        azureUserMap.keySet().stream().forEach((n) -> {
            azureUserIds.add(n);
        });

        azureUserIds.stream().forEach((n) -> {
            DirectoryObjectCollectionWithReferencesPage result = this.graphClient.users(n)
                    .registeredDevices()
                    .buildRequest()
                    .get();
            List<DirectoryObject> devices = result.getCurrentPage()
                    .stream()
                    .collect(Collectors.toList());
            devices.stream().forEach((m) -> {
                AzureUserDeviceRelationship userDevice = AzureUserDeviceRelationship.builder()
                        .azureUser(azureUserMap.get(n))
                        .azureUserId(n)
                        .azureDevice(azureDeviceMap.get(m))
                        .azureDeviceId(m.id)
                        .build();
                userDevices.add(userDevice);
            });
        });
        azureUserDeviceRelationshipRepository.saveAll(userDevices);
    }

    private void mapAppRoles(List<AppRole> graphAppRoles, AzureApplication azureApplication) {
        List<AzureAppRoles> azureAppRoles = graphAppRoles.stream()
                .map(role -> AzureAppRoles.builder()
                        .azureId(role.id)
                        .displayName(role.displayName)
                        .description(role.description)
                        .isEnabled(role.isEnabled)
                        .origin(role.origin)
                        .value(role.value)
                        .application(azureApplication)
                        .createdAt(new Date())
                        .build())
                .collect(Collectors.toList());

        azureAppRolesRepository.saveAll(azureAppRoles);
    }

}


