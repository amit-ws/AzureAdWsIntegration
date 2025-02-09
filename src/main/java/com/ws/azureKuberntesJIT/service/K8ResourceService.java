package com.ws.azureKuberntesJIT.service;


import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.exception.AzureDataException;
import com.ws.azureAdIntegration.util.AzureEntityUtil;
import com.ws.azureKuberntesJIT.constant.K8ResourceType;
import com.ws.azureKuberntesJIT.constant.RoleLevelType;
import com.ws.azureKuberntesJIT.projection.K8RoleProjection;
import com.ws.azureKuberntesJIT.repository.*;
import com.ws.azureKuberntesJIT.response.K8RoleResponse;
import com.ws.azureResourcesIntegration.dto.PublishResourceRequest;
import com.ws.azureResourcesIntegration.repository.PublishedResourcesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class K8ResourceService {
    final K8NamespaceRepository k8NamespaceRepository;
    final K8NodeRepository k8NodeRepository;
    final K8DeploymentRepository k8DeploymentRepository;
    final K8ServiceAccountRepository k8ServiceAccountRepository;
    final K8SecretRepository k8SecretRepository;
    final K8ConfigMapRepository k8ConfigMapRepository;
    final K8CustomResourceDefinitionRepository k8CustomResourceDefinitionRepository;
    final K8NetworkPolicyRepository k8NetworkPolicyRepository;
    final K8ClusterRoleRepository clusterRoleRepository;
    final K8NamespaceRoleRepository namespaceRoleRepository;
    final PublishedResourcesRepository publishedResourcesRepository;


    @Autowired
    public K8ResourceService(K8NamespaceRepository k8NamespaceRepository, K8NodeRepository k8NodeRepository,
                             K8DeploymentRepository k8DeploymentRepository, K8ServiceAccountRepository k8ServiceAccountRepository,
                             K8SecretRepository k8SecretRepository, K8ConfigMapRepository k8ConfigMapRepository,
                             K8CustomResourceDefinitionRepository k8CustomResourceDefinitionRepository, K8NetworkPolicyRepository k8NetworkPolicyRepository, K8ClusterRoleRepository clusterRoleRepository, K8NamespaceRoleRepository namespaceRoleRepository, PublishedResourcesRepository publishedResourcesRepository) {
        this.k8NamespaceRepository = k8NamespaceRepository;
        this.k8NodeRepository = k8NodeRepository;
        this.k8DeploymentRepository = k8DeploymentRepository;
        this.k8ServiceAccountRepository = k8ServiceAccountRepository;
        this.k8SecretRepository = k8SecretRepository;
        this.k8ConfigMapRepository = k8ConfigMapRepository;
        this.k8CustomResourceDefinitionRepository = k8CustomResourceDefinitionRepository;
        this.k8NetworkPolicyRepository = k8NetworkPolicyRepository;
        this.clusterRoleRepository = clusterRoleRepository;
        this.namespaceRoleRepository = namespaceRoleRepository;
        this.publishedResourcesRepository = publishedResourcesRepository;
    }


    public List<?> getK8Resources(String wsTenantName, CloudProviderType cloudProviderType, K8ResourceType type) {
        return switch (type) {
            case NAMESPACE ->
                    k8NamespaceRepository.findAllByWsTenantNameAndCloudProviderType(wsTenantName, cloudProviderType);
            case CUSTOM_RESOURCE_DEFINITION ->
                    k8CustomResourceDefinitionRepository.findAllByWsTenantNameAndCloudProviderType(wsTenantName, cloudProviderType);
            case DEPLOYMENT ->
                    k8DeploymentRepository.findAllByWsTenantNameAndCloudProviderType(wsTenantName, cloudProviderType);
            case NODE -> k8NodeRepository.findAllByWsTenantNameAndCloudProviderType(wsTenantName, cloudProviderType);
            case SERVICE_ACCOUNT ->
                    k8ServiceAccountRepository.findAllByWsTenantNameAndCloudProviderType(wsTenantName, cloudProviderType);
            case SECRET ->
                    k8SecretRepository.findAllByWsTenantNameAndCloudProviderType(wsTenantName, cloudProviderType);
            case CONFIG_MAP ->
                    k8ConfigMapRepository.findAllByWsTenantNameAndCloudProviderType(wsTenantName, cloudProviderType);
            case NETWORK_POLICY ->
                    k8NetworkPolicyRepository.findAllByWsTenantNameAndCloudProviderType(wsTenantName, cloudProviderType);
            default -> throw new RuntimeException("Invalid kubernetes resource type provided. Type: " + type);
        };
    }

    public List<K8RoleResponse> getK8Roles(String wsTenantName, CloudProviderType cloudProviderType, RoleLevelType roleLevelType) {
        return switch (roleLevelType) {
            case CLUSTER ->
                    clusterRoleRepository.findAllRolesUsingWsTenantNameAndCloudType(wsTenantName, cloudProviderType);
            case NAMESPACE ->
                    namespaceRoleRepository.findAllRolesUsingWsTenantNameAndCloudType(wsTenantName, cloudProviderType);
        };
    }

    public K8RoleResponse getK8RoleByUID(String roleUID, String wsTenantName, RoleLevelType roleLevelType) {
        K8RoleProjection projection;
        switch (roleLevelType) {
            case CLUSTER:
                projection = clusterRoleRepository.getK8ClusterRoleUsingUidAndTenantNameAndCloudType(wsTenantName, roleUID)
                        .orElseThrow(() -> new AzureDataException(String.format("No such %s role found with provided role UID: %s", roleLevelType, roleUID)));
                break;
            case NAMESPACE:
                projection = namespaceRoleRepository.getK8NamespaceRoleUsingUidAndTenantNameAndCloudType(wsTenantName, roleUID)
                        .orElseThrow(() -> new AzureDataException(String.format("No such %s role found with provided role UID: %s", roleLevelType, roleUID)));
                break;
            default:
                throw new IllegalArgumentException("Unsupported role type provided. Type: " + roleLevelType);
        }

        return K8RoleResponse.builder()
                .id(projection.getId())
                .UID(projection.getUID())
                .name(projection.getName())
                .namespace(projection.getNamespace())
                .cloudType(CloudProviderType.valueOf(projection.getCloudType()))
                .clusterId(projection.getClusterId())
                .parentResourceId(projection.getParentResourceId())
                .verbs(projection.getVerbs())
                .resources(projection.getResources())
                .resourceNames(projection.getResourceNames())
                .apiGroups(projection.getApiGroups())
                .build();
    }


    @Transactional
    public Boolean publishK8Resourcc(PublishResourceRequest request) {
        if (request.isFlag()) {
            try {
                publishedResourcesRepository.save(AzureEntityUtil.createPublishedResourcesFromRequest(request));
            } catch (Exception ex) {
                if (ex.getMessage().contains("duplicate key")) {
                    throw new RuntimeException("Resource already published");
                }
            }
        } else {
            publishedResourcesRepository.deleteByResourceIdAndWsTenantName(request.getResourceId(), request.getWsTenantName());
        }

        return Boolean.TRUE;
    }
}




















