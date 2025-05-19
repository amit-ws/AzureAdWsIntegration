package com.ws.azureKuberntesJIT.service;


import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureAdIntegration.exception.AzureDataException;
import com.ws.azureAdIntegration.repository.AzureUserRepository;
import com.ws.azureAdIntegration.service.AzureUserCredentialService;
import com.ws.azureAdIntegration.util.GenericUtil;
import com.ws.azureKuberntesJIT.models.K8CustomResourceRequestDTO;
import com.ws.azureResourcesIntegration.constant.PublishResourceType;
import com.ws.azureAdIntegration.exception.K8ResourceException;
import com.ws.azureAdIntegration.service.AzureSyncControlService;
import com.ws.azureAdIntegration.util.EncryptionUtil;
import com.ws.azureKuberntesJIT.constant.*;
import com.ws.azureKuberntesJIT.dto.ClusterConfigurationRequest;
import com.ws.azureKuberntesJIT.dto.K8ResourceDataSyncRequest;
import com.ws.azureKuberntesJIT.dto.K8RolePolicyRuleDTO;
import com.ws.azureKuberntesJIT.enttity.*;
import com.ws.azureKuberntesJIT.models.K8ResourceRaiseRequest;
import com.ws.azureKuberntesJIT.repository.K8IngressRepository;
import com.ws.azureKuberntesJIT.repository.*;
import com.ws.azureKuberntesJIT.response.K8RoleResponse;
import com.ws.azureKuberntesJIT.response.RoleResponse;
import com.ws.azureKuberntesJIT.response.RoleResponseProjection;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import com.ws.azureResourcesIntegration.constant.StateChangeConstants;
import com.ws.azureResourcesIntegration.entities.AzureKubernetesCluster;
import com.ws.azureResourcesIntegration.repository.AzureUserConfigureRepository;
import com.ws.azureResourcesIntegration.repository.PublishedResourcesRepository;
import com.ws.azureResourcesIntegration.service.AzureResourceDataService;
import com.ws.azureResourcesIntegration.service.CustomRoleAssignmentService;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class K8ResourceService {
    Map<String, RbacAuthorizationV1Api> k8RbacApiClientMap = new HashMap<>();
    final K8StorageClassRepository k8StorageClassRepository;
    final K8PersistentVolumeRepository k8PersistentVolumeRepository;
    final K8NamespaceRepository k8NamespaceRepository;
    final K8NodeRepository k8NodeRepository;
    final K8DeploymentRepository k8DeploymentRepository;
    final K8ServiceAccountRepository k8ServiceAccountRepository;
    final K8SecretRepository k8SecretRepository;
    final K8ConfigMapRepository k8ConfigMapRepository;
    final K8CustomResourceDefinitionRepository k8CustomResourceDefinitionRepository;
    final K8NetworkPolicyRepository k8NetworkPolicyRepository;
    final K8RoleRepository k8RoleRepository;
    final K8PolicyRuleRepository k8PolicyRuleRepository;
    final K8ReplicaSetRepository k8ReplicaSetRepository;
    final K8StatefulSetRepository k8StatefulSetRepository;
    final K8DaemonSetRepository k8DaemonSetRepository;
    final K8JobRepository k8JobRepository;
    final K8CronJobRepository k8CronJobRepository;
    final K8IngressRepository k8IngressRepository;
    final K8ServiceRepository k8ServiceRepository;
    final PublishedResourcesRepository publishedResourcesRepository;
    final K8CustomResourceRequestRepository k8CustomResourceRequestRepository;
    final K8RoleBindRepository k8RoleBindRepository;
    final AzureUserConfigureRepository azureUserConfigureRepository;
    final AzureUserRepository azureUserRepository;
    final AzureSyncControlService azureSyncControlService;
    final K8ClientService k8ClientService;
    final AzureResourceDataService azureResourceDataService;
    final CustomRoleAssignmentService customRoleAssignmentService;
    final AzureUserCredentialService azureUserCredentialService;
    RbacAuthorizationV1Api rbacApi;


    @Autowired
    public K8ResourceService(K8StorageClassRepository k8StorageClassRepository, K8PersistentVolumeRepository k8PersistentVolumeRepository, K8NamespaceRepository k8NamespaceRepository, K8NodeRepository k8NodeRepository,
                             K8DeploymentRepository k8DeploymentRepository, K8ServiceAccountRepository k8ServiceAccountRepository,
                             K8SecretRepository k8SecretRepository, K8ConfigMapRepository k8ConfigMapRepository,
                             K8CustomResourceDefinitionRepository k8CustomResourceDefinitionRepository, K8NetworkPolicyRepository k8NetworkPolicyRepository,
                             K8RoleRepository k8RoleRepository, K8PolicyRuleRepository k8PolicyRuleRepository, K8ReplicaSetRepository k8ReplicaSetRepository,
                             K8StatefulSetRepository k8StatefulSetRepository, K8DaemonSetRepository k8DaemonSetRepository, K8JobRepository k8JobRepository,
                             K8CronJobRepository k8CronJobRepository, K8IngressRepository k8IngressRepository, K8ServiceRepository k8ServiceRepository,
                             PublishedResourcesRepository publishedResourcesRepository, K8CustomResourceRequestRepository k8CustomResourceRequestRepository,
                             K8RoleBindRepository k8RoleBindRepository, AzureUserConfigureRepository azureUserConfigureRepository, AzureUserRepository azureUserRepository, AzureSyncControlService azureSyncControlService, K8ClientService k8ClientService, AzureResourceDataService azureResourceDataService, CustomRoleAssignmentService customRoleAssignmentService, AzureUserCredentialService azureUserCredentialService) {
        this.k8StorageClassRepository = k8StorageClassRepository;
        this.k8PersistentVolumeRepository = k8PersistentVolumeRepository;
        this.k8NamespaceRepository = k8NamespaceRepository;
        this.k8NodeRepository = k8NodeRepository;
        this.k8DeploymentRepository = k8DeploymentRepository;
        this.k8ServiceAccountRepository = k8ServiceAccountRepository;
        this.k8SecretRepository = k8SecretRepository;
        this.k8ConfigMapRepository = k8ConfigMapRepository;
        this.k8CustomResourceDefinitionRepository = k8CustomResourceDefinitionRepository;
        this.k8NetworkPolicyRepository = k8NetworkPolicyRepository;
        this.k8RoleRepository = k8RoleRepository;
        this.k8PolicyRuleRepository = k8PolicyRuleRepository;
        this.k8ReplicaSetRepository = k8ReplicaSetRepository;
        this.k8StatefulSetRepository = k8StatefulSetRepository;
        this.k8DaemonSetRepository = k8DaemonSetRepository;
        this.k8JobRepository = k8JobRepository;
        this.k8CronJobRepository = k8CronJobRepository;
        this.k8IngressRepository = k8IngressRepository;
        this.k8ServiceRepository = k8ServiceRepository;
        this.publishedResourcesRepository = publishedResourcesRepository;
        this.k8CustomResourceRequestRepository = k8CustomResourceRequestRepository;
        this.k8RoleBindRepository = k8RoleBindRepository;
        this.azureUserConfigureRepository = azureUserConfigureRepository;
        this.azureUserRepository = azureUserRepository;
        this.azureSyncControlService = azureSyncControlService;
        this.k8ClientService = k8ClientService;
        this.azureResourceDataService = azureResourceDataService;
        this.customRoleAssignmentService = customRoleAssignmentService;
        this.azureUserCredentialService = azureUserCredentialService;
    }


    @Transactional(readOnly = true)
    public List<?> getK8Resources(com.ws.azureKuberntesJIT.dto.K8ResourceRequest request, K8ResourceLevel resourceLevel) {
        String wsTenantName = request.getWsTenantName().trim();
        String clusterId = request.getClusterId().trim();
        CloudProviderType cloudProviderType = request.getCloudProviderType();
        K8ResourceType type = request.getType();
        if (ObjectUtils.isEmpty(type)) {
            throw new K8ResourceException("Kubernetes resource type is required");
        }
        return switch (resourceLevel) {
            case CLUSTER -> getClusterLevelK8Resources(wsTenantName, clusterId, cloudProviderType, type);
            case NAMESPACE -> {
                if (StringUtils.isEmpty(request.getNamespace())) {
                    throw new K8ResourceException(String.format("Namespace name required to fetch %s typed resource", type));
                }
                yield getNamespaceLevelK8Resources(wsTenantName, clusterId, cloudProviderType, type, request.getNamespace().trim());
            }
            default ->
                    throw new K8ResourceException(String.format("Invalid type. Supported types: %s and %s", K8ResourceLevel.CLUSTER, K8ResourceLevel.NAMESPACE));
        };
    }

    private List<?> getClusterLevelK8Resources(String wsTenantName, String clusterId, CloudProviderType cloudProviderType, K8ResourceType type) {
        return switch (type) {
            case NAMESPACE ->
                    k8NamespaceRepository.findAllByWsTenantNameAndCloudProviderTypeAndClusterIdOrderByName(wsTenantName, cloudProviderType, clusterId);
            case CUSTOM_RESOURCE_DEFINITION ->
                    k8CustomResourceDefinitionRepository.findAllByWsTenantNameAndCloudProviderTypeAndClusterIdOrderByName(wsTenantName, cloudProviderType, clusterId);
            case NODE ->
                    k8NodeRepository.findAllByWsTenantNameAndCloudProviderTypeAndClusterIdOrderByName(wsTenantName, cloudProviderType, clusterId);
            case STORAGE_CLASS ->
                    k8StorageClassRepository.findAllByWsTenantNameAndCloudProviderTypeAndClusterIdOrderByName(wsTenantName, cloudProviderType, clusterId);
            case PERSISTENT_VOLUME ->
                    k8PersistentVolumeRepository.findAllByWsTenantNameAndCloudProviderTypeAndClusterIdOrderByName(wsTenantName, cloudProviderType, clusterId);
            default ->
                    throw new RuntimeException("Invalid cluster level kubernetes resource type provided. Type: " + type);
        };
    }

    private List<?> getNamespaceLevelK8Resources(String wsTenantName, String clusterId, CloudProviderType cloudProviderType, K8ResourceType type, String namespace) {
        return switch (type) {
            case DEPLOYMENT ->
                    k8DeploymentRepository.findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(wsTenantName, cloudProviderType, clusterId, namespace);
            case SERVICE_ACCOUNT ->
                    k8ServiceAccountRepository.findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(wsTenantName, cloudProviderType, clusterId, namespace);
            case SECRET ->
                    k8SecretRepository.findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(wsTenantName, cloudProviderType, clusterId, namespace);
            case CONFIG_MAP ->
                    k8ConfigMapRepository.findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(wsTenantName, cloudProviderType, clusterId, namespace);
            case NETWORK_POLICY ->
                    k8NetworkPolicyRepository.findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(wsTenantName, cloudProviderType, clusterId, namespace);
            case JOB ->
                    k8JobRepository.findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(wsTenantName, cloudProviderType, clusterId, namespace);
            case CRON_JOB ->
                    k8CronJobRepository.findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(wsTenantName, cloudProviderType, clusterId, namespace);
            case REPLICA_SET ->
                    k8ReplicaSetRepository.findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(wsTenantName, cloudProviderType, clusterId, namespace);
            case STATEFUL_SET ->
                    k8StatefulSetRepository.findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(wsTenantName, cloudProviderType, clusterId, namespace);
            case DAEMON_SET ->
                    k8DaemonSetRepository.findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(wsTenantName, cloudProviderType, clusterId, namespace);
            case SERVICE ->
                    k8ServiceRepository.findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(wsTenantName, cloudProviderType, clusterId, namespace);
            case INGRESS ->
                    k8IngressRepository.findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(wsTenantName, cloudProviderType, clusterId, namespace);
            default -> throw new RuntimeException("Invalid kubernetes resource type provided. Type: " + type);
        };
    }


    public List<Map<String, String>> getK8ResourcesTypes(K8ResourceLevel level) {
        return switch (level) {
            case CLUSTER -> getAllClusterLevelResourcesTypes();
            case NAMESPACE -> getAllNamespaceLevelResourcesTypes();
            default -> throw new K8ResourceException("Unsupported kubernetes resource level provided");
        };
    }


    private List<Map<String, String>> getAllClusterLevelResourcesTypes() {
        return Arrays.stream(K8ResourceType.values())
                .filter(type -> type == K8ResourceType.NAMESPACE ||
                        type == K8ResourceType.CUSTOM_RESOURCE_DEFINITION ||
                        type == K8ResourceType.NODE ||
                        type == K8ResourceType.STORAGE_CLASS ||
                        type == K8ResourceType.PERSISTENT_VOLUME)
                .map(type -> Map.of("key", type.name(), "value", type.getDisplayName()))
                .collect(Collectors.toList());
    }


    private List<Map<String, String>> getAllNamespaceLevelResourcesTypes() {
        return Arrays.stream(K8ResourceType.values())
                .filter(type -> type == K8ResourceType.DEPLOYMENT ||
                        type == K8ResourceType.SERVICE_ACCOUNT ||
                        type == K8ResourceType.SECRET ||
                        type == K8ResourceType.CONFIG_MAP ||
                        type == K8ResourceType.NETWORK_POLICY ||
                        type == K8ResourceType.JOB ||
                        type == K8ResourceType.CRON_JOB ||
                        type == K8ResourceType.REPLICA_SET ||
                        type == K8ResourceType.STATEFUL_SET ||
                        type == K8ResourceType.DAEMON_SET ||
                        type == K8ResourceType.SERVICE ||
                        type == K8ResourceType.INGRESS)
                .map(type -> Map.of("key", type.name(), "value", type.getDisplayName()))
                .collect(Collectors.toList());
    }


    public List<K8RoleResponse> getK8Roles(String wsTenantName, String clusterId, CloudProviderType cloudProviderType, K8ResourceLevel k8RoleType) {
        if (k8RoleType.equals(K8ResourceLevel.ALL)) {
            k8RoleType = null;
        }
        return k8RoleRepository.findAllRolesUsingWsTenantNameAndCloudTypeAndRoleTypeAndClusterId(wsTenantName, clusterId, cloudProviderType, k8RoleType);
    }


    @Transactional(readOnly = true)
    public List<K8RolePolicyRuleDTO> getK8RolePoliciesByRoleUID(String roleUID, String wsTenantName, CloudProviderType cloudType) {
        List<K8RolePolicyRule> policyRules = k8PolicyRuleRepository.findByRoleUIDAndWsTenantNameAndCloudProviderType(roleUID, wsTenantName, cloudType);
        if (CollectionUtils.isEmpty(policyRules)) {
            throw new K8ResourceException("No data found!");
        }
        return policyRules.stream()
                .map(rule -> K8RolePolicyRuleDTO.builder()
                        .id(rule.getId())
                        .roleUID(rule.getRoleUID())
                        .verbs(rule.getVerbs())
                        .apiGroups(rule.getApiGroups())
                        .resources(rule.getResources())
                        .resourceNames(rule.getResourceNames())
                        .nonResourceURLs(rule.getNonResourceURLs())
                        .build())
                .collect(Collectors.toList());
    }


    public List<RoleResponse> suggestRoles(com.ws.azureKuberntesJIT.dto.K8ResourceRequest request, String resourceName) {
        String namespace = StringUtils.isNotEmpty(request.getNamespace()) ? request.getNamespace().trim() : null;
        String publishResourceType = StringUtils.isEmpty(namespace) ? PublishResourceType.CLUSTER_ROLE.name() : PublishResourceType.NAMESPACE_ROLE.name();
        if (StringUtils.isEmpty(request.getResourceAccountId())) {
            throw new K8ResourceException("Cloud resource account ID is required");
        }
        List<RoleResponseProjection> projections = k8RoleRepository.suggestRoles(request.getWsTenantName(), request.getCloudProviderType().name(), request.getResourceAccountId(), request.getClusterId(),
                namespace, resourceName, publishResourceType);
        if (projections.isEmpty()) {
            return Collections.emptyList();
        }
        return projections.stream().map((projection -> RoleResponse.builder()
                        .roleUid(projection.getRoleUid())
                        .roleName(projection.getRoleName())
                        .roleKind(projection.getRoleKind())
                        .roleLevel(projection.getRoleLevel())
                        .build()))
                .collect(Collectors.toList());
    }


    public List<String> getSupportedRoleVerbs() {
        return K8Verb.getAllVerb();
    }


    @Transactional
    public Boolean raiseResourceRequest(K8ResourceRaiseRequest request) {
        boolean isRoleCustomCreated = StringUtils.isEmpty(request.getRoleId());
        String roleId = null;
        String roleName;
        K8RoleKind roleKind;
        String namespace = StringUtils.isNotEmpty(request.getNamespace()) ? request.getNamespace().trim() : null;
        List<String> verbs = null;

        if (isRoleCustomCreated) {
            roleKind = StringUtils.isEmpty(namespace) ? K8RoleKind.ClusterRole : K8RoleKind.Role;
            if (CollectionUtils.isEmpty(request.getVerbs())) {
                throw new K8ResourceException(String.format("Please provide verb(s) for %s creation", roleKind));
            }
            roleName = UUID.randomUUID().toString();
            verbs = request.getVerbs();
        } else {
            roleId = request.getRoleId().trim();
            roleName = request.getRoleName().trim();
            roleKind = K8RoleKind.valueOf(request.getRoleKind().trim());

            // Check if already a role binding is present in PENDING or APPROVED statuses
            checkExistingRoleBinding(request, namespace, roleId);
        }

        // Proceed to create and save custom resource request
        createAndSaveK8CustomResourceRequest(request, roleId, roleName, roleKind, isRoleCustomCreated, namespace, verbs);
        return Boolean.TRUE;
    }

    private void checkExistingRoleBinding(K8ResourceRaiseRequest request, String namespace, String roleId) {
        String[] statuses = {RequestStatus.DECLINE.name(), RequestStatus.EXPIRED.name()};
        Optional<K8CustomResourceRequest> customResourceRequestOpt = k8CustomResourceRequestRepository.findCustomRequestWithParamsAndStatusNotIn(
                request.getWsTenantName().trim(), request.getCloudType().name(),
                request.getCloudResourceAccountId().trim(), request.getClusterId().trim(),
                namespace, roleId, request.getUserName(), statuses);
        customResourceRequestOpt.ifPresent(this::handleExistingRoleBindingRequest);
    }


    private void createAndSaveK8CustomResourceRequest(K8ResourceRaiseRequest request, String roleId, String roleName, K8RoleKind roleKind,
                                                      boolean isRoleCustomCreated, String namespace, List<String> verbs) {
        K8CustomResourceRequest resourceRequest = K8CustomResourceRequest.builder()
                .id(UUID.randomUUID())
                .wsTenantName(request.getWsTenantName().trim())
                .cloudType(request.getCloudType())
                .cloudResourceAccountId(request.getCloudResourceAccountId().trim())
                .clusterId(request.getClusterId().trim())
                .clusterName(GenericUtil.getOrNull(() -> request.getClusterName().trim()))
                .status(RequestStatus.PENDING)
                .wsUserEmail(request.getWsUserEmail().trim())
                .expiryTimeAmount(request.getExpiryTimeAmount())
                .message(request.getMessage())
                .build();

        // Setting the role related details
        K8RoleRequest roleRequest = K8RoleRequest.builder()
                .roleId(roleId)
                .roleName(roleName)
                .roleKind(roleKind)
                .verbs(verbs)
                .policyApiGroup(request.getK8ResourceType().getApiGroup())
                .policyResource(request.getK8ResourceType().getResourceName())
                .policyResourceName(request.getResourcesName().trim())
                .isRoleCustomCreated(isRoleCustomCreated)
                .build();
        resourceRequest.setRoleRequest(roleRequest);

        // Setting binding related details
        K8RoleBindRequest bindRequest = K8RoleBindRequest.builder()
                .k8ResourceId(request.getK8ResourceId().trim())
                .k8ResourceName(request.getK8ResourceName().trim())
                .resourceType(request.getK8ResourceType().name())
                .roleBindingName(UUID.randomUUID().toString())
                .userName(request.getUserName().trim())
                .userDisplayName(GenericUtil.getOrNull(() -> request.getUser().trim()))
                .subjectKind(K8SubjectKind.USER)
                .bindingType((StringUtils.isEmpty(namespace)) ? K8RoleBindingType.ClusterRoleBinding : K8RoleBindingType.RoleBinding)
                .level((StringUtils.isEmpty(namespace)) ? K8ResourceLevel.CLUSTER : K8ResourceLevel.NAMESPACE)
                .namespace(namespace)
                .build();
        resourceRequest.setRoleBindRequest(bindRequest);

        k8CustomResourceRequestRepository.save(resourceRequest);
    }

    private void handleExistingRoleBindingRequest(K8CustomResourceRequest roleBinding) {
        RequestStatus status = roleBinding.getStatus();
        switch (status) {
            case PENDING, APPROVED ->
                    throw new IllegalArgumentException(String.format("Your request is already in %s state", status));
        }
    }


    public List<K8CustomResourceRequestDTO> getK8CustomResourceRequests(String wsTenantName, CloudProviderType cloudType, RequestStatus requestStatus,
                                                                        String wsTenantUserEmail, String cloudResourceAccountId) {
        String azureUserUPN = null;
        if (StringUtils.isNotEmpty(wsTenantUserEmail)) {
            azureUserUPN = azureUserConfigureRepository.findByEmailAndWsTenantName(wsTenantUserEmail, wsTenantName)
                    .orElseThrow(() -> new RuntimeException("No data found for provided email: " + wsTenantUserEmail))
                    .getAzureUserUpn();
        }

        return switch (cloudType) {
            case AZURE ->
                    k8CustomResourceRequestRepository.findK8CustomResourceRequestWithParams(wsTenantName, cloudType, requestStatus, azureUserUPN, cloudResourceAccountId);
            default -> throw new K8ResourceException("Unsupported cloud type provided. Type: {}" + cloudType);
        };
    }


    @Transactional
    public Boolean processResourceRequest(String requestUUID, RequestStatus updatedStatus) {
        K8CustomResourceRequest foundRequest = k8CustomResourceRequestRepository.findById(UUID.fromString(requestUUID))
                .orElseThrow(() -> new K8ResourceException("No resource request found with provided ID: " + requestUUID));
        if (azureUserCredentialService.checkIfSyncInProcess(foundRequest.getWsTenantName())) {
            throw new AzureDataException("The process cannot be completed as a data sync is in progress. Please try again once it’s finished.");
        }
        RequestStatus currentStatus = foundRequest.getStatus();
//        checkAzureRoleAssignmentForUser(foundRequest);
        processRequest(foundRequest, updatedStatus, currentStatus);
        return Boolean.TRUE;
    }


    private void checkAzureRoleAssignmentForUser(K8CustomResourceRequest foundRequest) {
        String wsTenantName = foundRequest.getWsTenantName();
        String userName = foundRequest.getRoleBindRequest().getUserName();
        if (!foundRequest.getCloudType().equals(CloudProviderType.AZURE)) {
            throw new K8ResourceException("Unsupported cloud type provided. Type: " + foundRequest.getCloudType());
        }

        String assignee = findAzureUserIdByUPN(userName, wsTenantName).getAzureId();
        boolean flag = customRoleAssignmentService.checkIfCustomRoleAssignmentExistsForAssignee(
                wsTenantName,
                foundRequest.getCloudResourceAccountId(),
                foundRequest.getClusterId(),
                assignee
        );
        if (!flag) {
            throw new AzureDataException(String.format("No Azure RBAC exists on the assignee %s for scope %s ", userName, foundRequest.getClusterName()));
        }
    }

    private AzureUser findAzureUserIdByUPN(String upn, String wsTenantName) {
        return azureUserRepository.findByUserPrincipalNameAndWsTenantName(upn, wsTenantName)
                .orElseThrow(() -> new AzureDataException("No azure user found with provided upn: " + upn));
    }


    private void processRequest(K8CustomResourceRequest foundRequest, RequestStatus updatedStatus, RequestStatus currentStatus) {
        // Validate the state transition
        if (!StateChangeConstants.VALID_STATE_TRANSITIONS.getOrDefault(currentStatus, Set.of()).contains(updatedStatus)) {
            throw new IllegalStateException(String.format("Invalid state transition from %s to %s for resource name: %s for the ws tenant user: %s",
                    currentStatus, updatedStatus, foundRequest.getRoleBindRequest().getK8ResourceName(), foundRequest.getWsUserEmail()));
        }

        switch (updatedStatus) {
            case APPROVED -> processApproval(foundRequest);
            case DECLINE -> processDenial(foundRequest);
        }
    }

    private void processApproval(K8CustomResourceRequest customResourceRequest) {
        // 1. Initialize the K8 client using cluster ID and CloudType
        Triple<String, String, String> clusterDetails = initializeK8ClientsWithClusterDetails(customResourceRequest.getClusterId(), customResourceRequest.getCloudType());
        createRoleBindingAndRole(customResourceRequest);
        LocalDateTime validFrom = LocalDateTime.now();
        customResourceRequest.setValidFrom(validFrom);
        customResourceRequest.setValidTo(validFrom.plusMinutes(customResourceRequest.getExpiryTimeAmount()));
        updateCustomRoleAssignmentCommonFields(customResourceRequest, RequestStatus.APPROVED);

        // 2. Sync the roles and role bindings asynchronously
        azureSyncControlService.syncK8RolesAndBindings(createK8ResourceDataSyncRequest(customResourceRequest, clusterDetails));
    }

    private void createRoleBindingAndRole(K8CustomResourceRequest customResourceRequest) {
        String namespace = customResourceRequest.getRoleBindRequest().getNamespace();
        String roleName = customResourceRequest.getRoleRequest().getRoleName();
        List<String> verbs = customResourceRequest.getRoleRequest().getVerbs();
        String resource = customResourceRequest.getRoleRequest().getPolicyResource();
        String apiGroup = customResourceRequest.getRoleRequest().getPolicyApiGroup();
        String resourceName = customResourceRequest.getRoleRequest().getPolicyResourceName();
        String roleBindingName = customResourceRequest.getRoleBindRequest().getRoleBindingName();
        String userName = customResourceRequest.getRoleBindRequest().getUserName();

        if (StringUtils.isEmpty(namespace)) {
            if (customResourceRequest.getRoleRequest().isRoleCustomCreated()) {
                V1ClusterRole createdClusterRole = k8ClientService.createClusterRole(roleName, resourceName, verbs, apiGroup, resource);
                customResourceRequest.getRoleRequest().setRoleId(createdClusterRole.getMetadata().getUid());
            }
            V1ClusterRoleBinding createdClusterRoleBinding = k8ClientService.createClusterRoleBinding(roleName, roleBindingName, userName, K8ResourceType.CLUSTER_ROLE.getApiGroup(), K8ResourceType.CLUSTER_ROLE.getApiVersion());
            customResourceRequest.getRoleBindRequest().setRoleBindingId(createdClusterRoleBinding.getMetadata().getUid());
        } else {
            if (customResourceRequest.getRoleRequest().isRoleCustomCreated()) {
                V1Role createdNamespaceRole = k8ClientService.createNamespaceRole(namespace, roleName, resourceName, verbs, apiGroup, resource);
                customResourceRequest.getRoleRequest().setRoleId(createdNamespaceRole.getMetadata().getUid());
            }
            V1RoleBinding createdNamespaceRoleBinding = k8ClientService.createNamespaceRoleBinding(namespace, roleBindingName, roleName, userName, K8ResourceType.NAMESPACE_ROLE.getApiGroup(), K8ResourceType.NAMESPACE_ROLE.getApiVersion());
            customResourceRequest.getRoleBindRequest().setRoleBindingId(createdNamespaceRoleBinding.getMetadata().getUid());
        }
    }


    private void processDenial(K8CustomResourceRequest customResourceRequest) {
        updateCustomRoleAssignmentCommonFields(customResourceRequest, RequestStatus.DECLINE);
    }


    private void processExpiration(K8CustomResourceRequest customResourceRequest) {
        // 1. Initialize the K8 clients
        initializeK8ClientsWithClusterDetails(customResourceRequest.getClusterId(), customResourceRequest.getCloudType());

        // 2. Revoke Role and binding in the k8 and delete in the table
        revokeRoleBindingAndRole(customResourceRequest.getRoleBindRequest().getNamespace(),
                customResourceRequest.getRoleBindRequest().getRoleBindingName(),
                customResourceRequest.getRoleRequest().getRoleId(),
                customResourceRequest.getRoleRequest().getRoleName(),
                customResourceRequest.getRoleRequest().isRoleCustomCreated());

        // 3. Delete the role binding from the table
        k8RoleBindRepository.deleteByUid(customResourceRequest.getRoleBindRequest().getRoleBindingId());
        updateCustomRoleAssignmentCommonFields(customResourceRequest, RequestStatus.EXPIRED);
    }


    private void revokeRoleBindingAndRole(String namespace, String roleBindingName, String roleId, String roleName, boolean isRoleCustomCreated) {
        if (StringUtils.isEmpty(namespace)) {
            k8ClientService.revokeClusterRoleBinding(roleBindingName);
            if (isRoleCustomCreated) {
                k8ClientService.revokeClusterRole(roleName);
                k8RoleRepository.deleteByUid(roleId);
            }
        } else {
            k8ClientService.revokeNamespaceRoleBinding(namespace, roleBindingName);
            if (isRoleCustomCreated) {
                k8ClientService.revokeNamespaceRole(namespace, roleName);
                k8RoleRepository.deleteByUid(roleId);
            }
        }
    }


    private Triple<String, String, String> initializeK8ClientsWithClusterDetails(String clusterId, CloudProviderType cloudType) {
        Triple<String, String, String> clusterDetails = findClusterUrlAndToken(clusterId, cloudType);
        k8ClientService.initializeK8Client(clusterDetails.getLeft(), clusterDetails.getRight());
        k8ClientService.initializeK8RbacClient();
        return clusterDetails;
    }

    private RbacAuthorizationV1Api initializeK8RbacApiClientByClusterCredential(String clusterId, CloudProviderType cloudType) {
        Triple<String, String, String> clusterDetails = findClusterUrlAndToken(clusterId, cloudType);
        return k8ClientService.initializeK8ClientWithRbacApi(clusterDetails.getLeft(), clusterDetails.getRight());
    }


    private K8ResourceDataSyncRequest createK8ResourceDataSyncRequest(K8CustomResourceRequest customResourceRequest, Triple<String, String, String> triple) {
        return K8ResourceDataSyncRequest.builder()
                .wsTenantName(customResourceRequest.getWsTenantName())
                .resourceAccountId(customResourceRequest.getCloudResourceAccountId())
                .cloudProviderType(customResourceRequest.getCloudType())
                .configurations(Collections.singletonList(ClusterConfigurationRequest.builder()
                        .server(triple.getLeft())
                        .token(triple.getRight())
                        .clusterId(customResourceRequest.getClusterId())
                        .clusterName(triple.getMiddle())
                        .build()))
                .build();
    }


    private void updateCustomRoleAssignmentCommonFields(K8CustomResourceRequest customRoleAssignment, RequestStatus status) {
        customRoleAssignment.setStatus(status);
        customRoleAssignment.setUpdatedAt(new Date());
        k8CustomResourceRequestRepository.save(customRoleAssignment);
    }

    private Triple<String, String, String> findClusterUrlAndToken(String k8ClusterId, CloudProviderType cloudType) {
        if (cloudType.name().equalsIgnoreCase(CloudProviderType.AZURE.name())) {
            AzureKubernetesCluster azureKubernetesCluster = azureResourceDataService.findAksByAzureId(k8ClusterId);
            String severURL = EncryptionUtil.getDecryptedKey(azureKubernetesCluster.getAzureK8ClusterCredentials().get(0).getClusterServerUrl(), Constant.AKS_CLUSTER_SERVER_URL);
            String token = EncryptionUtil.getDecryptedKey(azureKubernetesCluster.getAzureK8ClusterCredentials().get(0).getToken(), Constant.AKS_CLUSTER_TOKEN);
            return Triple.of(severURL, azureKubernetesCluster.getName(), token);
        } else {
            throw new K8ResourceException("Unsupported cloud type provided: " + cloudType);
        }
    }


    @Transactional
    public void revokeK8ResourceAccess(K8CustomResourceRequest customResourceRequest) {
        processExpiration(customResourceRequest);
    }

    public void revokeK8ResourceAccess(List<K8CustomResourceRequestDTO> customResourceRequests) {
        for (K8CustomResourceRequestDTO customResourceRequest : customResourceRequests) {
            RbacAuthorizationV1Api rbacAuthorizationV1Api = getOrInitializeK8Client(customResourceRequest.getClusterId(), customResourceRequest.getCloudType());
            k8ClientService.initializeK8RbacClient(rbacAuthorizationV1Api);
            revokeRoleBindingAndRole(customResourceRequest.getNamespace(),
                    customResourceRequest.getRoleBindingName(),
                    customResourceRequest.getRoleId(),
                    customResourceRequest.getRoleName(),
                    customResourceRequest.isRoleCustomCreated());
        }
    }

    private RbacAuthorizationV1Api getOrInitializeK8Client(String clusterId, CloudProviderType cloudType) {
        if (!k8RbacApiClientMap.containsKey(clusterId)) {
            k8RbacApiClientMap.put(clusterId, initializeK8RbacApiClientByClusterCredential(clusterId, cloudType));
        }
        return k8RbacApiClientMap.get(clusterId);
    }


    //-------------------------------------------------------------//    //-------------------------------------------------------------//
    //-------------------------------------------------------------//    //-------------------------------------------------------------//


    public List<?> getNamespaceLevelK8Resources(String clusterId, String wsTenantName, K8ResourceType type) {
        return switch (type) {
            case DEPLOYMENT -> k8DeploymentRepository.findAllByClusterIdAndWsTenantName(clusterId, wsTenantName);
            case NODE -> k8NodeRepository.findAllByClusterIdAndWsTenantName(clusterId, wsTenantName);
            case SERVICE_ACCOUNT ->
                    k8ServiceAccountRepository.findAllByClusterIdAndWsTenantName(clusterId, wsTenantName);
            case SECRET -> k8SecretRepository.findAllByClusterIdAndWsTenantName(clusterId, wsTenantName);
            case CONFIG_MAP -> k8ConfigMapRepository.findAllByClusterIdAndWsTenantName(clusterId, wsTenantName);
            case NETWORK_POLICY -> k8NetworkPolicyRepository.findAllByClusterIdAndWsTenantName(clusterId, wsTenantName);
            case JOB -> k8JobRepository.findAllByClusterIdAndWsTenantName(clusterId, wsTenantName);
            case CRON_JOB -> k8CronJobRepository.findAllByClusterIdAndWsTenantName(clusterId, wsTenantName);
            case REPLICA_SET -> k8ReplicaSetRepository.findAllByClusterIdAndWsTenantName(clusterId, wsTenantName);
            case STATEFUL_SET -> k8StatefulSetRepository.findAllByClusterIdAndWsTenantName(clusterId, wsTenantName);
            case DAEMON_SET -> k8DaemonSetRepository.findAllByClusterIdAndWsTenantName(clusterId, wsTenantName);
            case SERVICE -> k8ServiceRepository.findAllByClusterIdAndWsTenantName(clusterId, wsTenantName);
            case INGRESS -> k8IngressRepository.findAllByClusterIdAndWsTenantName(clusterId, wsTenantName);
            default -> throw new RuntimeException("Invalid kubernetes resource type provided. Type: " + type);
        };
    }


    public List<K8RoleResponse> getK8Roles(String wsTenantName, CloudProviderType cloudProviderType, K8ResourceLevel k8RoleType) {
        if (k8RoleType.equals(K8ResourceLevel.ALL)) {
            k8RoleType = null;
        }
        return k8RoleRepository.findAllRolesUsingWsTenantNameAndCloudTypeAndRoleType(wsTenantName, cloudProviderType, k8RoleType);
    }


    public List<RoleResponse> findApplicableRoles(String wsTenantName, String resourceType, String resourceId, String clusterId, CloudProviderType cloudProviderType) {
        List<RoleResponseProjection> projections = k8RoleRepository.findApplicableRoles(wsTenantName, resourceType, resourceId, clusterId, cloudProviderType.name());
        if (projections.isEmpty()) {
            return Collections.emptyList();
        }

        return projections.stream().map((projection -> RoleResponse.builder().roleUid(projection.getRoleUid()).roleLevel(projection.getRoleLevel()).roleName(projection.getRoleName()).build())).collect(Collectors.toList());
    }


//    @Transactional
//    public Boolean raiseRequest(List<RaiseRequest> requests) {
//        List<K8ResourceRequest> roleRequests = requests.stream()
//                .map((request -> K8ResourceRequest.builder()
//                        .roleId(request.getRoleId())
//                        .roleName(request.getRoleName())
//                        .resourceId(request.getResourceId())
//                        .resourceType(request.getResourceType())
//                        .roleType(request.getRoleType())
//                        .cloudType(request.getCloudType())
//                        .clusterId(request.getClusterId())
//                        .namespace(request.getNamespace())
//                        .wsTenantName(request.getWsTenantName())
//                        .status(RequestStatus.APPROVED)
//                        .build())).toList();
//
////        k8CustomResourceRequestRepository.saveAll(roleRequests);
//
//        return Boolean.TRUE;
//    }


    //-------------------------------------------------------------//    //-------------------------------------------------------------//
    //-------------------------------------------------------------//    //-------------------------------------------------------------//


    @Transactional(readOnly = true)
    public void assignClusterRole(String clusterRoleName) {
        initialise();
        String roleBindingName = UUID.randomUUID().toString();
        String userName = "testuser@VinayMamidi76outlook.onmicrosoft.com";
        String apiGroup = "rbac.authorization.k8s.io";
        String apiVersion = "rbac.authorization.k8s.io/v1";
        assignClusterRole(clusterRoleName, roleBindingName, userName, apiGroup, apiVersion);
    }

    @Transactional(readOnly = true)
    public void assignNamespaceRole(String namespace, String namespaceRoleName) {
        initialise();
        String roleBindingName = UUID.randomUUID().toString();
        String userName = "testuser@VinayMamidi76outlook.onmicrosoft.com";
        String apiGroup = "rbac.authorization.k8s.io";
        String apiVersion = "rbac.authorization.k8s.io/v1";
        assignNamespaceRole(namespace, roleBindingName, namespaceRoleName, userName, apiGroup, apiVersion);
    }


    public String createNamespaceRole(String namespace, List<String> resourceNames) {
        initialise();
        V1Role v1Role = createV1Role(namespace, resourceNames);
        return v1Role.getMetadata().getUid();
    }

    public void test() {
        initialise();
        try {
            V1RoleBindingList v1RoleBindingList = this.rbacApi.listRoleBindingForAllNamespaces().execute();
            log.info("Response Kind: {}", v1RoleBindingList.getKind()); // This will output "RoleBindingList"
            log.info("Response API Version: {}", v1RoleBindingList.getApiVersion()); // This will output "rbac.authorization.k8s.io/v1"

            v1RoleBindingList.getItems().forEach((item) -> {
                log.info("RoleBinding Kind: {}", item.getKind()); // This will output "RoleBinding" for each item in the list
                log.info("RoleBinding Kind: {}", item.getApiVersion()); // This will output "RoleBinding" for each item in the list
                log.info("RoleBinding Name: {}", item.getMetadata().getName());
                log.info("---------------");
            });


        } catch (Exception e) {
            throw new K8ResourceException(e.getMessage());
        }
    }


    private V1Role createV1Role(String namespace, List<String> resourceNames) {
        try {
            V1Role v1Role = new V1Role()
                    .metadata(new V1ObjectMeta().name(namespace + "-example-role-2").namespace(namespace))
                    .rules(Collections.singletonList(
                            new V1PolicyRule()
                                    .verbs(Arrays.asList("get", "list"))
                                    .apiGroups(Collections.singletonList(""))
                                    .resources(Collections.singletonList("secrets"))
                                    .resourceNames(resourceNames)
                    ));
            return rbacApi.createNamespacedRole(namespace, v1Role).execute();
        } catch (Exception exp) {
            throw new K8ResourceException("Error: " + exp.getMessage());
        }
    }


    private V1RoleBinding assignNamespaceRole(String namespace,
                                              String roleBindingName,
                                              String roleName,
                                              String userName,
                                              String apiGroup,
                                              String apiVersion) {
        try {
            V1ObjectMeta metadata = new V1ObjectMeta();
            metadata.setName(roleBindingName);
            metadata.setNamespace(namespace);

            V1RoleRef roleRef = new V1RoleRef();
            roleRef.setKind(K8RoleKind.Role.name());
            roleRef.setName(roleName);
            roleRef.setApiGroup(apiGroup);

            RbacV1Subject subject = new RbacV1Subject();
            subject.setKind("User");
            subject.setName(userName);
            subject.setApiGroup(apiGroup);

            V1RoleBinding roleBinding = new V1RoleBinding();
            roleBinding.setMetadata(metadata);
            roleBinding.setRoleRef(roleRef);
            roleBinding.addSubjectsItem(subject);
            roleBinding.setKind(K8RoleBindingType.RoleBinding.name());
            roleBinding.setApiVersion(apiVersion);

            roleBinding = this.rbacApi.createNamespacedRoleBinding(namespace, roleBinding).execute();
            log.info("Created namespaceRoleBinding UID: {}", roleBinding.getMetadata().getUid());
            return roleBinding;
        } catch (ApiException exp) {
            throw new K8ResourceException("Failed to assign namespace role. Message: " + exp.getMessage());
        }
    }


    private V1ClusterRoleBinding assignClusterRole(String clusterRoleName,
                                                   String roleBindingName,
                                                   String userName,
                                                   String apiGroup,
                                                   String apiVersion) {
        try {
            V1ObjectMeta metadata = new V1ObjectMeta();
            metadata.setName(roleBindingName);
            metadata.setNamespace(null);

            V1RoleRef roleRef = new V1RoleRef();
            roleRef.setKind(K8RoleKind.ClusterRole.name());
            roleRef.setName(clusterRoleName);
            roleRef.setApiGroup(apiGroup);

            RbacV1Subject subject = new RbacV1Subject();
            subject.setKind("User");
            subject.setName(userName);
            subject.setApiGroup(apiGroup);

            V1ClusterRoleBinding clusterRoleBinding = new V1ClusterRoleBinding();
            clusterRoleBinding.setMetadata(metadata);
            clusterRoleBinding.setRoleRef(roleRef);
            clusterRoleBinding.setSubjects(Collections.singletonList(subject));
            clusterRoleBinding.setKind(K8RoleBindingType.ClusterRoleBinding.name());
            clusterRoleBinding.setApiVersion(apiVersion);

            clusterRoleBinding = this.rbacApi.createClusterRoleBinding(clusterRoleBinding).execute();
            log.info("Created clusterRoleBinding UID: {}", clusterRoleBinding.getMetadata().getUid());
            return clusterRoleBinding;
        } catch (ApiException exp) {
            throw new K8ResourceException("Failed to assign cluster role. Message: " + exp.getMessage());
        }
    }


    public void deleteClusterRoleBindingUsingName(String name) {
        try {
            this.rbacApi.deleteClusterRoleBinding(name).execute();
        } catch (Exception exp) {
            throw new K8ResourceException("Failed to delete cluster role binding. Message: " + exp.getMessage());
        }
    }

    public void deleteNamespaceRoleBindingUsingName(String namespace, String name) {
        try {
            this.rbacApi.deleteNamespacedRoleBinding(name, namespace).execute();
        } catch (Exception exp) {
            throw new K8ResourceException("Failed to delete cluster role binding. Message: " + exp.getMessage());
        }
    }


    private void initialise() {
//        AzureKubernetesCluster azureKubernetesCluster = azureKubernetesClusterRepository.findByName("ws-test-aks-cluster-1").get();
//        String severURL = EncryptionUtil.getDecryptedKey(azureKubernetesCluster.getAzureK8ClusterCredentials().get(0).getClusterServerUrl(), Constant.AKS_CLUSTER_SERVER_URL);
//        String token = EncryptionUtil.getDecryptedKey(azureKubernetesCluster.getAzureK8ClusterCredentials().get(0).getToken(), Constant.AKS_CLUSTER_TOKEN);
        initializeK8Client("https://ws-test-aks-cluster-1-dns-8t33e8yw.hcp.eastus.azmk8s.io:443",
                "2m1cdratgzh42n8k2n7rdd5ovazvn4ymgvdeb5bk2vjp94o5wqr8vwgfr0m863f8c0lwzr7rtmb5dy8dzwafp09kj4jrcqt5n49v4qsc9sls018c2nt20u9pyoqahqhi");
        initializeK8RbacClient();
    }

    private void initializeK8Client(String clusterURL, String token) {
        try {
            ApiClient client = Config.fromToken(clusterURL, token);
            client.setVerifyingSsl(false);
            Configuration.setDefaultApiClient(client);
        } catch (Exception ex) {
            log.error("Error in initializing k8 client");
            log.error("Error: {}", ex.getMessage());
            throw new RuntimeException(ex.getMessage());
        }
    }


    private void initializeK8RbacClient() {
        this.rbacApi = new RbacAuthorizationV1Api();
//        this.coreV1Api = new CoreV1Api();
    }


}


