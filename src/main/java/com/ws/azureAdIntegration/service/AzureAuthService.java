package com.ws.azureAdIntegration.service;

import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.dto.CreateAzureConfiguration;
import com.ws.azureAdIntegration.dto.AzureDataResponse;
import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureAdIntegration.exception.AzureDataException;
import com.ws.azureAdIntegration.repository.AzureUserCredentialRepository;
import com.ws.azureAdIntegration.repository.AzureUserRepository;
import com.ws.azureAdIntegration.util.AzureAuthUtil;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import com.ws.azureKuberntesJIT.service.K8CustomResourceRequestService;
import com.ws.azureKuberntesJIT.service.K8ResourcesDataService;
import com.ws.azureResourcesIntegration.repository.PublishedResourcesRepository;
import com.ws.azureResourcesIntegration.service.AzureResourceDataService;
import com.ws.azureResourcesIntegration.service.AzureResourceSyncService;
import com.ws.azureResourcesIntegration.service.CustomRoleAssignmentService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import okhttp3.Request;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureAuthService {
    final Logger log = LoggerFactory.getLogger(this.getClass());
    final AzureUserCredentialRepository azureUserCredentialRepository;
    final BackendApplicationLogservice backendApplicationLogservice;
    final AzureSyncControlService azureSyncControlService;
    final AzureResourceSyncService azureResourceSyncService;
    final AzureUserRepository azureUserRepository;
    final PublishedResourcesRepository publishedResourcesRepository;
    final AzureUserEntityService azureUserEntityService;
    final AzureUserCredentialService azureUserCredentialService;
    final AzureResourceDataService azureResourceDataService;
    final K8ResourcesDataService k8ResourcesDataService;
    final K8CustomResourceRequestService k8CustomResourceRequestService;
    final CustomRoleAssignmentService customRoleAssignmentService;
    final AzureAuthUtil azureAuthUtil;

    @Value("${spring.cloud.azure.active-directory.redirect-uri}")
    String redirectUri;

    @Autowired
    public AzureAuthService(AzureUserCredentialRepository azureUserCredentialRepository, BackendApplicationLogservice backendApplicationLogservice, AzureSyncControlService azureSyncControlService,
                            AzureResourceSyncService azureResourceSyncService, AzureAuthUtil azureAuthUtil, AzureUserEntityService azureUserEntityService, AzureUserRepository azureUserRepository, PublishedResourcesRepository publishedResourcesRepository, AzureUserCredentialService azureUserCredentialService, AzureResourceDataService azureResourceDataService, K8ResourcesDataService k8ResourcesDataService, K8CustomResourceRequestService k8CustomResourceRequestService, CustomRoleAssignmentService customRoleAssignmentService) {
        this.azureUserCredentialRepository = azureUserCredentialRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
        this.azureSyncControlService = azureSyncControlService;
        this.azureResourceSyncService = azureResourceSyncService;
        this.azureAuthUtil = azureAuthUtil;
        this.azureUserEntityService = azureUserEntityService;
        this.azureUserRepository = azureUserRepository;
        this.publishedResourcesRepository = publishedResourcesRepository;
        this.azureUserCredentialService = azureUserCredentialService;
        this.azureResourceDataService = azureResourceDataService;
        this.k8ResourcesDataService = k8ResourcesDataService;
        this.k8CustomResourceRequestService = k8CustomResourceRequestService;
        this.customRoleAssignmentService = customRoleAssignmentService;
    }

    @Transactional
    public Map<String, String> creatAzureConfigurationV2(CreateAzureConfiguration createAzureConfiguration) {
        String wsTenantName = createAzureConfiguration.getWsTenantName().trim();
        String clientId = createAzureConfiguration.getClientId().trim();
        String tenantId = createAzureConfiguration.getTenantId().trim();
        String clientSecret = createAzureConfiguration.getClientSecret().trim();
        Set<String> subscriptionIds = createAzureConfiguration.getSubscriptionIds();

        // Check at ws tenant level
        AzureUserCredential foundCredential = getAzureUserCredentialForWSTenant(wsTenantName);
        if (ObjectUtils.isNotEmpty(foundCredential)) {
            throw new AzureDataException("Azure credentials already exist for ws tenant: " + wsTenantName);
        }
        if (azureUserCredentialService.checkIfCredentialExistsUsingTenantId(tenantId)) {
            throw new AzureDataException("Azure credentials already exist for provided azure tenant ID: " + tenantId);
        }

        log.info(Constant.VALIDATING_AZURE_TENANT_AUTHENTICATION_CREDENTIAL);
        GraphServiceClient<Request> graphClient = azureAuthUtil.validateAzureAuthenticationCredentials(tenantId, clientId, clientSecret);
        log.info(Constant.VALIDATING_AZURE_TENANT_SUBSCRIPTION_IDS);
        validateRequestedSubscriptionIds(subscriptionIds, tenantId, clientId, clientSecret);

        log.info("Thread name for azure user credential creation: {}", Thread.currentThread().getName());
        AzureUserCredential azureUserCredential = azureUserCredentialRepository.save(
                AzureUserCredential.builder()
                        .clientId(clientId)
                        .clientSecret(EncryptionUtil.getEncryptedKey(clientSecret, Constant.AZURE_CLIENT_SECRET))
                        .tenantId(tenantId)
                        .subscriptionIds(createAzureConfiguration.getSubscriptionIds())
                        .wsTenantName(wsTenantName)
                        .syncStatus(true)
                        .createdAt(new Date())
                        .build()
        );
        log.info("Azure user credential saved for WS Tenant: {}", wsTenantName);
        backendApplicationLogservice.saveAuditLog(wsTenantName, "dummy@gmail.com", Constant.ADD, Constant.AZURE_CREDENTIALS_SAVED, "Info");
        AzureUserCredentialDTO azureUserCredentialDTO = azureUserCredentialService.mapFromAzureUserCredentialAndDecryptSecretKey(azureUserCredential);
        backendApplicationLogservice.saveAuditLog(wsTenantName, "dummy@gmail.com", Constant.ADD, Constant.AZURE_AD_DATA_SYNC_START, "INFO");
        log.info("Azure data sync started");
        azureSyncControlService.syncAzureData(graphClient, azureUserCredentialDTO);
        return Collections.singletonMap("message", "Azure credentials configured successfully");
    }

//    @Transactional
//    public Map<String, String> creatAzureConfiguration(CreateAzureConfiguration createAzureConfiguration) {
//        String subscriptionId = Optional.ofNullable(createAzureConfiguration.getSubscriptionId()).filter(subId -> !subId.isEmpty()).map(String::trim).orElse(null);
//        String wsTenantName = createAzureConfiguration.getWsTenantName().trim();
//        String clientId = createAzureConfiguration.getClientId().trim();
//        String tenantId = createAzureConfiguration.getTenantId().trim();
//        Optional.ofNullable(getAzureUserCredentialForWSTenant(wsTenantName))
//                .ifPresent(credential -> {
//                    if (StringUtils.equalsIgnoreCase(credential.getClientId(), clientId)) {
//                        throw new RuntimeException("Azure credentials already exist for client ID: " + clientId);
//                    }
//                    if (StringUtils.isNotEmpty(credential.getSubscriptionId()) &&
//                            StringUtils.equalsIgnoreCase(credential.getSubscriptionId(), subscriptionId)) {
//                        throw new RuntimeException("Azure credentials already exist for subscription ID: " + subscriptionId);
//                    }
//                });
//        log.info("Validating user's Azure-AD credentials..");
//        GraphServiceClient<Request> graphClient = azureAuthUtil.validateAzureAuthenticationCredentials(tenantId, clientId, createAzureConfiguration.getClientSecret());
//        AzureUserCredential azureUserCredential = azureUserCredentialRepository.save(
//                AzureUserCredential.builder()
//                        .clientId(clientId)
//                        .clientSecret(EncryptionUtil.getEncryptedKey(createAzureConfiguration.getClientSecret(), Constant.AZURE_CLIENT_SECRET))
//                        .tenantId(tenantId)
//                        .subscriptionId(subscriptionId)
//                        .wsTenantName(wsTenantName)
//                        .syncStatus(true)
//                        .createdAt(new Date())
//                        .build()
//        );
//        backendApplicationLogservice.saveAuditLog(wsTenantName, "dummy@gmail.com", Constant.ADD, Constant.AZURE_CREDENTIALS_SAVED, "Info");
//        AzureUserCredentialDTO azureUserCredentialDTO = azureUserCredentialService.mapFromAzureUserCredentialAndDecryptSecretKey(azureUserCredential);
//        log.info("Thread name: {}", Thread.currentThread().getName());
//        backendApplicationLogservice.saveAuditLog(wsTenantName, "dummy@gmail.com", Constant.ADD, Constant.AZURE_AD_DATA_SYNC_START, "INFO");
////        azureSyncControlService.syncAzureData(graphClient, azureUserCredentialDTO);
//        return Collections.singletonMap("message", "Credentials configured successfully");
//    }


    public AzureUserCredentialDTO fetchAzureConfiguration(String tenantName) {
        return azureUserCredentialService.findWSTenantIdWithDecryptedSecret(tenantName);
    }


    public String generateAzureSSOUrl(String email) {
        azureUserEntityService.getAzureUserUsingEmail(email);
        AzureUserCredential azureUserCredential = getAzureUserCredentialForWSTenant(getAzureUserUsingEmail(email).getWsTenantName());

        return UriComponentsBuilder.fromHttpUrl("https://login.microsoftonline.com/")
                .pathSegment(azureUserCredential.getTenantId(), Constant.OAUTH, Constant.OAUTH_VERSION, Constant.OAUTH_TYPE)
                .queryParam(Constant.CLIENT_ID_PARAM, azureUserCredential.getClientId())
                .queryParam(Constant.RESPONSE_TYPE_PARAM, Constant.AZURE_RESPONSE_TYPE)
                .queryParam(Constant.REDIRECT_URI_PARAM, redirectUri)
                .queryParam(Constant.RESPONSE_MODE_PARAM, Constant.AZURE_RESPONSE_MODE)
                .queryParam(Constant.SCOPE_PARAM, URLEncoder.encode("offline_access User.Read Mail.Read", StandardCharsets.UTF_8))
                .toUriString();
    }


//    @Transactional
//    public AzureUserCredentialDTO updateSubscriptionId1(Integer credId, String subscriptionId) {
//        AzureUserCredential updatedCredential = updateSubscriptionIdInCredential(subscriptionId,
//                azureUserCredentialRepository.findById(credId).orElseThrow(() -> new RuntimeException("No azure credentials found with provided id: " + credId)));
//        backendApplicationLogservice.saveAuditLog(updatedCredential.getWsTenantName(), "demo@gmail.com", "UPDATE", Constant.AZURE_SUBSCRIPTION_ID_UPDATED, "Info");
//        backendApplicationLogservice.saveAuditLog(updatedCredential.getWsTenantName(), "demo@gmail.com", "ADD", Constant.AZURE_RESOURCE_DATA_SYNC_START, "Info");
//        AzureUserCredentialDTO azureUserCredentialDTO = azureUserCredentialService.mapFromAzureUserCredentialAndDecryptSecretKey(updatedCredential);
//        azureSyncControlService.syncAzureResourcesData(azureUserCredentialDTO);
//        return azureUserCredentialDTO;
//    }

//    @Transactional
//    public AzureDataResponse updateSubscriptionId2(Integer credId, String subscriptionId) {
//        AzureDataResponse response = new AzureDataResponse();
//        AzureUserCredential updatedCredential = updateSubscriptionIdInCredential(subscriptionId,
//                azureUserCredentialRepository.findById(credId).orElseThrow(() -> new RuntimeException("No azure credentials found with provided id: " + credId)));
//        backendApplicationLogservice.saveAuditLog(updatedCredential.getWsTenantName(), "demo@gmail.com", "UPDATE", Constant.AZURE_SUBSCRIPTION_ID_UPDATED, "Info");
//        backendApplicationLogservice.saveAuditLog(updatedCredential.getWsTenantName(), "demo@gmail.com", "ADD", Constant.AZURE_RESOURCE_DATA_SYNC_START, "Info");
//        response.setData(updatedCredential);
//        if (updatedCredential.isSyncStatus()) {
//            response.setMessage("Subscription ID has been updated. Azure resources data sync has been paused as an earlier sync process is currently in progress");
//        } else {
//            updatedCredential.setSyncStatus(true);
//            azureUserCredentialRepository.save(updatedCredential);
//            response.setMessage("Subscription ID updated and Azure resources data sync started");
//            azureSyncControlService.syncAzureResourcesData(azureUserCredentialService.mapFromAzureUserCredentialAndDecryptSecretKey(updatedCredential));
//        }
//        return response;
//    }


    @Transactional
    public AzureDataResponse updateSubscriptionIds(Integer credId, String wsTenantName, Set<String> requestedSubIds) {
        log.info("Thread name for updateSubscriptionId: {}", Thread.currentThread().getName());
        AzureDataResponse response = new AzureDataResponse();
        boolean toStartSync = false;

        AzureUserCredential azureUserCredential = azureUserCredentialService.getAzureUserCredential(credId, wsTenantName);
        String decryptedClientSecret = EncryptionUtil.getDecryptedKey(azureUserCredential.getClientSecret(), Constant.AZURE_CLIENT_SECRET);
        validateRequestedSubscriptionIds(requestedSubIds, azureUserCredential.getTenantId(), azureUserCredential.getClientId(), decryptedClientSecret);

        Set<String> toBeDeletedSubscriptionIds = azureUserCredential.getSubscriptionIds();
        toBeDeletedSubscriptionIds.removeAll(requestedSubIds);
        log.info("Found subscriptions to delete: {}", toBeDeletedSubscriptionIds);
        if (CollectionUtils.isNotEmpty(toBeDeletedSubscriptionIds)) {
            deleteSubscriptions(toBeDeletedSubscriptionIds, wsTenantName);
            revokeAzureAndK8RolesAndRequests(wsTenantName, toBeDeletedSubscriptionIds);
        }
        log.info("Processing subscription updates for: {}", requestedSubIds);

        if (azureUserCredential.isSyncStatus()) {
            toStartSync = true;
            response.setMessage("Your subscription ID(s) have been successfully updated. New data sync skipped as one earlier sync process is currently in progress.");
        } else {
            azureUserCredential.setSyncStatus(true);
            response.setMessage("Subscription ID updated, and Azure resources data sync started.");
        }
        azureUserCredential.setSubscriptionIds(requestedSubIds);
        azureUserCredential.setUpdatedAt(new Date());
        azureUserCredentialRepository.saveAndFlush(azureUserCredential);

        AzureUserCredentialDTO credentialDTO = azureUserCredentialService.mapFromAzureUserCredentialAndDecryptSecretKey(azureUserCredential);
        if (!toStartSync) {
            azureSyncControlService.syncAzureResourcesData(credentialDTO);
        }

        response.setData(credentialDTO);
        return response;
    }

    private void deleteSubscriptions(Set<String> toBeDeletedSubscriptionIds, String wsTenantName) {
        List<Integer> subscriptionIds = toBeDeletedSubscriptionIds.stream()
                .map(subscriptionId -> azureResourceDataService.findAzureSubscriptionByIdAndWsTenantName(subscriptionId, wsTenantName).getId())
                .collect(Collectors.toList());
        azureResourceDataService.deleteBySubscriptionIdsAndWsTenantName(subscriptionIds, wsTenantName);
    }


    private void revokeAzureAndK8RolesAndRequests(String wsTenantName, Set<String> toBeDeletedSubscriptionIds) {
        log.info("Revoking roles and requests for deleted subscriptions: {}", toBeDeletedSubscriptionIds);
        customRoleAssignmentService.revokeApprovedRolesInAzureAndDeleteAllForWsTenant(wsTenantName, toBeDeletedSubscriptionIds);
        k8CustomResourceRequestService.revokeCustomRequestsByWsTenantNameAndSubscriptionIds(wsTenantName, CloudProviderType.AZURE, toBeDeletedSubscriptionIds);
        k8ResourcesDataService.deleteByWsTenantNameAndSubscriptionIds(wsTenantName, CloudProviderType.AZURE, toBeDeletedSubscriptionIds);
        publishedResourcesRepository.deleteAllByWsTenantName(wsTenantName, toBeDeletedSubscriptionIds);
    }


    private void validateRequestedSubscriptionIds(Set<String> requestedSubIds, String tenantId, String clientId, String clientSecret) {
        if (CollectionUtils.isNotEmpty(requestedSubIds)) {
            requestedSubIds.forEach(subscriptionId -> {
                log.info(String.format(Constant.VALIDATING_SUBSCRIPTION_ID, subscriptionId));
                azureAuthUtil.validateAzureAuthenticationCredentialsWithSubscriptionId(
                        tenantId,
                        clientId,
                        clientSecret,
                        subscriptionId
                );
            });
        }
    }


//    @Transactional
//    public AzureDataResponse updateSubscriptionIds(Integer credId, String wsTenantName, Set<String> requestedSubIds, boolean flag) {
//        log.info("Thread name for updateSubscriptionId: {}", Thread.currentThread().getName());
//        AzureDataResponse response = new AzureDataResponse();
//        boolean toStartSync = false;
//        AzureUserCredential azureUserCredential = azureUserCredentialRepository.findByIdAndWsTenantName(credId, wsTenantName).orElseThrow(() -> new RuntimeException("No azure credentials found with provided id: " + credId));
//        for (String subscriptionId : requestedSubIds) {
//            azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredential.getTenantId(), azureUserCredential.getClientId(),
//                    EncryptionUtil.getEncryptedKey(azureUserCredential.getClientSecret(), Constant.AZURE_CLIENT_SECRET), subscriptionId);
//        }
//
//        Set<String> toBeDeletedIds = azureUserCredential.getSubscriptionIds();
//        toBeDeletedIds.removeAll(requestedSubIds);
//        log.info("toBeDeletedIds: {}", toBeDeletedIds);
//
//        if (CollectionUtils.isNotEmpty(toBeDeletedIds)) {
//            List<Integer> subcriptionIds = toBeDeletedIds.stream()
//                    .map(subscriptionId -> azureResourceDataService.findAzureSubscriptionByIdAndWsTenantName(subscriptionId, wsTenantName).getId())
//                    .collect(Collectors.toList());
//
//            azureResourceDataService.deleteBySubscriptionIdsAndWsTenantName(subcriptionIds, wsTenantName);
//            if (flag) {
//                // Then delete the custom resource assignments for all such ids
//            }
//        }
//        log.info("finally to be saved ID(s): {}", requestedSubIds);
//        log.info("azureUserCredential sync status: {}", azureUserCredential.isSyncStatus());
//        if (azureUserCredential.isSyncStatus()) {
//            toStartSync = true;
//            response.setMessage("Your subscription ID(s) has been successfully updated. New data sync skipped as one earlier sync process is currently in progress");
//        } else {
//            azureUserCredential.setSyncStatus(true);
//            response.setMessage("Subscription ID updated and Azure resources data sync started");
//        }
//        azureUserCredential.setSubscriptionIds(requestedSubIds);
//        azureUserCredential.setUpdatedAt(new Date());
//        azureUserCredentialRepository.saveAndFlush(azureUserCredential);
//        AzureUserCredentialDTO credentialDTO = azureUserCredentialService.mapFromAzureUserCredentialAndDecryptSecretKey(azureUserCredential);
//        if (CollectionUtils.isNotEmpty(requestedSubIds) && !toStartSync) {
//            azureSyncControlService.syncAzureResourcesData(credentialDTO);
//        }
//        response.setData(credentialDTO);
//        return response;
//    }


//    @Transactional
//    public AzureDataResponse updateSubscriptionId(Integer credId, String subscriptionId) {
//        log.info("Thread name for updateSubscriptionId: {}", Thread.currentThread().getName());
//        AzureDataResponse response = new AzureDataResponse();
//        boolean flag = false;
//        AzureUserCredential azureUserCredential = azureUserCredentialRepository.findById(credId).orElseThrow(() -> new RuntimeException("No azure credentials found with provided id: " + credId));
//        log.info("azureUserCredential status: {}", azureUserCredential.isSyncStatus());
//        if (azureUserCredential.isSyncStatus()) {
//            flag = true;
//            response.setMessage("Subscription ID has been updated. Azure resources data sync has been paused as an earlier sync process is currently in progress");
//        } else {
//            azureUserCredential.setSyncStatus(true);
//            response.setMessage("Subscription ID updated and Azure resources data sync started");
//        }
//
//        azureUserCredential.setSubscriptionId(subscriptionId);
//        azureUserCredential.setUpdatedAt(new Date());
//        azureUserCredentialRepository.saveAndFlush(azureUserCredential);
//        if (!flag) {
//            azureSyncControlService.syncAzureResourcesData(azureUserCredentialService.mapFromAzureUserCredentialAndDecryptSecretKey(azureUserCredential));
//        }
//        response.setData(azureUserCredential);
//        return response;
//    }


    private AzureUserCredential updateSubscriptionIdInCredential(String subscriptionId, AzureUserCredential azureUserCredential) {
        azureUserCredential.setSubscriptionId(subscriptionId);
        azureUserCredential.setUpdatedAt(new Date());
        return azureUserCredentialRepository.save(azureUserCredential);
    }


    private AzureUserCredential getAzureUserCredentialForWSTenant(String wsTenantName) {
        return azureUserCredentialRepository.findByWsTenantName(wsTenantName).orElse(null);
    }

    private AzureUser getAzureUserUsingEmail(String email) {
        return azureUserRepository.findByUserPrincipalName(email)
                .map(azureUser -> {
                    if (azureUser.getIsSSOEnabled() == null || !azureUser.getIsSSOEnabled()) {
                        throw new RuntimeException("Azure user not SSO enabled");
                    }
                    return azureUser;
                })
                .orElseThrow(() -> new RuntimeException(String.format("No Azure User found with provided email: %s", email)));
    }
}
