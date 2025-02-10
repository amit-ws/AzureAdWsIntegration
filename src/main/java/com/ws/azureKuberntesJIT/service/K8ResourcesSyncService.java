package com.ws.azureKuberntesJIT.service;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.exception.AzureDataException;
import com.ws.azureAdIntegration.exception.K8DataException;
import com.ws.azureAdIntegration.service.BackendApplicationLogservice;
import com.ws.azureKuberntesJIT.constant.RoleLevelType;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    final K8ClusterRoleRepository k8ClusterRoleRepository;
    final K8ConfigMapRepository k8ConfigMapRepository;
    final K8CustomResourceDefinitionRepository k8CustomResourceDefinitionRepository;
    final K8DeploymentRepository k8DeploymentRepository;
    final K8NetworkPolicyRepository k8NetworkPolicyRepository;
    final K8NodeRepository k8NodeRepository;
    final K8RoleBindRepository k8RoleBindRepository;
    final K8SecretRepository k8SecretRepository;
    final K8ServiceAccountRepository k8ServiceAccountRepository;
    final K8NamespaceRepository k8NamespaceRepository;
    final K8NamespaceRoleRepository k8NamespaceRoleRepository;
    final K8PersistentVolumeRepository k8PersistentVolumeRepository;
    final K8PersistentVolumeClaimRepository k8PersistentVolumeClaimRepository;
    final K8StorageClassRepository K8StorageClassRepository;
    final BackendApplicationLogservice backendApplicationLogservice;

    @Autowired
    public K8ResourcesSyncService(K8ClusterRoleRepository k8ClusterRoleRepository, K8ConfigMapRepository
            k8ConfigMapRepository, K8CustomResourceDefinitionRepository k8CustomResourceDefinitionRepository,
                                  K8DeploymentRepository k8DeploymentRepository, K8NetworkPolicyRepository k8NetworkPolicyRepository,
                                  K8NodeRepository k8NodeRepository, K8RoleBindRepository k8RoleBindRepository,
                                  K8SecretRepository k8SecretRepository, K8ServiceAccountRepository k8ServiceAccountRepository,
                                  K8NamespaceRepository k8NamespaceRepository, K8NamespaceRoleRepository k8NamespaceRoleRepository,
                                  K8PersistentVolumeRepository k8PersistentVolumeRepository, K8PersistentVolumeClaimRepository k8PersistentVolumeClaimRepository,
                                  K8StorageClassRepository K8StorageClassRepository, BackendApplicationLogservice backendApplicationLogservice
    ) {
        this.k8ClusterRoleRepository = k8ClusterRoleRepository;
        this.k8ConfigMapRepository = k8ConfigMapRepository;
        this.k8CustomResourceDefinitionRepository = k8CustomResourceDefinitionRepository;
        this.k8DeploymentRepository = k8DeploymentRepository;
        this.k8NetworkPolicyRepository = k8NetworkPolicyRepository;
        this.k8NodeRepository = k8NodeRepository;
        this.k8RoleBindRepository = k8RoleBindRepository;
        this.k8SecretRepository = k8SecretRepository;
        this.k8ServiceAccountRepository = k8ServiceAccountRepository;
        this.k8NamespaceRepository = k8NamespaceRepository;
        this.k8NamespaceRoleRepository = k8NamespaceRoleRepository;
        this.k8PersistentVolumeRepository = k8PersistentVolumeRepository;
        this.k8PersistentVolumeClaimRepository = k8PersistentVolumeClaimRepository;
        this.K8StorageClassRepository = K8StorageClassRepository;
        this.backendApplicationLogservice = backendApplicationLogservice;
    }


    @Transactional
    public void syncKubernetesData(K8ResourceDataSyncRequest k8ResourceDataSyncRequest) {
        if (ObjectUtils.isEmpty(k8ResourceDataSyncRequest)) {
            throw new K8DataException("Please provide payload to start K8 resources data sync");
        }
        this.cloudProviderType = k8ResourceDataSyncRequest.getCloudProviderType();
        this.wsTenantName = k8ResourceDataSyncRequest.getWsTenantName();
        this.tenantEmail = k8ResourceDataSyncRequest.getTenantEmail();
        this.resourceAccountId = k8ResourceDataSyncRequest.getResourceAccountId();
        for (Map.Entry<String, String> clusterIdAndKubeConfigMapEntry : k8ResourceDataSyncRequest.getClusterIdAndKubeConfigMap().entrySet()) {
            initializeK8Clients(clusterIdAndKubeConfigMapEntry.getValue());
            log.info("K8 clients initialized successfully");
            log.info(String.format("K8 resources data sync STARTED for cluster id: %s of type: %s at: %s", clusterIdAndKubeConfigMapEntry.getKey(), cloudProviderType, LocalDateTime.now()));
            executeSync(clusterIdAndKubeConfigMapEntry.getKey());
            log.info(String.format("K8 resources data sync COMPLETED for cluster id: %s of type: %s at: %s", clusterIdAndKubeConfigMapEntry.getKey(), cloudProviderType, LocalDateTime.now()));
        }
    }

    private void initializeK8Clients(String kubeConfig) {
        try {
            ApiClient client = Config.fromConfig(new StringReader(kubeConfig));
            Configuration.setDefaultApiClient(client);
            this.coreV1Api = new CoreV1Api();
            this.appsV1Api = new AppsV1Api();
            this.batchApi = new BatchV1Api();
            this.storageV1Api = new StorageV1Api();
            this.networkingApi = new NetworkingV1Api();
            this.rbacApi = new RbacAuthorizationV1Api();
            this.apiextensionsV1Api = new ApiextensionsV1Api();
        } catch (Exception ex) {
            log.error("Error in initializing k8 clients");
            log.error("Error: {}", ex.getMessage());
            throw new RuntimeException(ex.getMessage());
        }
    }


    private void executeSync(String clusterId) {
        try {
            fetchNamespace(clusterId); /* Required to fetch */
            fetchNodes(clusterId);
            fetchCustomResourceDefinition(clusterId);
            fetchClusterRoles(clusterId);
            fetchClusterRoleBinding(clusterId);
            fetchNamespaceRoles(clusterId);
            fetchNamespaceRoleBinding(clusterId);
            fetchDeployments(clusterId);
            fetchSecrets(clusterId);
            fetchServiceAccounts(clusterId);
            fetchPersistentVolumes(clusterId);
            fetchPersistentVolumeClaims(clusterId);
            fetchStorageClasses(clusterId);
            fetchConfigMap(clusterId);
            fetchNetworkPolicies(clusterId);
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
                    K8Namespace.K8NamespaceBuilder builder = K8Namespace.builder();
                    if (!ObjectUtils.isEmpty(item.getStatus())) {
                        builder.phase(item.getStatus().getPhase());
                    }
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
                    }
                    return builder.build();
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
                    K8Node.K8NodeBuilder builder = K8Node.builder();
                    if (!ObjectUtils.isEmpty(item.getStatus())) {
                        builder.phase(item.getStatus().getPhase());
                    }
                    if (!ObjectUtils.isEmpty(item.getSpec())) {
                        builder.externalID(item.getSpec().getExternalID());
                        builder.podCIDR(item.getSpec().getPodCIDR());
                        builder.unschedulable(item.getSpec().getUnschedulable());
                        builder.providerID(item.getSpec().getProviderID());
                    }
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
                    }
                    return builder.build();
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
                    K8CustomResourceDefinition.K8CustomResourceDefinitionBuilder builder = K8CustomResourceDefinition.builder();
                    setMetadataFields(builder, item.getMetadata(), clusterId);
                    return builder.build();
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
        List<K8ClusterRole> k8ClusterRoles = v1ClusterRoleList.getItems().stream()
                .map(item -> {
                    K8ClusterRole.K8ClusterRoleBuilder builder = K8ClusterRole.builder();

                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
                    }

                    V1AggregationRule v1AggregationRule = item.getAggregationRule();
                    if (!ObjectUtils.isEmpty(v1AggregationRule)) {
                        K8AggregationRule k8AggregationRule = new K8AggregationRule();

                        List<K8LabelSelector> k8LabelSelectors = v1AggregationRule.getClusterRoleSelectors().stream()
                                .map(v1LabelSelector -> {
                                    List<K8LabelSelectorRequirement> k8LabelSelectorRequirementDTOS = v1LabelSelector.getMatchExpressions().stream()
                                            .map(matchExpression -> K8LabelSelectorRequirement.builder()
                                                    .key(matchExpression.getKey())
                                                    .operator(matchExpression.getOperator())
                                                    .values(matchExpression.getValues())
                                                    .build())
                                            .collect(Collectors.toList());

                                    return K8LabelSelector.builder()
                                            .matchExpressions(k8LabelSelectorRequirementDTOS)
                                            .matchLabels(v1LabelSelector.getMatchLabels())
                                            .build();
                                })
                                .collect(Collectors.toList());

                        k8AggregationRule.setK8LabelSelectors(k8LabelSelectors);
                        builder.k8AggregationRule(k8AggregationRule);
                    }
                    List<K8RolePolicyRule> policyRuleDTOList = item.getRules().stream()
                            .map(v1PolicyRule -> K8RolePolicyRule.builder()
                                    .verbs(v1PolicyRule.getVerbs())
                                    .apiGroups(v1PolicyRule.getApiGroups())
                                    .resources(v1PolicyRule.getResources())
                                    .nonResourceURLs(v1PolicyRule.getNonResourceURLs())
                                    .resourceNames(v1PolicyRule.getResourceNames())
                                    .build())
                            .collect(Collectors.toList());

                    builder.k8RolePolicyRules(policyRuleDTOList);

                    return builder.build();
                })
                .toList();

        k8ClusterRoleRepository.saveAll(k8ClusterRoles);
    }

    private void fetchClusterRoleBinding(String clusterId) throws ApiException {
        V1ClusterRoleBindingList v1ClusterRoleBindingList = this.rbacApi.listClusterRoleBinding().execute();
        if (ObjectUtils.isEmpty(v1ClusterRoleBindingList)) {
            log.warn(String.format("NO CLUSTER_ROLE_BINDING found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
        }

        List<K8RoleBind> kubernetesRoleBinds = v1ClusterRoleBindingList.getItems().stream()
                .map(item -> {
                    K8RoleBind.K8RoleBindBuilder builder = K8RoleBind.builder();

                    builder.roleLevelType(RoleLevelType.CLUSTER);

                    // Set metadata fields
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
                    }

                    Optional.of(item.getRoleRef())
                            .ifPresent(roleRef -> builder.roleRef(K8RoleReference.builder()
                                    .kind(roleRef.getKind())
                                    .name(roleRef.getName())
                                    .apiGroup(roleRef.getApiGroup())
                                    .build()));

                    List<K8RbacSubject> k8RbacSubjects = item.getSubjects().stream()
                            .map(rbacV1Subject -> K8RbacSubject.builder()
                                    .kind(rbacV1Subject.getKind())
                                    .apiGroup(rbacV1Subject.getApiGroup())
                                    .name(rbacV1Subject.getName())
                                    .namespace(rbacV1Subject.getNamespace())
                                    .build())
                            .collect(Collectors.toList());

                    builder.rbacSubjects(k8RbacSubjects);
                    return builder.build();
                })
                .toList();

        k8RoleBindRepository.saveAll(kubernetesRoleBinds);
    }

    private void fetchNamespaceRoles(String clusterId) throws ApiException {
        V1RoleList v1RoleList = rbacApi.listRoleForAllNamespaces().execute();
        if (ObjectUtils.isEmpty(v1RoleList)) {
            log.warn(String.format("NO NAMESPACE_ROLE(s) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }

        List<K8NamespaceRole> kubernetesNamespaceRoles = v1RoleList.getItems().stream()
                .map(item -> {
                    K8NamespaceRole.K8NamespaceRoleBuilder builder = K8NamespaceRole.builder();

                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
                    }

                    List<K8RolePolicyRule> policyRules = item.getRules().stream()
                            .map(rule -> K8RolePolicyRule.builder()
                                    .verbs(rule.getVerbs())
                                    .apiGroups(rule.getApiGroups())
                                    .resources(rule.getResources())
                                    .nonResourceURLs(rule.getNonResourceURLs())
                                    .resourceNames(rule.getResourceNames())
                                    .build())
                            .collect(Collectors.toList());

                    builder.k8RolePolicyRules(policyRules);

                    return builder.build();
                })
                .toList();

        k8NamespaceRoleRepository.saveAll(kubernetesNamespaceRoles);
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

                    builder.roleLevelType(RoleLevelType.NAMESPACE);
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
                    }
                    Optional.of(item.getRoleRef())
                            .ifPresent(roleRef -> builder.roleRef(
                                    K8RoleReference.builder()
                                            .kind(roleRef.getKind())
                                            .name(roleRef.getName())
                                            .apiGroup(roleRef.getApiGroup())
                                            .build()
                            ));
                    List<K8RbacSubject> subjects = item.getSubjects().stream()
                            .map(rbacV1Subject -> K8RbacSubject.builder()
                                    .kind(rbacV1Subject.getKind())
                                    .apiGroup(rbacV1Subject.getApiGroup())
                                    .name(rbacV1Subject.getName())
                                    .namespace(rbacV1Subject.getNamespace())
                                    .build())
                            .collect(Collectors.toList());

                    builder.rbacSubjects(subjects);
                    return builder.build();
                })
                .toList();

        k8RoleBindRepository.saveAll(kubernetesNamespaceRoleBinds);
    }

    private void fetchDeployments(String clusterId) throws ApiException {
        V1DeploymentList v1DeploymentList = this.appsV1Api.listDeploymentForAllNamespaces().execute();
        if (ObjectUtils.isEmpty(v1DeploymentList.getItems())) {
            log.warn(String.format("NO DEPLOYMENT(s) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8Deployment> k8Deployments = v1DeploymentList.getItems().stream()
                .filter(item -> item.getMetadata() != null)
                .map(item -> {
                    K8Deployment.K8DeploymentBuilder builder = K8Deployment.builder();
                    setMetadataFields(builder, item.getMetadata(), clusterId);
                    return builder.build();
                })
                .collect(Collectors.toList());

        k8DeploymentRepository.saveAll(k8Deployments);
    }

    private void fetchSecrets(String clusterId) throws ApiException {
        V1SecretList v1SecretList = this.coreV1Api.listSecretForAllNamespaces().execute();
        if (ObjectUtils.isEmpty(v1SecretList)) {
            log.warn(String.format("NO SECRET(s) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8Secret> kubernetesSecrets = v1SecretList.getItems().stream()
                .map(item -> {
                    K8Secret.K8SecretBuilder builder = K8Secret.builder();
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
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
            log.warn(String.format("NO SERVICE_ACCOUNT(s) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
            return;
        }
        List<K8ServiceAccount> kubernetesServiceAccounts = v1ServiceAccountList.getItems().stream()
                .map(item -> {
                    K8ServiceAccount.K8ServiceAccountBuilder builder = K8ServiceAccount.builder();
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
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
                    setMetadataFields(builder, item.getMetadata(), clusterId);
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
                    setMetadataFields(builder, item.getMetadata(), clusterId);
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
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
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
                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
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

                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
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
        builder.annotations(metadata.getAnnotations());
        builder.creationTimestamp(metadata.getCreationTimestamp());
        builder.deletionTimestamp(metadata.getDeletionTimestamp());
        builder.clusterId(clusterId);
        builder.cloudProviderType(this.cloudProviderType);
        builder.wsTenantName(this.wsTenantName);
        builder.cloudProviderType(this.cloudProviderType);
        builder.resourceAccountId(this.resourceAccountId);
    }

}
