package com.ws.service;


import com.microsoft.graph.models.Organization;
import com.microsoft.graph.models.User;
import com.microsoft.graph.requests.GraphServiceClient;
import com.microsoft.graph.requests.UserCollectionPage;
import com.ws.azureAdIntegration.entity.*;
import com.ws.azureAdIntegration.repository.*;
import com.ws.cofiguration.azure.GraphServiceClientFactory;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
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
    final AzureAppRolesRepository azureAppRolesRepository;
    final AzureUserGroupMembershipRepository azureUserGroupMembershipRepository;
    final AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository;
    final AzureUserCredentialRepository azureUserCredentialRepository;

    @Autowired
    public AzureADSyncService(GraphServiceClientFactory graphServiceClientFactory, AzureUserRepository azureUserRepository, AzureGroupRepository azureGroupRepository, AzureDeviceRepository azureDeviceRepository, AzureTenantRepository azureTenantRepository, AzureApplicationRepository azureApplicationRepository, AzureAppRolesRepository azureAppRolesRepository, AzureUserGroupMembershipRepository azureUserGroupMembershipRepository, AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository, AzureUserCredentialRepository azureUserCredentialRepository) {
        this.graphServiceClientFactory = graphServiceClientFactory;
        this.azureUserRepository = azureUserRepository;
        this.azureGroupRepository = azureGroupRepository;
        this.azureDeviceRepository = azureDeviceRepository;
        this.azureTenantRepository = azureTenantRepository;
        this.azureApplicationRepository = azureApplicationRepository;
        this.azureAppRolesRepository = azureAppRolesRepository;
        this.azureUserGroupMembershipRepository = azureUserGroupMembershipRepository;
        this.azureUserDeviceRelationshipRepository = azureUserDeviceRelationshipRepository;
        this.azureUserCredentialRepository = azureUserCredentialRepository;
    }

    public void syncAzureData(Integer wsTenantId) {
        AzureUserCredential azureUserCredential = Optional.ofNullable(azureUserCredentialRepository.findByWsTenantId(wsTenantId).get())
                .orElseThrow(() -> new RuntimeException("No Azure AD configuration found!"));
        String tenantId = azureUserCredential.getTenantId();
        this.graphClient = graphServiceClientFactory.createClient(azureUserCredential.getClientId(), azureUserCredential.getClientSecret(), tenantId);
        this.wsTenantId = wsTenantId;
        AzureTenant azureTenant = syncTenantData(tenantId);
        List<AzureUser> azureUsers = syncUsersData(azureTenant);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AzureTenant syncTenantData(String tenantId) {
        Organization organization = this.graphClient.organization(tenantId)
                .buildRequest()
                .get();
        AzureTenant foundAzureTenant = AzureTenant.fromAzureOrganization(organization, azureTenantRepository.findByAzureId(tenantId).get());
        return azureTenantRepository.save(foundAzureTenant);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AzureUser> syncUsersData(AzureTenant azureTenant) {
        UserCollectionPage result = this.graphClient.users()
                .buildRequest()
                .get();

        Map<String, AzureUser> existingAzureUserMap = azureUserRepository.findAllByAzureTenant(azureTenant)
                .stream()
                .collect(Collectors.toMap(AzureUser::getAzureId, Function.identity()));

        List<AzureUser> azureUsers = result.getCurrentPage().stream()
                .map(user -> existingAzureUserMap.containsKey(user.id)
                        ? AzureUser.fromUser(user, existingAzureUserMap.get(user.id))
                        : AzureUser.fromUser(user, AzureUser.builder()
                        .wsTenantId(this.wsTenantId)
                        .azureTenant(azureTenant)
                        .build()))
                .collect(Collectors.toList());

        existingAzureUserMap.clear();
        return azureUserRepository.saveAll(azureUsers);
    }
}
