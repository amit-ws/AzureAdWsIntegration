package com.ws.azureKuberntesJIT.service;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.exception.AzureDataException;
import com.ws.azureAdIntegration.exception.K8DataException;
import com.ws.azureAdIntegration.service.BackendApplicationLogservice;
import com.ws.azureAdIntegration.util.GenericUtil;
import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import com.ws.azureKuberntesJIT.constant.K8RoleBindingType;
import com.ws.azureKuberntesJIT.constant.K8RoleKind;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

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
    String clusterName;
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
    final K8ReplicaSetRepository k8ReplicaSetRepository;
    final K8StatefulSetRepository k8StatefulSetRepository;
    final K8DaemonSetRepository k8DaemonSetRepository;
    final K8JobRepository k8JobRepository;
    final K8CronJobRepository k8CronJobRepository;
    final K8IngressRepository k8IngressRepository;
    final K8ServiceRepository k8ServiceRepository;
    final BackendApplicationLogservice backendApplicationLogservice;

    @Autowired
    public K8ResourcesSyncService(K8RoleRepository k8RoleRepository, K8ConfigMapRepository
            k8ConfigMapRepository, K8CustomResourceDefinitionRepository k8CustomResourceDefinitionRepository,
                                  K8DeploymentRepository k8DeploymentRepository, K8NetworkPolicyRepository k8NetworkPolicyRepository,
                                  K8NodeRepository k8NodeRepository, K8RoleReferenceRepository k8RoleReferenceRepository, K8RoleBindRepository k8RoleBindRepository,
                                  K8SecretRepository k8SecretRepository, K8ServiceAccountRepository k8ServiceAccountRepository,
                                  K8NamespaceRepository k8NamespaceRepository,
                                  K8PersistentVolumeRepository k8PersistentVolumeRepository, K8PersistentVolumeClaimRepository k8PersistentVolumeClaimRepository,
                                  K8StorageClassRepository K8StorageClassRepository, K8ReplicaSetRepository k8ReplicaSetRepository, K8StatefulSetRepository k8StatefulSetRepository, K8DaemonSetRepository k8DaemonSetRepository, K8JobRepository k8JobRepository, K8CronJobRepository k8CronJobRepository, K8IngressRepository k8IngressRepository, K8ServiceRepository k8ServiceRepository, BackendApplicationLogservice backendApplicationLogservice
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
        this.k8ReplicaSetRepository = k8ReplicaSetRepository;
        this.k8StatefulSetRepository = k8StatefulSetRepository;
        this.k8DaemonSetRepository = k8DaemonSetRepository;
        this.k8JobRepository = k8JobRepository;
        this.k8CronJobRepository = k8CronJobRepository;
        this.k8IngressRepository = k8IngressRepository;
        this.k8ServiceRepository = k8ServiceRepository;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncKubernetesData(K8ResourceDataSyncRequest k8ResourceDataSyncRequest) {
        if (ObjectUtils.isEmpty(k8ResourceDataSyncRequest)) {
            throw new K8DataException("Please provide payload to start K8 resources data sync");
        }
        this.cloudProviderType = k8ResourceDataSyncRequest.getCloudProviderType();
        this.wsTenantName = k8ResourceDataSyncRequest.getWsTenantName();
        this.tenantEmail = k8ResourceDataSyncRequest.getTenantEmail();
        this.resourceAccountId = k8ResourceDataSyncRequest.getResourceAccountId();

        for (ClusterConfigurationRequest configuration : k8ResourceDataSyncRequest.getConfigurations()) {
            this.clusterName = configuration.getClusterName();
            String clusterURL = configuration.getServer();
            String clusterToken = configuration.getToken();
            initializeK8Client(clusterURL, clusterToken);
            log.info("K8 client initialized successfully for cluster name: {}", this.clusterName);
            initializeK8sApis();
            log.info("K8 APIs initialized successfully for cluster name: {}", this.clusterName);
            log.info("K8 data sync STARTED for cluster: {}", this.clusterName);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.AZURE_KUBERNETES_RESOURCE_DATA_SYNC_STARTED, this.clusterName, cloudProviderType, LocalDateTime.now()), "Info");
            executeSync(configuration.getClusterId());
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.AZURE_KUBERNETES_RESOURCE_DATA_SYNC_ENDED, this.clusterName, cloudProviderType, LocalDateTime.now()), "Info");
            log.info("K8 data sync ENDED for cluster: {}", this.clusterName);
        }
    }

    public void executeSync(String clusterId, String clusterName, String url, String token, CloudProviderType cloudProviderType) {
        this.cloudProviderType = cloudProviderType;
        this.clusterName = clusterName;
        log.info("url: {}", url);
        log.info("token: {}", token);
        initializeK8Client(url, token);
        initializeK8RbackApi();
        log.info("K8 data sync STARTED for cluster ID: {}", clusterId);
        syncClusterRolesAndBindings(clusterId);
        syncNamespaceRolesAndBindings(clusterId);
        log.info("K8 data sync ENDED for cluster ID: {}", clusterId);
    }

    private void syncClusterRolesAndBindings(String clusterId) {
        backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_CLUSTER_ROLE_AND_BINDING_DATA_SYNC, "STARTED", this.clusterName, cloudProviderType, LocalDateTime.now()), "Info");
        fetchClusterRoles(clusterId);
        fetchClusterRoleBinding(clusterId);
        backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_CLUSTER_ROLE_AND_BINDING_DATA_SYNC, "ENDED", this.clusterName, cloudProviderType, LocalDateTime.now()), "Info");
    }

    private void syncNamespaceRolesAndBindings(String clusterId) {
        backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_NAMESPACE_ROLE_AND_BINDING_DATA_SYNC, "STARTED", this.clusterName, cloudProviderType, LocalDateTime.now()), "Info");
        fetchNamespaceRoles(clusterId);
        fetchNamespaceRoleBinding(clusterId);
        backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_NAMESPACE_ROLE_AND_BINDING_DATA_SYNC, "ENDED", this.clusterName, cloudProviderType, LocalDateTime.now()), "Info");
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

    private void initializeK8sApis() {
        this.coreV1Api = new CoreV1Api();
        this.appsV1Api = new AppsV1Api();
        this.batchApi = new BatchV1Api();
        this.storageV1Api = new StorageV1Api();
        this.networkingApi = new NetworkingV1Api();
        this.rbacApi = new RbacAuthorizationV1Api();
        this.apiextensionsV1Api = new ApiextensionsV1Api();
    }

    private void initializeK8RbackApi() {
        this.rbacApi = new RbacAuthorizationV1Api();
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
            fetchReplicaSets(clusterId);
            fetchStateFulsSets(clusterId);
            fetchDaemonSets(clusterId);
            fetchJobs(clusterId);
            fetchCronJobs(clusterId);
            fetchIngresses(clusterId);
            fetchServices(clusterId);
        } catch (Exception ex) {
            log.error("Error occurred in syncing data from Kubernetes: {}", ex.getMessage());
            throw new RuntimeException(ex.getMessage());
        }
    }


    private void fetchNamespace(String clusterId) throws ApiException {
        try {
            V1NamespaceList v1NamespaceList = this.coreV1Api.listNamespace().execute();
            if (ObjectUtils.isEmpty(v1NamespaceList)) {
                throw new AzureDataException("No NAMESPACE(s) found");
            }
            String apiVersion = v1NamespaceList.getApiVersion();
            List<K8Namespace> kubernetesNamespaces = v1NamespaceList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8Namespace k8Namespace = K8Namespace.builder()
                                .phase(GenericUtil.getOrNull(() -> Objects.requireNonNull(item.getStatus()).getPhase()))
                                .build();
                        setMetadata(k8Namespace, item.getMetadata(), apiVersion, clusterId);
                        return k8Namespace;
                    })
                    .toList();
            k8NamespaceRepository.saveAll(kubernetesNamespaces);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_NAMESPACE_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1NamespaceList.class.getName(), ignored.getMessage()));
            throw new RuntimeException(ignored.getMessage());
        }
    }

    private void fetchNodes(String clusterId) {
        try {
            V1NodeList v1NodeList = this.coreV1Api.listNode().execute();
            if (ObjectUtils.isEmpty(v1NodeList)) {
                log.warn(String.format("No NODE(s) found for cluster name: %S of cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = v1NodeList.getApiVersion();
            List<K8Node> kubernetesNodes = v1NodeList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8Node k8Node = K8Node.builder()
                                .phase(GenericUtil.getOrNull(() -> Objects.requireNonNull(item.getStatus()).getPhase()))
                                .externalID(GenericUtil.getOrNull(() -> Objects.requireNonNull(item.getSpec()).getExternalID()))
                                .podCIDR(GenericUtil.getOrNull(() -> Objects.requireNonNull(item.getSpec()).getPodCIDR()))
                                .unschedulable(GenericUtil.getOrNull(() -> Objects.requireNonNull(item.getSpec()).getUnschedulable()))
                                .providerID(GenericUtil.getOrNull(() -> Objects.requireNonNull(item.getSpec()).getProviderID()))
                                .build();

                        setMetadata(k8Node, item.getMetadata(), apiVersion, clusterId);
                        return k8Node;
                    })
                    .toList();
            k8NodeRepository.saveAll(kubernetesNodes);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_NODE_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1NodeList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchCustomResourceDefinition(String clusterId) {
        try {
            V1CustomResourceDefinitionList v1CustomResourceDefinitionList = this.apiextensionsV1Api.listCustomResourceDefinition().execute();
            if (ObjectUtils.isEmpty(v1CustomResourceDefinitionList)) {
                log.warn(String.format("No CustomResourceDefinition(s) found for cluster name: %S of cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = v1CustomResourceDefinitionList.getApiVersion();
            List<K8CustomResourceDefinition> k8CustomResourceDefinitions = v1CustomResourceDefinitionList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8CustomResourceDefinition k8CustomResourceDefinition = K8CustomResourceDefinition.builder().build();
                        setMetadata(k8CustomResourceDefinition, item.getMetadata(), apiVersion, clusterId);
                        return k8CustomResourceDefinition;
                    })
                    .collect(Collectors.toList());

            k8CustomResourceDefinitionRepository.saveAll(k8CustomResourceDefinitions);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_CUSTOM_RESOURCE_DEFINITION_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1CustomResourceDefinitionList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchClusterRoles(String clusterId) {
        try {
            V1ClusterRoleList v1ClusterRoleList = this.rbacApi.listClusterRole().execute();
            if (ObjectUtils.isEmpty(v1ClusterRoleList)) {
                log.warn(String.format("No CLUSTER_ROLE(s) found for cluster name: %S of cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = v1ClusterRoleList.getApiVersion();
            List<K8Role> k8ClusterRoles = v1ClusterRoleList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8Role clusterRole = K8Role.builder()
                                .roleLevel(K8ResourceLevel.CLUSTER)
                                .roleKind(K8RoleKind.ClusterRole)
                                .build();

                        setMetadata(clusterRole, item.getMetadata(), apiVersion, clusterId);

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

                        clusterRole.setK8RolePolicyRules(policyRules);
                        return clusterRole;
                    })
                    .collect(Collectors.toList());

            k8RoleRepository.saveAll(k8ClusterRoles);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_CLUSTER_ROLE_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1ClusterRoleList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchClusterRoleBinding(String clusterId) {
        try {
            V1ClusterRoleBindingList v1ClusterRoleBindingList = this.rbacApi.listClusterRoleBinding().execute();
            if (ObjectUtils.isEmpty(v1ClusterRoleBindingList)) {
                log.warn(String.format("NO CLUSTER_ROLE_BINDING found for cluster name: %s or cloud type: %s", this.clusterName, this.cloudProviderType));
            }
            String apiVersion = v1ClusterRoleBindingList.getApiVersion();
            List<K8RoleBind> kubernetesRoleBinds = v1ClusterRoleBindingList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
//                        log.info("------------");
//                        log.info("item.getKind() : {}", item.getKind());
//                        log.info("item.getApiVersion(): {}", item.getApiVersion());
//                        log.info("------------");
                        K8RoleBind clusterRoleBind = K8RoleBind.builder()
                                .kind(K8RoleBindingType.ClusterRoleBinding.name())
                                .level(K8ResourceLevel.CLUSTER)
                                .bindingType(K8RoleBindingType.ClusterRoleBinding)
                                .roleRef(k8RoleReferenceRepository.save(convertToK8RoleReference(item.getRoleRef(), clusterId, item.getMetadata().getUid())))
                                .build();

                        setMetadata(clusterRoleBind, item.getMetadata(), apiVersion, clusterId);
                        clusterRoleBind.setRbacSubjects(convertToK8RbacSubjects(item.getSubjects(), clusterId, clusterRoleBind));
                        return clusterRoleBind;
                    })
                    .toList();

            k8RoleBindRepository.saveAll(kubernetesRoleBinds);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_CLUSTER_ROLE_BINDING_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1ClusterRoleBindingList.class.getName(), ignored.getMessage()));
        }
    }

    private K8RoleReference convertToK8RoleReference(V1RoleRef roleRef, String clusterId, String roleUID) {
        return Optional.ofNullable(roleRef)
                .map(ref -> K8RoleReference.builder()
                        .apiGroup(ref.getApiGroup())
                        .kind(ref.getKind())
                        .name(ref.getName())
                        .roleUID(roleUID)  /* it is the role binding uid */
                        .clusterId(clusterId)
                        .cloudProviderType(this.cloudProviderType)
                        .cloudResourceAccountId(this.resourceAccountId)
                        .wsTenantName(this.wsTenantName)
                        .build())
                .orElse(null);
    }

    private List<K8RbacSubject> convertToK8RbacSubjects(List<RbacV1Subject> subjects, String clusterId, K8RoleBind k8RoleBind) {
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
                        .kubernetesRoleBind(k8RoleBind)
                        .build())
                .collect(Collectors.toList());
    }


    private void fetchNamespaceRoles(String clusterId) {
        try {
            V1RoleList v1RoleList = rbacApi.listRoleForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(v1RoleList)) {
                log.warn(String.format("NO NAMESPACE_ROLE(s) found for cluster name: %s or cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = v1RoleList.getApiVersion();
            List<K8Role> kubernetesNamespaceRoles = v1RoleList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8Role namespaceRole = K8Role.builder()
                                .roleLevel(K8ResourceLevel.NAMESPACE)
                                .roleKind(K8RoleKind.Role)
                                .build();
                        setMetadata(namespaceRole, item.getMetadata(), apiVersion, clusterId);

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
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_NAMESPACE_ROLE_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1RoleList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchNamespaceRoleBinding(String clusterId) {
        try {
            V1RoleBindingList v1RoleBindingList = rbacApi.listRoleBindingForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(v1RoleBindingList)) {
                log.warn(String.format("NO NAMESPACE_ROLE_BINDING found for cluster name: %s or cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = v1RoleBindingList.getApiVersion();
            List<K8RoleBind> kubernetesNamespaceRoleBinds = v1RoleBindingList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8RoleBind namespaceRoleBind = K8RoleBind.builder()
                                .kind(K8RoleBindingType.RoleBinding.name())
                                .level(K8ResourceLevel.NAMESPACE)
                                .bindingType(K8RoleBindingType.RoleBinding)
                                .roleRef(k8RoleReferenceRepository.save(convertToK8RoleReference(item.getRoleRef(), clusterId, item.getMetadata().getUid())))
                                .build();
                        setMetadata(namespaceRoleBind, item.getMetadata(), apiVersion, clusterId);
                        namespaceRoleBind.setRbacSubjects(convertToK8RbacSubjects(item.getSubjects(), clusterId, namespaceRoleBind));
                        return namespaceRoleBind;
                    })
                    .toList();

            k8RoleBindRepository.saveAll(kubernetesNamespaceRoleBinds);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_NAMESPACE_ROLE_BINDING_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1RoleBindingList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchDeployments(String clusterId) {
        try {
            V1DeploymentList v1DeploymentList = this.appsV1Api.listDeploymentForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(v1DeploymentList.getItems())) {
                log.warn(String.format("NO DEPLOYMENT(s) found for cluster id: %s of cloud type: %s", clusterId, this.cloudProviderType));
                return;
            }
            String apiVersion = v1DeploymentList.getApiVersion();
            List<K8Deployment> k8Deployments = v1DeploymentList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8Deployment k8Deployment = K8Deployment.builder().build();
                        setMetadata(k8Deployment, item.getMetadata(), apiVersion, clusterId);
                        return k8Deployment;
                    })
                    .collect(Collectors.toList());

            k8DeploymentRepository.saveAll(k8Deployments);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_DEPLOYMENT_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1DeploymentList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchSecrets(String clusterId) {
        try {
            V1SecretList v1SecretList = this.coreV1Api.listSecretForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(v1SecretList)) {
                log.warn(String.format("NO SECRET(s) found for cluster name: %s of cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = v1SecretList.getApiVersion();
            List<K8Secret> kubernetesSecrets = v1SecretList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8Secret k8Secret = K8Secret.builder()
                                .type(item.getType())
                                .immutable(item.getImmutable())
                                .stringData(item.getStringData())
                                .build();
                        setMetadata(k8Secret, item.getMetadata(), apiVersion, clusterId);
                        return k8Secret;
                    })
                    .toList();

            k8SecretRepository.saveAll(kubernetesSecrets);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_SECRET_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1SecretList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchServiceAccounts(String clusterId) {
        try {
            V1ServiceAccountList v1ServiceAccountList = coreV1Api.listServiceAccountForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(v1ServiceAccountList)) {
                log.warn(String.format("NO SERVICE_ACCOUNT(s) found for cluster id: %s of cloud type: %s", clusterId, this.cloudProviderType));
                return;
            }
            String apiVersion = v1ServiceAccountList.getApiVersion();
            List<K8ServiceAccount> kubernetesServiceAccounts = v1ServiceAccountList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8ServiceAccount k8ServiceAccount = K8ServiceAccount.builder()
                                .automountServiceAccountToken(item.getAutomountServiceAccountToken())
                                .build();
                        setMetadata(k8ServiceAccount, item.getMetadata(), apiVersion, clusterId);
                        return k8ServiceAccount;
                    })
                    .toList();
            k8ServiceAccountRepository.saveAll(kubernetesServiceAccounts);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_SERVICE_ACCOUNT_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1ServiceAccountList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchPersistentVolumes(String clusterId) {
        try {
            V1PersistentVolumeList v1PersistentVolumeList = this.coreV1Api.listPersistentVolume().execute();
            if (ObjectUtils.isEmpty(v1PersistentVolumeList)) {
                log.warn(String.format("NO PERSISTENT_VOLUME(s) found for cluster name: %s or cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = v1PersistentVolumeList.getApiVersion();
            List<K8PersistentVolume> persistentVolumes = v1PersistentVolumeList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8PersistentVolume k8PersistentVolume = K8PersistentVolume.builder().build();
                        setMetadata(k8PersistentVolume, item.getMetadata(), apiVersion, clusterId);
                        return k8PersistentVolume;
                    })
                    .toList();

            k8PersistentVolumeRepository.saveAll(persistentVolumes);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_PERSISTENT_VOLUME_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1PersistentVolumeList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchPersistentVolumeClaims(String clusterId) {
        try {
            V1PersistentVolumeClaimList v1PersistentVolumeClaimList = this.coreV1Api.listPersistentVolumeClaimForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(v1PersistentVolumeClaimList)) {
                log.warn(String.format("NO PERSISTENT_VOLUME_CLAIM(s) found for cluster name: %s or cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = v1PersistentVolumeClaimList.getApiVersion();
            List<K8PersistentVolumeClaim> persistentVolumeClaims = v1PersistentVolumeClaimList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8PersistentVolumeClaim k8PersistentVolumeClaim = K8PersistentVolumeClaim.builder().build();
                        setMetadata(k8PersistentVolumeClaim, item.getMetadata(), apiVersion, clusterId);
                        return k8PersistentVolumeClaim;
                    })
                    .toList();

            k8PersistentVolumeClaimRepository.saveAll(persistentVolumeClaims);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_PERSISTENT_VOLUME_CLAIM_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1PersistentVolumeClaimList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchStorageClasses(String clusterId) {
        try {
            V1StorageClassList v1StorageClassList = this.storageV1Api.listStorageClass().execute();
            if (ObjectUtils.isEmpty(v1StorageClassList)) {
                log.warn(String.format("NO STORAE_CLASS(Es) found for cluster name: %s or cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = v1StorageClassList.getApiVersion();
            List<K8StorageClass> kubernetesStorageClasses = v1StorageClassList.getItems().stream()
                    .map(item -> {
                        K8StorageClass k8StorageClass = K8StorageClass.builder()
                                .provisioner(item.getProvisioner())
                                .volumeBindingMode(item.getVolumeBindingMode())
                                .allowVolumeExpansion(item.getAllowVolumeExpansion())
                                .reclaimPolicy(item.getReclaimPolicy())
                                .build();

                        if (!ObjectUtils.isEmpty(item.getMetadata())) {
                            setMetadata(k8StorageClass, item.getMetadata(), apiVersion, clusterId);
                        }
                        return k8StorageClass;
                    })
                    .toList();

            K8StorageClassRepository.saveAll(kubernetesStorageClasses);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_STORAGE_CLASS_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1StorageClassList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchConfigMap(String clusterId) {
        try {
            V1ConfigMapList v1ConfigMapList = this.coreV1Api.listConfigMapForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(v1ConfigMapList)) {
                log.warn(String.format("NO CONFIG_MAP(Es) found for cluster name: %s or cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = v1ConfigMapList.getApiVersion();
            List<K8ConfigMap> configMaps = v1ConfigMapList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8ConfigMap k8ConfigMap = K8ConfigMap.builder()
                                .immutable(item.getImmutable())
                                .build();
                        setMetadata(k8ConfigMap, item.getMetadata(), apiVersion, clusterId);
                        return k8ConfigMap;
                    })
                    .toList();

            k8ConfigMapRepository.saveAll(configMaps);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_CONFIG_MAP_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1ConfigMapList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchNetworkPolicies(String clusterId) {
        try {
            V1NetworkPolicyList v1NetworkPolicyList = this.networkingApi.listNetworkPolicyForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(v1NetworkPolicyList)) {
                log.warn(String.format("NO NETWORK_POLICIES found for cluster name: %s or cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = v1NetworkPolicyList.getApiVersion();
            List<K8NetworkPolicy> kubernetesNetworkPolicies = v1NetworkPolicyList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8NetworkPolicy k8NetworkPolicy = K8NetworkPolicy.builder().build();
                        setMetadata(k8NetworkPolicy, item.getMetadata(), apiVersion, clusterId);
                        return k8NetworkPolicy;
                    })
                    .toList();

            k8NetworkPolicyRepository.saveAll(kubernetesNetworkPolicies);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_NETWORK_POLICY_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1NetworkPolicyList.class.getName(), ignored.getMessage()));
        }
    }


    private void fetchReplicaSets(String clusterId) {
        try {
            V1ReplicaSetList replicaSets = this.appsV1Api.listReplicaSetForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(replicaSets)) {
                log.warn(String.format("No REPLICASET(s) found for cluster name: %S of cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = replicaSets.getApiVersion();
            List<K8ReplicaSet> k8ReplicaSets = replicaSets.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8ReplicaSet k8ReplicaSet = K8ReplicaSet.builder().build();
                        setMetadata(k8ReplicaSet, item.getMetadata(), apiVersion, clusterId);
//                if (org.apache.commons.lang3.ObjectUtils.isNotEmpty(item.getStatus())) {
//                    V1ReplicaSetStatus v1ReplicaSetStatus = item.getStatus();
//                    k8ReplicaSet.setFullyLabeledReplicas(v1ReplicaSetStatus.getFullyLabeledReplicas());
//                    K8PodManagementControllerStatus status = createK8PodManagementControllerStatus(v1ReplicaSetStatus.getObservedGeneration(), v1ReplicaSetStatus.getReadyReplicas(),
//                            v1ReplicaSetStatus.getReplicas(), v1ReplicaSetStatus.getAvailableReplicas());
//                    k8ReplicaSet.setReplicaSetStatus(status);
//                }
                        return k8ReplicaSet;
                    }).toList();

            k8ReplicaSetRepository.saveAll(k8ReplicaSets);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_REPLICASET_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1ReplicaSetList.class.getName(), ignored.getMessage()));
            throw new RuntimeException(ignored.getMessage());
        }
    }

    private void fetchStateFulsSets(String clusterId) {
        try {
            V1StatefulSetList statefulSets = this.appsV1Api.listStatefulSetForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(statefulSets)) {
                log.warn(String.format("No STATEFULSET(s) found for cluster name: %S of cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = statefulSets.getApiVersion();
            List<K8StatefulSet> k8StatefulSets = statefulSets.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8StatefulSet k8StatefulSet = K8StatefulSet.builder().build();
                        setMetadata(k8StatefulSet, item.getMetadata(), apiVersion, clusterId);
//                if (org.apache.commons.lang3.ObjectUtils.isNotEmpty(item.getStatus())) {
//                    V1StatefulSetStatus v1StatefulSetStatus = item.getStatus();
//                    k8StatefulSet.setCurrentReplicas(v1StatefulSetStatus.getCurrentReplicas());
//                    k8StatefulSet.setCurrentRevision(v1StatefulSetStatus.getCurrentRevision());
//                    k8StatefulSet.setReadyReplicas(v1StatefulSetStatus.getReadyReplicas());
//                    k8StatefulSet.setUpdatedReplicas(v1StatefulSetStatus.getUpdatedReplicas());
//                    k8StatefulSet.setUpdateRevision(v1StatefulSetStatus.getUpdateRevision());
//                    k8StatefulSet.setCollisionCount(v1StatefulSetStatus.getCollisionCount());
//                    K8PodManagementControllerStatus status = createK8PodManagementControllerStatus(v1StatefulSetStatus.getObservedGeneration(), v1StatefulSetStatus.getReadyReplicas(),
//                            v1StatefulSetStatus.getReplicas(), v1StatefulSetStatus.getAvailableReplicas());
//                    k8StatefulSet.setStatefulStatus(status);
//                }
                        return k8StatefulSet;
                    }).toList();

            k8StatefulSetRepository.saveAll(k8StatefulSets);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_STATEFULSET_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1StatefulSetList.class.getName(), ignored.getMessage()));
            throw new RuntimeException(ignored.getMessage());
        }
    }


    private void fetchDaemonSets(String clusterId) {
        try {
            V1DaemonSetList daemonSetList = this.appsV1Api.listDaemonSetForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(daemonSetList)) {
                log.warn(String.format("No DAEMONSET(s) found for cluster name: %S of cloud type: %s", this.clusterName, this.cloudProviderType));
                return;
            }
            String apiVersion = daemonSetList.getApiVersion();
            List<K8DaemonSet> k8DaemonSets = daemonSetList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8DaemonSet k8DaemonSet = K8DaemonSet.builder().build();
                        setMetadata(k8DaemonSet, item.getMetadata(), apiVersion, clusterId);

//                if (org.apache.commons.lang3.ObjectUtils.isNotEmpty(item.getStatus())) {
//                    V1DaemonSetStatus v1StatefulSetStatus = item.getStatus();
//                    k8DaemonSet.setCurrentNumberScheduled(v1StatefulSetStatus.getCurrentNumberScheduled());
//                    k8DaemonSet.setDesiredNumberScheduled(v1StatefulSetStatus.getDesiredNumberScheduled());
//                    k8DaemonSet.setNumberAvailable(v1StatefulSetStatus.getNumberAvailable());
//                    k8DaemonSet.setNumberMisscheduled(v1StatefulSetStatus.getNumberMisscheduled());
//                    k8DaemonSet.setNumberReady(v1StatefulSetStatus.getNumberReady());
//                    k8DaemonSet.setNumberAvailable(v1StatefulSetStatus.getNumberAvailable());
//                    k8DaemonSet.setUpdatedNumberScheduled(v1StatefulSetStatus.getUpdatedNumberScheduled());
//                    k8DaemonSet.setCollisionCount(v1StatefulSetStatus.getCollisionCount());
//                    k8DaemonSet.setObservedGeneration(v1StatefulSetStatus.getObservedGeneration());
//                }
                        return k8DaemonSet;
                    }).toList();
            k8DaemonSetRepository.saveAll(k8DaemonSets);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_DAEMONSET_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1DaemonSetList.class.getName(), ignored.getMessage()));
            throw new RuntimeException(ignored.getMessage());
        }
    }

    private void fetchJobs(String clusterId) throws ApiException {
        try {
            V1JobList v1JobList = this.batchApi.listJobForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(v1JobList)) {
                log.warn(String.format("No JOB(s) found for cluster name: %S of cloud type: %s", this.clusterName, this.cloudProviderType));
            }
            String apiVersion = v1JobList.getApiVersion();
            List<K8Job> k8Jobs = v1JobList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8Job k8Job = K8Job.builder().build();
                        setMetadata(k8Job, item.getMetadata(), apiVersion, clusterId);
                        return k8Job;
                    })
                    .toList();
            k8JobRepository.saveAll(k8Jobs);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_JOB_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1JobList.class.getName(), ignored.getMessage()));
        }
    }


    private void fetchCronJobs(String clusterId) throws ApiException {
        try {
            V1CronJobList v1CronJobList = batchApi.listCronJobForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(v1CronJobList)) {
                log.warn(String.format("No CRON JOB(s) found for cluster name: %S of cloud type: %s", this.clusterName, this.cloudProviderType));
            }
            String apiVersion = v1CronJobList.getApiVersion();
            List<K8CronJob> cronJobs = v1CronJobList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8CronJob cronJob = K8CronJob.builder().build();
                        setMetadata(cronJob, item.getMetadata(), apiVersion, clusterId);
                        return cronJob;
                    })
                    .toList();
            k8CronJobRepository.saveAll(cronJobs);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_CRON_JOB_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1CronJobList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchIngresses(String clusterId) throws ApiException {
        try {
            V1IngressList v1IngressList = this.networkingApi.listIngressForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(v1IngressList)) {
                log.warn(String.format("No INGRESS(s) found for cluster name: %S of cloud type: %s", this.clusterName, this.cloudProviderType));
            }
            String apiVersion = v1IngressList.getApiVersion();
            List<K8Ingress> k8Ingresses = v1IngressList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8Ingress k8Ingress = K8Ingress.builder().build();
                        setMetadata(k8Ingress, item.getMetadata(), apiVersion, clusterId);
                        return k8Ingress;
                    })
                    .toList();
            k8IngressRepository.saveAll(k8Ingresses);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_INGRESS_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1IngressList.class.getName(), ignored.getMessage()));
        }
    }

    private void fetchServices(String clusterId) {
        try {
            V1ServiceList v1ServiceList = this.coreV1Api.listServiceForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(v1ServiceList)) {
                log.warn(String.format("No SERVICE(s) found for cluster name: %S of cloud type: %s", this.clusterName, this.cloudProviderType));
            }
            String apiVersion = v1ServiceList.getApiVersion();
            List<K8Service> k8Services = v1ServiceList.getItems().stream()
                    .filter(item -> item.getMetadata() != null)
                    .map(item -> {
                        K8Service k8Service = K8Service.builder().build();
                        setMetadata(k8Service, item.getMetadata(), apiVersion, clusterId);
                        return k8Service;
                    })
                    .toList();
            k8ServiceRepository.saveAll(k8Services);
            backendApplicationLogservice.saveAuditLog(this.wsTenantName, this.tenantEmail, Constant.ADD, String.format(Constant.KUBERNETES_SERVICES_DATA_SYNCED, this.clusterName, this.cloudProviderType), "Info");
        } catch (Exception ignored) {
            log.error(String.format("Error in syncing %s with message: %s", V1ServiceList.class.getName(), ignored.getMessage()));
        }
    }


    private void setMetadata(K8Metadata k8Metadata, V1ObjectMeta metadata, String apiVersion, String clusterId) {
        k8Metadata.setKind(k8Metadata.getKind());
        k8Metadata.setApiVersion(apiVersion);
        k8Metadata.setSelfLink(metadata.getSelfLink());
        k8Metadata.setResourceVersion(metadata.getResourceVersion());
        k8Metadata.setGeneration(metadata.getGeneration());
        k8Metadata.setName(metadata.getName());
        k8Metadata.setUid(metadata.getUid());
        k8Metadata.setNamespace(metadata.getNamespace());
        k8Metadata.setGenerateName(metadata.getGenerateName());
        k8Metadata.setCreationTimestamp(metadata.getCreationTimestamp());
        k8Metadata.setDeletionTimestamp(metadata.getDeletionTimestamp());
        k8Metadata.setSyncedAt(new Date());
        k8Metadata.setClusterId(clusterId);
        k8Metadata.setCloudProviderType(this.cloudProviderType);
        k8Metadata.setWsTenantName(this.wsTenantName);
        k8Metadata.setCloudResourceAccountId(this.resourceAccountId);
    }


