package com.ws.azureKuberntesJIT.service;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.exception.AzureDataException;
import com.ws.azureAdIntegration.exception.K8DataException;
import com.ws.azureAdIntegration.service.BackendApplicationLogservice;
import com.ws.azureAdIntegration.util.GenericUtil;
import com.ws.azureKuberntesJIT.constant.K8ResourceType;
import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import com.ws.azureKuberntesJIT.dto.*;
import com.ws.azureKuberntesJIT.enttity.*;
import com.ws.azureKuberntesJIT.repository.*;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8ResourcesSyncService {
    String wsTenantName;
    String tenantEmail;
    String resourceAccountId;
    CoreV1Api coreV1Api;
    AppsV1Api appsV1Api;
    BatchV1Api batchApi;
    StorageV1Api storageV1Api;
    NetworkingV1Api networkingApi;
    RbacAuthorizationV1Api rbacApi;
    ApiextensionsV1Api apiextensionsV1Api;
    CloudProviderType cloudProviderType;
    final K8RoleRepository k8RoleRepository;
    final K8ConfigMapRepository k8ConfigMapRepository;
    final K8CustomResourceDefinitionRepository k8CustomResourceDefinitionRepository;
    final K8DeploymentRepository k8DeploymentRepository;
    final K8NetworkPolicyRepository k8NetworkPolicyRepository;
    final K8NodeRepository k8NodeRepository;
    final K8RoleReferenceRepository k8RoleReferenceRepository;
    final K8RoleBindRepository k8RoleBindRepository;
    final K8SecretRepository k8SecretRepository;
    final K8ServiceAccountRepository k8ServiceAccountRepository;
    final K8NamespaceRepository k8NamespaceRepository;
    final K8PersistentVolumeRepository k8PersistentVolumeRepository;
    final K8PersistentVolumeClaimRepository k8PersistentVolumeClaimRepository;
    final K8StorageClassRepository K8StorageClassRepository;
    final K8ResourceAnnotationRepository k8ResourceAnnotationRepository;
    final BackendApplicationLogservice backendApplicationLogservice;

    @Autowired
    public K8ResourcesSyncService(K8RoleRepository k8RoleRepository, K8ConfigMapRepository
            k8ConfigMapRepository, K8CustomResourceDefinitionRepository k8CustomResourceDefinitionRepository,
                                  K8DeploymentRepository k8DeploymentRepository, K8NetworkPolicyRepository k8NetworkPolicyRepository,
                                  K8NodeRepository k8NodeRepository, K8RoleReferenceRepository k8RoleReferenceRepository, K8RoleBindRepository k8RoleBindRepository,
                                  K8SecretRepository k8SecretRepository, K8ServiceAccountRepository k8ServiceAccountRepository,
                                  K8NamespaceRepository k8NamespaceRepository,
                                  K8PersistentVolumeRepository k8PersistentVolumeRepository, K8PersistentVolumeClaimRepository k8PersistentVolumeClaimRepository,
                                  K8StorageClassRepository K8StorageClassRepository, K8ResourceAnnotationRepository k8ResourceAnnotationRepository, BackendApplicationLogservice backendApplicationLogservice
    ) {
        this.k8RoleRepository = k8RoleRepository;
        this.k8ConfigMapRepository = k8ConfigMapRepository;
        this.k8CustomResourceDefinitionRepository = k8CustomResourceDefinitionRepository;
        this.k8DeploymentRepository = k8DeploymentRepository;
        this.k8NetworkPolicyRepository = k8NetworkPolicyRepository;
        this.k8NodeRepository = k8NodeRepository;
        this.k8RoleReferenceRepository = k8RoleReferenceRepository;
        this.k8RoleBindRepository = k8RoleBindRepository;
        this.k8SecretRepository = k8SecretRepository;
        this.k8ServiceAccountRepository = k8ServiceAccountRepository;
        this.k8NamespaceRepository = k8NamespaceRepository;
        this.k8PersistentVolumeRepository = k8PersistentVolumeRepository;
        this.k8PersistentVolumeClaimRepository = k8PersistentVolumeClaimRepository;
        this.K8StorageClassRepository = K8StorageClassRepository;
        this.k8ResourceAnnotationRepository = k8ResourceAnnotationRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
    }
//    @Transactional
//    public void syncKubernetesData(K8ResourceDataSyncRequest k8ResourceDataSyncRequest) {
//        if (ObjectUtils.isEmpty(k8ResourceDataSyncRequest)) {
//            throw new K8DataException("Please provide payload to start K8 resources data sync");
//        }
//        this.cloudProviderType = k8ResourceDataSyncRequest.getCloudProviderType();
//        this.wsTenantName = k8ResourceDataSyncRequest.getWsTenantName();
//        this.tenantEmail = k8ResourceDataSyncRequest.getTenantEmail();
//        this.resourceAccountId = k8ResourceDataSyncRequest.getResourceAccountId();
//        for (Map.Entry<String, String> clusterIdAndKubeConfigMapEntry : k8ResourceDataSyncRequest.getClusterIdAndKubeConfigMap().entrySet()) {
//            initializeK8Client(clusterIdAndKubeConfigMapEntry.getValue());
//            log.info("K8 client initialized successfully");
//            initializeK8sApis();
//            log.info(String.format("K8 resources data sync STARTED for cluster id: %s of type: %s at: %s", clusterIdAndKubeConfigMapEntry.getKey(), cloudProviderType, LocalDateTime.now()));
//            executeSync(clusterIdAndKubeConfigMapEntry.getKey());
//            log.info(String.format("K8 resources data sync COMPLETED for cluster id: %s of type: %s at: %s", clusterIdAndKubeConfigMapEntry.getKey(), cloudProviderType, LocalDateTime.now()));
//        }
//    }

    @Transactional
    public void syncKubernetesData(K8ResourceDataSyncRequest k8ResourceDataSyncRequest) {
        if (ObjectUtils.isEmpty(k8ResourceDataSyncRequest)) {
            throw new K8DataException("Please provide payload to start K8 resources data sync");
        }
        this.cloudProviderType = k8ResourceDataSyncRequest.getCloudProviderType();
        this.wsTenantName = k8ResourceDataSyncRequest.getWsTenantName();
        this.tenantEmail = k8ResourceDataSyncRequest.getTenantEmail();
        this.resourceAccountId = k8ResourceDataSyncRequest.getResourceAccountId();

        for (Triple<String, String, String> clusterConfigTriple : k8ResourceDataSyncRequest.getClusterConfigTriples()) {
            String clusterId = clusterConfigTriple.getLeft();
            String clusterURL = clusterConfigTriple.getMiddle();
            String clusterToken = clusterConfigTriple.getRight();
            initializeK8Client(clusterURL, clusterToken);
            log.info("K8 client initialized successfully");
            initializeK8sApis();
            log.info("K8 APIs initialized successfully");
            log.info(String.format("K8 resources data sync STARTED for cluster id: %s of type: %s at: %s", clusterId, cloudProviderType, LocalDateTime.now()));
            executeSync(clusterId);
            log.info(String.format("K8 resources data sync COMPLETED for cluster id: %s of type: %s at: %s", clusterId, cloudProviderType, LocalDateTime.now()));
        }
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

    private void initializeK8Client(String kubeConfig) {
        try {
            ApiClient client = Config.fromConfig(new StringReader(kubeConfig));
            Configuration.setDefaultApiClient(client);
        } catch (Exception ex) {
            log.error("Error in initializing k8 client");
            log.error("Error: {}", ex.getMessage());
            throw new RuntimeException(ex.getMessage());
        }
    }

    private void initializeK8sApis() {
        this.coreV1Api = new CoreV1Api();
        this.appsV1Api = new AppsV1Api();
        this.batchApi = new BatchV1Api();
        this.storageV1Api = new StorageV1Api();
        this.networkingApi = new NetworkingV1Api();
        this.rbacApi = new RbacAuthorizationV1Api();
        this.apiextensionsV1Api = new ApiextensionsV1Api();
    }


    private void executeSync(String clusterId) {
        try {
            fetchNamespace(clusterId); /* Required to fetch */
            fetchNodes(clusterId);
            fetchCustomResourceDefinition(clusterId);
            fetchClusterRoles(clusterId);
//            fetchClusterRoleBinding(clusterId);
            fetchNamespaceRoles(clusterId);
//            fetchNamespaceRoleBinding(clusterId);
//            fetchDeployments(clusterId);
//            fetchSecrets(clusterId);
//            fetchServiceAccounts(clusterId);
//            fetchPersistentVolumes(clusterId);
//            fetchPersistentVolumeClaims(clusterId);
//            fetchStorageClasses(clusterId);
//            fetchConfigMap(clusterId);
//            fetchNetworkPolicies(clusterId);
        } catch (Exception ex) {
            log.error("Error occurred in syncing data from Kubernetes: {}", ex.getMessage());
            throw new RuntimeException(ex.getMessage());
        }
    }

    private void fetchNamespace(String clusterId) throws ApiException {
        V1NamespaceList v1NamespaceList = this.coreV1Api.listNamespace().execute();
        if (ObjectUtils.isEmpty(v1NamespaceList)) {
            throw new AzureDataException("No NAMESPACE(s) found");
        }
        List<K8Namespace> kubernetesNamespaces = v1NamespaceList.getItems().stream()
                .map(item -> {
                    K8Namespace k8Namespace = K8Namespace.builder()
                            .kind(item.getKind())
                            .apiVersion(item.getApiVersion())
                            .phase(GenericUtil.getOrNull(() -> Objects.requireNonNull(item.getStatus()).getPhase()))
                            .build();
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadata(k8Namespace, item.getMetadata(), clusterId);
                    }
                    return k8Namespace;
                })
                .toList();
        k8NamespaceRepository.saveAll(kubernetesNamespaces);
    }

    private void fetchNodes(String clusterId) throws ApiException {
        V1NodeList v1NodeList = this.coreV1Api.listNode().execute();
        if (ObjectUtils.isEmpty(v1NodeList)) {
            log.warn(String.format("No NODE(s) found for cluster id: %S of cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8Node> kubernetesNodes = v1NodeList.getItems().stream()
                .map(item -> {
                    K8Node k8Node = K8Node.builder()
                            .kind(item.getKind())
                            .apiVersion(item.getApiVersion())
                            .phase(GenericUtil.getOrNull(() -> Objects.requireNonNull(item.getStatus()).getPhase()))
                            .externalID(GenericUtil.getOrNull(() -> Objects.requireNonNull(item.getSpec()).getExternalID()))
                            .podCIDR(GenericUtil.getOrNull(() -> Objects.requireNonNull(item.getSpec()).getPodCIDR()))
                            .unschedulable(GenericUtil.getOrNull(() -> Objects.requireNonNull(item.getSpec()).getUnschedulable()))
                            .providerID(GenericUtil.getOrNull(() -> Objects.requireNonNull(item.getSpec()).getProviderID()))
                            .build();
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadata(k8Node, item.getMetadata(), clusterId);
                    }
                    return k8Node;
                })
                .toList();
        k8NodeRepository.saveAll(kubernetesNodes);
    }

    private void fetchCustomResourceDefinition(String clusterId) throws ApiException {
        V1CustomResourceDefinitionList v1CustomResourceDefinitionList = this.apiextensionsV1Api.listCustomResourceDefinition().execute();
        if (ObjectUtils.isEmpty(v1CustomResourceDefinitionList)) {
            log.warn(String.format("No CustomResourceDefinition(s) found for cluster id: %S of cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8CustomResourceDefinition> k8CustomResourceDefinitions = v1CustomResourceDefinitionList.getItems().stream()
                .filter(item -> item.getMetadata() != null)
                .map(item -> {
                    K8CustomResourceDefinition k8CustomResourceDefinition = K8CustomResourceDefinition.builder()
                            .kind(item.getKind())
                            .apiVersion(item.getApiVersion())
                            .build();
                    setMetadata(k8CustomResourceDefinition, item.getMetadata(), clusterId);
                    return k8CustomResourceDefinition;
                })
                .collect(Collectors.toList());

        k8CustomResourceDefinitionRepository.saveAll(k8CustomResourceDefinitions);
    }

    private void fetchClusterRoles(String clusterId) throws ApiException {
        V1ClusterRoleList v1ClusterRoleList = this.rbacApi.listClusterRole().execute();
        if (ObjectUtils.isEmpty(v1ClusterRoleList)) {
            log.warn(String.format("No CLUSTER_ROLE(s) found for cluster id: %S of cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        log.info("v1ClusterRoleList size: {}", v1ClusterRoleList.getItems().size());
        List<K8Role> k8ClusterRoles = v1ClusterRoleList.getItems().stream()
                .map(item -> {
                    K8Role clusterRole = K8Role.builder()
                            .kind(item.getKind())
                            .apiVersion(item.getApiVersion())
                            .roleType(K8ResourceLevel.CLUSTER)
                            .build();
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadata(clusterRole, item.getMetadata(), clusterId);
                    }

                    V1AggregationRule v1AggregationRule = item.getAggregationRule();
                    if (!ObjectUtils.isEmpty(v1AggregationRule)) {
                        K8AggregationRule k8AggregationRule = new K8AggregationRule();

                        List<K8LabelSelector> k8LabelSelectors = v1AggregationRule.getClusterRoleSelectors().stream()
                                .map(v1LabelSelector -> {
                                    K8LabelSelector k8LabelSelector = K8LabelSelector.builder()
                                            .matchLabels(v1LabelSelector.getMatchLabels())
                                            .clusterRoleUID(item.getMetadata().getUid())
                                            .k8AggregationRule(k8AggregationRule)
                                            .build();

                                    List<K8LabelSelectorRequirement> k8LabelSelectorRequirements = v1LabelSelector.getMatchExpressions().stream()
                                            .map(matchExpression -> K8LabelSelectorRequirement.builder()
                                                    .key(matchExpression.getKey())
                                                    .operator(matchExpression.getOperator())
                                                    .values(matchExpression.getValues())
                                                    .k8LabelSelector(k8LabelSelector)
                                                    .build())
                                            .collect(Collectors.toList());


                                    k8LabelSelector.setMatchExpressions(k8LabelSelectorRequirements);
                                    return k8LabelSelector;
                                })
                                .collect(Collectors.toList());

                        k8AggregationRule.setClusterRoleUID(item.getMetadata().getUid());
                        k8AggregationRule.setK8LabelSelectors(k8LabelSelectors);
                        k8AggregationRule.setKubernetesRole(clusterRole);
                        clusterRole.setKubernetesAggregationRule(k8AggregationRule);
                    }
                    Set<K8RolePolicyRule> policyRules = item.getRules().stream()
                            .map(v1PolicyRule -> K8RolePolicyRule.builder()
                                    .verbs(v1PolicyRule.getVerbs())
                                    .apiGroups(v1PolicyRule.getApiGroups())
                                    .resources(v1PolicyRule.getResources())
                                    .nonResourceURLs(v1PolicyRule.getNonResourceURLs())
                                    .resourceNames(v1PolicyRule.getResourceNames())
                                    .roleUID(item.getMetadata().getUid())
                                    .clusterId(clusterId)
                                    .kubernetesRoleType(K8ResourceLevel.CLUSTER)
                                    .kubernetesRole(clusterRole)
                                    .cloudProviderType(this.cloudProviderType)
                                    .wsTenantName(this.wsTenantName)
                                    .build())
                            .collect(Collectors.toSet());

                    log.info("cluster policyRules count: {}", policyRules.size());

                    clusterRole.setK8RolePolicyRules(policyRules);
                    return clusterRole;
                })
                .collect(Collectors.toList());

        k8RoleRepository.saveAll(k8ClusterRoles);
    }

    private void fetchClusterRoleBinding(String clusterId) throws ApiException {
        V1ClusterRoleBindingList v1ClusterRoleBindingList = this.rbacApi.listClusterRoleBinding().execute();
        if (ObjectUtils.isEmpty(v1ClusterRoleBindingList)) {
            log.warn(String.format("NO CLUSTER_ROLE_BINDING found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
        }

        List<K8RoleBind> kubernetesRoleBinds = v1ClusterRoleBindingList.getItems().stream()
                .map(item -> {
                    K8RoleBind.K8RoleBindBuilder builder = K8RoleBind.builder();

                    builder.kubernetesRoleType(K8ResourceLevel.CLUSTER);
                    item.setKind(item.getKind());
                    item.apiVersion(item.getApiVersion());

                    // Set metadata fields
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
                        if (!CollectionUtils.isEmpty(item.getMetadata().getAnnotations())) {
                            createAndSaveK8ResourceAnnotations(item.getMetadata().getAnnotations(), K8ResourceType.CLUSTER_ROLE_BINDING, clusterId, item.getMetadata().getUid());
                        }
                    }

                    builder.roleRef(k8RoleReferenceRepository.save(convertToK8RoleReference(item.getRoleRef(), clusterId, item.getMetadata().getUid())));
                    builder.rbacSubjects(convertToK8RbacSubjects(item.getSubjects(), clusterId));
                    return builder.build();
                })
                .toList();

        k8RoleBindRepository.saveAll(kubernetesRoleBinds);
    }

    private K8RoleReference convertToK8RoleReference(V1RoleRef roleRef, String clusterId, String roleUID) {
        return Optional.ofNullable(roleRef)
                .map(ref -> K8RoleReference.builder()
                        .apiGroup(ref.getApiGroup())
                        .kind(ref.getKind())
                        .name(ref.getName())
                        .roleUID(roleUID)
                        .clusterId(clusterId)
                        .cloudProviderType(this.cloudProviderType)
                        .wsTenantName(this.wsTenantName)
                        .build())
                .orElse(null);
    }

    private List<K8RbacSubject> convertToK8RbacSubjects(List<RbacV1Subject> subjects, String clusterId) {
        return Optional.ofNullable(subjects)
                .orElse(Collections.emptyList())
                .stream()
                .map(rbacV1Subject -> K8RbacSubject.builder()
                        .kind(rbacV1Subject.getKind())
                        .apiGroup(rbacV1Subject.getApiGroup())
                        .name(rbacV1Subject.getName())
                        .namespace(rbacV1Subject.getNamespace())
                        .clusterId(clusterId)
                        .cloudProviderType(this.cloudProviderType)
                        .wsTenantName(this.wsTenantName)
//                        .kubernetesRoleBind(k8RoleBind)
                        .build())
                .collect(Collectors.toList());
    }


    private void fetchNamespaceRoles(String clusterId) throws ApiException {
        V1RoleList v1RoleList = rbacApi.listRoleForAllNamespaces().execute();
        if (ObjectUtils.isEmpty(v1RoleList)) {
            log.warn(String.format("NO NAMESPACE_ROLE(s) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8Role> kubernetesNamespaceRoles = v1RoleList.getItems().stream()
                .map(item -> {
                    K8Role namespaceRole = K8Role.builder()
                            .kind(item.getKind())
                            .apiVersion(item.getApiVersion())
                            .roleType(K8ResourceLevel.NAMESPACE)
                            .build();

                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadata(namespaceRole, item.getMetadata(), clusterId);
                    }

                    Set<K8RolePolicyRule> policyRules = item.getRules().stream()
                            .map(rule -> K8RolePolicyRule.builder()
                                    .verbs(rule.getVerbs())
                                    .apiGroups(rule.getApiGroups())
                                    .resources(rule.getResources())
                                    .nonResourceURLs(rule.getNonResourceURLs())
                                    .resourceNames(rule.getResourceNames())
                                    .roleUID(item.getMetadata().getUid())
                                    .kubernetesRoleType(K8ResourceLevel.NAMESPACE)
                                    .cloudProviderType(this.cloudProviderType)
                                    .clusterId(clusterId)
                                    .kubernetesRole(namespaceRole)
                                    .wsTenantName(this.wsTenantName)
                                    .build())
                            .collect(Collectors.toSet());

                    namespaceRole.setK8RolePolicyRules(policyRules);
                    return namespaceRole;
                })
                .collect(Collectors.toList());

        k8RoleRepository.saveAll(kubernetesNamespaceRoles);
    }

    private void fetchNamespaceRoleBinding(String clusterId) throws ApiException {
        V1RoleBindingList v1RoleBindingList = rbacApi.listRoleBindingForAllNamespaces().execute();
        if (ObjectUtils.isEmpty(v1RoleBindingList)) {
            log.warn(String.format("NO NAMESPACE_ROLE_BINDING found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }

        List<K8RoleBind> kubernetesNamespaceRoleBinds = v1RoleBindingList.getItems().stream()
                .map(item -> {
                    K8RoleBind.K8RoleBindBuilder builder = K8RoleBind.builder();
                    item.setKind(item.getKind());
                    item.apiVersion(item.getApiVersion());
                    builder.kubernetesRoleType(K8ResourceLevel.NAMESPACE);

                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
                        if (!CollectionUtils.isEmpty(item.getMetadata().getAnnotations())) {
                            createAndSaveK8ResourceAnnotations(item.getMetadata().getAnnotations(), K8ResourceType.NAMESPACE_ROLE_BINDING, clusterId, item.getMetadata().getUid());
                        }
                    }

                    builder.roleRef(k8RoleReferenceRepository.save(convertToK8RoleReference(item.getRoleRef(), clusterId, item.getMetadata().getUid())));
                    builder.rbacSubjects(convertToK8RbacSubjects(item.getSubjects(), clusterId));
                    return builder.build();
                })
                .toList();

        k8RoleBindRepository.saveAll(kubernetesNamespaceRoleBinds);
    }

    private void fetchDeployments(String clusterId) throws ApiException {
        V1DeploymentList v1DeploymentList = this.appsV1Api.listDeploymentForAllNamespaces().execute();
        if (ObjectUtils.isEmpty(v1DeploymentList.getItems())) {
            log.warn(String.format("NO DEPLOYMENT(s) found for cluster id: %s of cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8Deployment> k8Deployments = v1DeploymentList.getItems().stream()
                .filter(item -> item.getMetadata() != null)
                .map(item -> {
                    K8Deployment.K8DeploymentBuilder builder = K8Deployment.builder();
                    item.setKind(item.getKind());
                    item.apiVersion(item.getApiVersion());
                    setMetadataFields(builder, item.getMetadata(), clusterId);
                    if (!CollectionUtils.isEmpty(item.getMetadata().getAnnotations())) {
                        createAndSaveK8ResourceAnnotations(item.getMetadata().getAnnotations(), K8ResourceType.DEPLOYMENT, clusterId, item.getMetadata().getUid());
                    }
                    return builder.build();
                })
                .collect(Collectors.toList());

        k8DeploymentRepository.saveAll(k8Deployments);
    }

    private void fetchSecrets(String clusterId) throws ApiException {
        V1SecretList v1SecretList = this.coreV1Api.listSecretForAllNamespaces().execute();
        if (ObjectUtils.isEmpty(v1SecretList)) {
            log.warn(String.format("NO SECRET(s) found for cluster id: %s of cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8Secret> kubernetesSecrets = v1SecretList.getItems().stream()
                .map(item -> {
                    K8Secret.K8SecretBuilder builder = K8Secret.builder();
                    item.setKind(item.getKind());
                    item.apiVersion(item.getApiVersion());
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
                        if (!CollectionUtils.isEmpty(item.getMetadata().getAnnotations())) {
                            createAndSaveK8ResourceAnnotations(item.getMetadata().getAnnotations(), K8ResourceType.SECRET, clusterId, item.getMetadata().getUid());
                        }
                    }
                    builder.type(item.getType())
                            .immutable(item.getImmutable())
                            .stringData(item.getStringData());
                    return builder.build();
                })
                .toList();

        k8SecretRepository.saveAll(kubernetesSecrets);
    }

    private void fetchServiceAccounts(String clusterId) throws ApiException {
        V1ServiceAccountList v1ServiceAccountList = coreV1Api.listServiceAccountForAllNamespaces().execute();
        if (ObjectUtils.isEmpty(v1ServiceAccountList)) {
            log.warn(String.format("NO SERVICE_ACCOUNT(s) found for cluster id: %s of cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8ServiceAccount> kubernetesServiceAccounts = v1ServiceAccountList.getItems().stream()
                .map(item -> {
                    K8ServiceAccount.K8ServiceAccountBuilder builder = K8ServiceAccount.builder();
                    item.setKind(item.getKind());
                    item.apiVersion(item.getApiVersion());
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
                        if (!CollectionUtils.isEmpty(item.getMetadata().getAnnotations())) {
                            createAndSaveK8ResourceAnnotations(item.getMetadata().getAnnotations(), K8ResourceType.SERVICE_ACCOUNT, clusterId, item.getMetadata().getUid());
                        }
                    }
                    builder.automountServiceAccountToken(item.getAutomountServiceAccountToken());
                    return builder.build();
                })
                .toList();

        k8ServiceAccountRepository.saveAll(kubernetesServiceAccounts);
    }

    private void fetchPersistentVolumes(String clusterId) throws ApiException {
        V1PersistentVolumeList v1PersistentVolumeList = this.coreV1Api.listPersistentVolume().execute();
        if (ObjectUtils.isEmpty(v1PersistentVolumeList)) {
            log.warn(String.format("NO PERSISTENT_VOLUME(s) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8PersistentVolume> persistentVolumes = v1PersistentVolumeList.getItems().stream()
                .filter(item -> item.getMetadata() != null)
                .map(item -> {
                    K8PersistentVolume.K8PersistentVolumeBuilder builder = K8PersistentVolume.builder();
                    item.setKind(item.getKind());
                    item.apiVersion(item.getApiVersion());
                    setMetadataFields(builder, item.getMetadata(), clusterId);
                    if (!CollectionUtils.isEmpty(item.getMetadata().getAnnotations())) {
                        createAndSaveK8ResourceAnnotations(item.getMetadata().getAnnotations(), K8ResourceType.PERSISTENT_VOLUME, clusterId, item.getMetadata().getUid());
                    }
                    return builder.build();
                })
                .toList();

        k8PersistentVolumeRepository.saveAll(persistentVolumes);
    }

    private void fetchPersistentVolumeClaims(String clusterId) throws ApiException {
        V1PersistentVolumeClaimList v1PersistentVolumeClaimList = this.coreV1Api.listPersistentVolumeClaimForAllNamespaces().execute();
        if (ObjectUtils.isEmpty(v1PersistentVolumeClaimList)) {
            log.warn(String.format("NO PERSISTENT_VOLUME_CLAIM(s) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8PersistentVolumeClaim> persistentVolumeClaims = v1PersistentVolumeClaimList.getItems().stream()
                .filter(item -> item.getMetadata() != null)
                .map(item -> {
                    K8PersistentVolumeClaim.K8PersistentVolumeClaimBuilder builder = K8PersistentVolumeClaim.builder();
                    item.setKind(item.getKind());
                    item.apiVersion(item.getApiVersion());
                    setMetadataFields(builder, item.getMetadata(), clusterId);
                    if (!CollectionUtils.isEmpty(item.getMetadata().getAnnotations())) {
                        createAndSaveK8ResourceAnnotations(item.getMetadata().getAnnotations(), K8ResourceType.PERSISTENT_VOLUME_CLAIM, clusterId, item.getMetadata().getUid());
                    }
                    return builder.build();
                })
                .toList();

        k8PersistentVolumeClaimRepository.saveAll(persistentVolumeClaims);
    }

    private void fetchStorageClasses(String clusterId) throws ApiException {
        V1StorageClassList v1StorageClassList = this.storageV1Api.listStorageClass().execute();
        if (ObjectUtils.isEmpty(v1StorageClassList)) {
            log.warn(String.format("NO STORAE_CLASS(Es) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8StorageClass> kubernetesStorageClasses = v1StorageClassList.getItems().stream()
                .map(item -> {
                    K8StorageClass.K8StorageClassBuilder builder = K8StorageClass.builder();
                    item.setKind(item.getKind());
                    item.apiVersion(item.getApiVersion());
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
                        if (!CollectionUtils.isEmpty(item.getMetadata().getAnnotations())) {
                            createAndSaveK8ResourceAnnotations(item.getMetadata().getAnnotations(), K8ResourceType.STORAGE_CLASS, clusterId, item.getMetadata().getUid());
                        }
                    }
                    builder.apiVersion(item.getApiVersion())
                            .provisioner(item.getProvisioner())
                            .volumeBindingMode(item.getVolumeBindingMode())
                            .allowVolumeExpansion(item.getAllowVolumeExpansion())
                            .reclaimPolicy(item.getReclaimPolicy());

                    return builder.build();
                })
                .toList();

        K8StorageClassRepository.saveAll(kubernetesStorageClasses);

    }

    private void fetchConfigMap(String clusterId) throws ApiException {
        V1ConfigMapList v1ConfigMapList = this.coreV1Api.listConfigMapForAllNamespaces().execute();
        if (ObjectUtils.isEmpty(v1ConfigMapList)) {
            log.warn(String.format("NO CONFIG_MAP(Es) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8ConfigMap> configMaps = v1ConfigMapList.getItems().stream()
                .map(item -> {
                    K8ConfigMap.K8ConfigMapBuilder builder = K8ConfigMap.builder();
                    item.setKind(item.getKind());
                    item.apiVersion(item.getApiVersion());
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
                        if (!CollectionUtils.isEmpty(item.getMetadata().getAnnotations())) {
                            createAndSaveK8ResourceAnnotations(item.getMetadata().getAnnotations(), K8ResourceType.CONFIG_MAP, clusterId, item.getMetadata().getUid());
                        }
                    }
                    builder.immutable(item.getImmutable());
                    return builder.build();
                })
                .toList();

        k8ConfigMapRepository.saveAll(configMaps);
    }

    private void fetchNetworkPolicies(String clusterId) throws ApiException {
        V1NetworkPolicyList v1NetworkPolicyList = this.networkingApi.listNetworkPolicyForAllNamespaces().execute();
        if (ObjectUtils.isEmpty(v1NetworkPolicyList)) {
            log.warn(String.format("NO NETWORK_POLICIES found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8NetworkPolicy> kubernetesNetworkPolicies = v1NetworkPolicyList.getItems().stream()
                .map(item -> {
                    K8NetworkPolicy.K8NetworkPolicyBuilder builder = K8NetworkPolicy.builder();
                    item.setKind(item.getKind());
                    item.apiVersion(item.getApiVersion());
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
                        if (!CollectionUtils.isEmpty(item.getMetadata().getAnnotations())) {
                            createAndSaveK8ResourceAnnotations(item.getMetadata().getAnnotations(), K8ResourceType.NETWORK_POLICY, clusterId, item.getMetadata().getUid());
                        }
                    }

                    return builder.build();
                })
                .toList();

        k8NetworkPolicyRepository.saveAll(kubernetesNetworkPolicies);
    }


    private void setMetadataFields(K8Metadata.K8MetadataBuilder builder, V1ObjectMeta metadata, String clusterId) {
        builder.selfLink(metadata.getSelfLink());
        builder.resourceVersion(metadata.getResourceVersion());
        builder.generation(metadata.getGeneration());
        builder.name(metadata.getName());
        builder.uid(metadata.getUid());
        builder.namespace(metadata.getNamespace());
        builder.generateName(metadata.getGenerateName());
        builder.creationTimestamp(metadata.getCreationTimestamp());
        builder.deletionTimestamp(metadata.getDeletionTimestamp());
        builder.clusterId(clusterId);
        builder.cloudProviderType(this.cloudProviderType);
        builder.wsTenantName(this.wsTenantName);
        builder.cloudProviderType(this.cloudProviderType);
        builder.cloudResourceAccountId(this.resourceAccountId);
    }

    private void setMetadata(K8Metadata k8Metadata, V1ObjectMeta metadata, String clusterId) {
        k8Metadata.setSelfLink(metadata.getSelfLink());
        k8Metadata.setResourceVersion(metadata.getResourceVersion());
        k8Metadata.setGeneration(metadata.getGeneration());
        k8Metadata.setName(metadata.getName());
        k8Metadata.setUid(metadata.getUid());
        k8Metadata.setNamespace(metadata.getNamespace());
        k8Metadata.setGenerateName(metadata.getGenerateName());
        k8Metadata.setCreationTimestamp(metadata.getCreationTimestamp());
        k8Metadata.setDeletionTimestamp(metadata.getDeletionTimestamp());
        k8Metadata.setClusterId(clusterId);
        k8Metadata.setCloudProviderType(this.cloudProviderType);
        k8Metadata.setWsTenantName(this.wsTenantName);
        k8Metadata.setCloudResourceAccountId(this.resourceAccountId);
    }


    private void createAndSaveK8ResourceAnnotations(Map<String, String> annotationsMap,
                                                    K8ResourceType type, String ClusterId, String kubernetesResourceId) {
        List<K8ResourceAnnotation> k8ResourceAnnotations = new ArrayList<>();
        for (Map.Entry<String, String> entry : annotationsMap.entrySet()) {
            K8ResourceAnnotation annotation = K8ResourceAnnotation.builder()
                    .key("entry.getKey()")
                    .value("entry.getValue()")
                    .k8ResourceType(type)
                    .kubernetesResourceId(kubernetesResourceId)
                    .clusterId(ClusterId)
                    .resourceAccountId(this.resourceAccountId)
                    .cloudProviderType(this.cloudProviderType)
                    .wsTenantName(this.wsTenantName)
                    .build();

            k8ResourceAnnotations.add(annotation);
        }
        k8ResourceAnnotationRepository.saveAll(k8ResourceAnnotations);
    }

}




