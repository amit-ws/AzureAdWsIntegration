package com.ws.azureKuberntesJIT.service;


import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.exception.K8ResourceException;
import com.ws.azureKuberntesJIT.constant.K8ResourceType;
import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import com.ws.azureKuberntesJIT.dto.K8ResourceRequest;
import com.ws.azureKuberntesJIT.dto.K8RolePolicyRuleDTO;
import com.ws.azureKuberntesJIT.enttity.K8IngressRepository;
import com.ws.azureKuberntesJIT.enttity.K8RolePolicyRule;
import com.ws.azureKuberntesJIT.repository.*;
import com.ws.azureKuberntesJIT.response.K8RoleResponse;
import com.ws.azureResourcesIntegration.repository.PublishedResourcesRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class K8ResourceService {
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


    @Autowired
    public K8ResourceService(K8StorageClassRepository k8StorageClassRepository, K8PersistentVolumeRepository k8PersistentVolumeRepository, K8NamespaceRepository k8NamespaceRepository, K8NodeRepository k8NodeRepository,
                             K8DeploymentRepository k8DeploymentRepository, K8ServiceAccountRepository k8ServiceAccountRepository,
                             K8SecretRepository k8SecretRepository, K8ConfigMapRepository k8ConfigMapRepository,
                             K8CustomResourceDefinitionRepository k8CustomResourceDefinitionRepository, K8NetworkPolicyRepository k8NetworkPolicyRepository,
                             K8RoleRepository k8RoleRepository, K8PolicyRuleRepository k8PolicyRuleRepository, K8ReplicaSetRepository k8ReplicaSetRepository,
                             K8StatefulSetRepository k8StatefulSetRepository, K8DaemonSetRepository k8DaemonSetRepository, K8JobRepository k8JobRepository,
                             K8CronJobRepository k8CronJobRepository, K8IngressRepository k8IngressRepository, K8ServiceRepository k8ServiceRepository,
                             PublishedResourcesRepository publishedResourcesRepository) {
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
    }

    @Transactional(readOnly = true)
    public List<?> getK8Resources(K8ResourceRequest request, K8ResourceLevel resourceLevel) {
        String wsTenantName = request.getWsTenantName().trim();
        String clusterId = request.getClusterId().trim();
        CloudProviderType cloudProviderType = request.getCloudProviderType();
        K8ResourceType type = request.getType();
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
}




