//    private void setMetadataFields(K8Metadata.K8MetadataBuilder builder, V1ObjectMeta metadata, String clusterId) {
//        builder.selfLink(metadata.getSelfLink());
//        builder.resourceVersion(metadata.getResourceVersion());
//        builder.generation(metadata.getGeneration());
//        builder.name(metadata.getName());
//        builder.uid(metadata.getUid());
//        builder.namespace(metadata.getNamespace());
//        builder.generateName(metadata.getGenerateName());
//        builder.creationTimestamp(metadata.getCreationTimestamp());
//        builder.deletionTimestamp(metadata.getDeletionTimestamp());
//        builder.clusterId(clusterId);
//        builder.cloudProviderType(this.cloudProviderType);
//        builder.wsTenantName(this.wsTenantName);
//        builder.cloudProviderType(this.cloudProviderType);
//        builder.cloudResourceAccountId(this.resourceAccountId);
//    }


//    private void createAndSaveK8ResourceAnnotations(Map<String, String> annotationsMap,
//                                                    K8ResourceType type, String ClusterId, String kubernetesResourceId) {
//        List<K8ResourceAnnotation> k8ResourceAnnotations = new ArrayList<>();
//        for (Map.Entry<String, String> entry : annotationsMap.entrySet()) {
//            K8ResourceAnnotation annotation = K8ResourceAnnotation.builder()
//                    .key("entry.getKey()")
//                    .value("entry.getValue()")
//                    .k8ResourceType(type)
//                    .kubernetesResourceId(kubernetesResourceId)
//                    .clusterId(ClusterId)
//                    .resourceAccountId(this.resourceAccountId)
//                    .cloudProviderType(this.cloudProviderType)
//                    .wsTenantName(this.wsTenantName)
//                    .build();
//
//            k8ResourceAnnotations.add(annotation);
//        }
//        k8ResourceAnnotationRepository.saveAll(k8ResourceAnnotations);
//    }


//    private void initializeK8Client(String kubeConfig) {
//        try {
//            ApiClient client = Config.fromConfig(new StringReader(kubeConfig));
//            Configuration.setDefaultApiClient(client);
//        } catch (Exception ex) {
//            log.error("Error in initializing k8 client");
//            log.error("Error: {}", ex.getMessage());
//            throw new RuntimeException(ex.getMessage());
//        }
//    }

    //    private K8PodManagementControllerStatus createK8PodManagementControllerStatus(Long observedGeneration, Integer readyReplicas,
//                                                                                  Integer replicas, Integer availableReplicas) {
//        return K8PodManagementControllerStatus.builder()
//                .observedGeneration(observedGeneration)
//                .readyReplicas(readyReplicas)
//                .replicas(replicas)
//                .availableReplicas(availableReplicas)
//                .build();
//    }
}




