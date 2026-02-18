package com.ws.azureResourcesIntegration.service;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.exception.K8ResourceException;
import com.ws.azureResourcesIntegration.constant.PublishResourceType;
import com.ws.azureAdIntegration.exception.AzureDataException;
import com.ws.azureAdIntegration.util.AzureEntityUtil;
import com.ws.azureKuberntesJIT.repository.K8IngressRepository;
import com.ws.azureKuberntesJIT.repository.*;
import com.ws.azureResourcesIntegration.dto.PublishResourceRequest;
import com.ws.azureResourcesIntegration.entities.PublishedResource;
import com.ws.azureResourcesIntegration.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PublishResourceService {
    final PublishedResourcesRepository publishedResourcesRepository;
    final AzureVMRepository azureVMRepository;
    final AzureStorageRepository azureStorageRepository;
    final AzureDatabaseRepository azureDatabaseRepository;
    final AzureKubernetesClusterRepository azureKubernetesClusterRepository;
    final K8StorageClassRepository k8StorageClassRepository;
    final K8PersistentVolumeRepository k8PersistentVolumeRepository;
    final K8NamespaceRepository k8NamespaceRepository;
    final K8NodeRepository k8NodeRepository;
    final K8DeploymentRepository k8DeploymentRepository;
    final K8ServiceAccountRepository k8ServiceAccountRepository;
    final K8SecretRepository k8SecretRepository;
    final K8ConfigMapRepository k8ConfigMapRepository;
    final K8NetworkPolicyRepository k8NetworkPolicyRepository;
    final K8ReplicaSetRepository k8ReplicaSetRepository;
    final K8StatefulSetRepository k8StatefulSetRepository;
    final K8DaemonSetRepository k8DaemonSetRepository;
    final K8JobRepository k8JobRepository;
    final K8CronJobRepository k8CronJobRepository;
    final K8IngressRepository k8IngressRepository;
    final K8ServiceRepository k8ServiceRepository;

    @Autowired
    public PublishResourceService(PublishedResourcesRepository publishedResourcesRepository, AzureVMRepository azureVMRepository,
                                  AzureStorageRepository azureStorageRepository, AzureDatabaseRepository azureDatabaseRepository, AzureKubernetesClusterRepository azureKubernetesClusterRepository, K8StorageClassRepository k8StorageClassRepository, K8PersistentVolumeRepository k8PersistentVolumeRepository, K8NamespaceRepository k8NamespaceRepository,
                                  K8NodeRepository k8NodeRepository, K8DeploymentRepository k8DeploymentRepository,
                                  K8ServiceAccountRepository k8ServiceAccountRepository, K8SecretRepository k8SecretRepository,
                                  K8ConfigMapRepository k8ConfigMapRepository, K8NetworkPolicyRepository k8NetworkPolicyRepository,
                                  K8ReplicaSetRepository k8ReplicaSetRepository, K8StatefulSetRepository k8StatefulSetRepository,
                                  K8DaemonSetRepository k8DaemonSetRepository, K8JobRepository k8JobRepository, K8CronJobRepository k8CronJobRepository,
                                  K8IngressRepository k8IngressRepository, K8ServiceRepository k8ServiceRepository) {
        this.publishedResourcesRepository = publishedResourcesRepository;
        this.azureVMRepository = azureVMRepository;
        this.azureStorageRepository = azureStorageRepository;
        this.azureDatabaseRepository = azureDatabaseRepository;
        this.azureKubernetesClusterRepository = azureKubernetesClusterRepository;
        this.k8StorageClassRepository = k8StorageClassRepository;
        this.k8PersistentVolumeRepository = k8PersistentVolumeRepository;
        this.k8NamespaceRepository = k8NamespaceRepository;
        this.k8NodeRepository = k8NodeRepository;
        this.k8DeploymentRepository = k8DeploymentRepository;
        this.k8ServiceAccountRepository = k8ServiceAccountRepository;
        this.k8SecretRepository = k8SecretRepository;
        this.k8ConfigMapRepository = k8ConfigMapRepository;
        this.k8NetworkPolicyRepository = k8NetworkPolicyRepository;
        this.k8ReplicaSetRepository = k8ReplicaSetRepository;
        this.k8StatefulSetRepository = k8StatefulSetRepository;
        this.k8DaemonSetRepository = k8DaemonSetRepository;
        this.k8JobRepository = k8JobRepository;
        this.k8CronJobRepository = k8CronJobRepository;
        this.k8IngressRepository = k8IngressRepository;
        this.k8ServiceRepository = k8ServiceRepository;
    }

    @Transactional
    public void publishAzureResource(PublishResourceRequest request) {
        if (request.isFlag()) {
            publishResources(AzureEntityUtil.createPublishedResourcesFromRequest(request));
        } else {
            unPublishResources(request.getResourceId().trim(), request.getResourceAccountId().trim(), request.getWsTenantName().trim());
        }
    }

    @Transactional
    public Boolean publishKubernetesResource(PublishResourceRequest request) {
        String resourceAccountId = request.getResourceAccountId().trim();
        String wsTenantName = request.getWsTenantName().trim();
        String clusterId = request.getClusterId().trim();
        String resourceId = request.getResourceId().trim();

        checkIfClusterIsPublished(request.getCloudProviderType(), clusterId, resourceAccountId, wsTenantName);

        if (request.isFlag()) {
            publishResources(AzureEntityUtil.createPublishedResourcesForK8FromRequest(request));
        } else {
            unPublishResources(resourceId, resourceAccountId, wsTenantName);
        }
        return Boolean.TRUE;
    }


    private void checkIfClusterIsPublished(CloudProviderType cloudType, String clusterId, String resourceAccountId, String wsTenantName) {
        if (Objects.requireNonNull(cloudType) == CloudProviderType.AZURE) {
            checkIfAKSClusterIsPublishedByClusterId(clusterId, resourceAccountId, wsTenantName);
        } else {
            throw new K8ResourceException("Unsupported cloud type provided. Type: " + cloudType);
        }
    }


    private void checkIfAKSClusterIsPublishedByClusterId(String clusterId, String resourceAccountId, String wsTenantName) {
        boolean isClusterPublished = publishedResourcesRepository
                .findByResourceIdAndResourceTypeAndResourceAccountIdAndWsTenantName(clusterId, PublishResourceType.AZURE_KUBERNETES, resourceAccountId, wsTenantName)
                .isEmpty();
        if (isClusterPublished) {
            throw new K8ResourceException("Please publish the cluster first before publishing its resource");
        }
    }


    private void publishResources(PublishedResource publishedResource) {
        try {
            publishedResourcesRepository.save(publishedResource);
        } catch (Exception ex) {
            if (ex.getMessage().contains("duplicate key")) {
                throw new AzureDataException("Resource already published");
            }
        }
    }

    private void unPublishResources(String resourceId, String resourceAccountId, String wsTenantName) {
        publishedResourcesRepository.deleteByResourceIdAndResourceAccountIdAndWsTenantName(resourceId, resourceAccountId, wsTenantName);
    }

    public List<?> getPublishedAzureResources(String wsTenantName, PublishResourceType type, String subscriptionId) {
        return switch (type) {
            case VIRTUAL_MACHINE ->
                    azureVMRepository.findAllPublishedAzureVMByWsTenantNameAndSubscriptionId(wsTenantName, subscriptionId);
            case STORAGE_ACCOUNT ->
                    azureStorageRepository.findAllPublishedAzureStorageAccountsBywsTenantNameAndsubscriptionId(wsTenantName, subscriptionId);
            case DATABASE ->
                    azureDatabaseRepository.findAllPublishedAzureDatabaseBywsTenantNameAndsubscriptionId(wsTenantName, subscriptionId);
            case AZURE_KUBERNETES ->
                    azureKubernetesClusterRepository.findAllPublishedAksClustersByWsTenantNameAndSubscriptionId(wsTenantName, subscriptionId);
            default -> throw new AzureDataException(String.format("Invalid type provided. Type: %s", type));
        };
        //        if (!resources.isEmpty()) {
//            setSubscriptionIdForResources(resources);
//        }
    }


    public List<?> getPublishedKubernetesResources(String wsTenantName, String clusterId, PublishResourceType type) {
        return switch (type) {
//            case NAMESPACE -> k8NamespaceRepository.findAllPublishedK8DeploymentsByWsTenantName(wsTenantName, clusterId)
//            case CUSTOM_RESOURCE_DEFINITION ->
//            case NODE ->
//            case STORAGE_CLASS ->
//            case PERSISTENT_VOLUME ->
            case DEPLOYMENT ->
                    k8DeploymentRepository.findAllPublishedK8DeploymentsByWsTenantName(wsTenantName, clusterId);
            case SERVICE_ACCOUNT ->
                    k8ServiceAccountRepository.findAllPublishedK8ServiceAccountsByWsTenantName(wsTenantName, clusterId);
            case SECRET -> k8SecretRepository.findAllPublishedK8SecretsByWsTenantName(wsTenantName, clusterId);
            case CONFIG_MAP ->
                    k8ConfigMapRepository.findAllPublishedK8ConfigMapsByWsTenantName(wsTenantName, clusterId);
            case NETWORK_POLICY ->
                    k8NetworkPolicyRepository.findAllPublishedK8NetworkPoliciesByWsTenantName(wsTenantName, clusterId);
            case JOB -> k8JobRepository.findAllPublishedK8JobsByWsTenantName(wsTenantName, clusterId);
            case CRON_JOB -> k8CronJobRepository.findAllPublishedK8CronJobsByWsTenantName(wsTenantName, clusterId);
            case INGRESS -> k8IngressRepository.findAllPublishedK8IngressesByWsTenantName(wsTenantName, clusterId);
            case SERVICE -> k8ServiceRepository.findAllPublishedK8ServicesByWsTenantName(wsTenantName, clusterId);
            case REPLICA_SET ->
                    k8ReplicaSetRepository.findAllPublishedK8ReplicaSetsByWsTenantName(wsTenantName, clusterId);
            case STATEFUL_SET ->
                    k8StatefulSetRepository.findAllPublishedK8StatefulSetsByWsTenantName(wsTenantName, clusterId);
            case DAEMON_SET ->
                    k8DaemonSetRepository.findAllPublishedK8DaemonSetsByWsTenantName(wsTenantName, clusterId);
            default -> throw new RuntimeException(String.format("Invalid type provided. Type: %s", type));
        };
    }


    public List<Map<String, String>> getAllAzurePublishedResourceTypes() {
        return Arrays.stream(PublishResourceType.values())
                .filter(type -> type == PublishResourceType.VIRTUAL_MACHINE ||
                        type == PublishResourceType.STORAGE_ACCOUNT ||
                        type == PublishResourceType.DATABASE ||
                        type == PublishResourceType.AZURE_KUBERNETES)
                .map(type -> Map.of("key", type.name(), "value", type.getDisplayName())) // Changed 'value' to 'displayName'
                .collect(Collectors.toList());
    }


    public List<Map<String, String>> getAllK8PublishedResourceTypes() {
        return Arrays.stream(PublishResourceType.values())
                .filter(type -> type == PublishResourceType.NAMESPACE ||
                        type == PublishResourceType.CUSTOM_RESOURCE_DEFINITION ||
                        type == PublishResourceType.NODE ||
                        type == PublishResourceType.STORAGE_CLASS ||
                        type == PublishResourceType.PERSISTENT_VOLUME ||
                        type == PublishResourceType.DEPLOYMENT ||
                        type == PublishResourceType.SERVICE_ACCOUNT ||
                        type == PublishResourceType.SECRET ||
                        type == PublishResourceType.CONFIG_MAP ||
                        type == PublishResourceType.NETWORK_POLICY ||
                        type == PublishResourceType.JOB ||
                        type == PublishResourceType.CRON_JOB ||
                        type == PublishResourceType.INGRESS ||
                        type == PublishResourceType.SERVICE ||
                        type == PublishResourceType.REPLICA_SET ||
                        type == PublishResourceType.STATEFUL_SET ||
                        type == PublishResourceType.DAEMON_SET)
                .map(type -> Map.of("key", type.name(), "value", type.getDisplayName())) // Changed 'value' to 'displayName'
                .collect(Collectors.toList());
    }


//    private void setSubscriptionIdForResources(List<?> resources) {
//        if (resources.get(0) instanceof AzureVM) {
//            Integer subscriptionId = ((AzureVM) resources.get(0)).getAzureSubscription().getId();
//            resources.forEach(resource -> ((AzureVM) resource).setAzureSubscriptionId(subscriptionId));
//        } else if (resources.get(0) instanceof AzureStorageAccount) {
//            Integer subscriptionId = ((AzureStorageAccount) resources.get(0)).getAzureSubscription().getId();
//            resources.forEach(resource -> ((AzureStorageAccount) resource).setAzureSubscriptionId(subscriptionId));
//        } else if (resources.get(0) instanceof AzureDatabase) {
//            Integer subscriptionId = ((AzureDatabase) resources.get(0)).getAzureServer().getAzureSubscription().getId();
//            resources.forEach(resource -> ((AzureDatabase) resource).setAzureSubscriptionId(subscriptionId));
//        }
//    }

//    @Transactional
//    public void publishResourceByResourceIdAndType(PublishResourceRequest request) {
//        if (request.isFlag()) {
//            try {
//                PublishedResource publishedResource = AzureEntityUtil.createPublishedResourcesFromRequest(request);
//                publishedResourcesRepository.save(publishedResource);
//            } catch (Exception ex) {
//                if (ex.getMessage().contains("duplicate key")) {
//                    throw new AzureDataException("Resource already published");
//                }
//            }
//        } else {
//            publishedResourcesRepository.deleteByResourceIdAndWsTenantName(request.getResourceId(), request.getWsTenantName());
//        }
//    }

}
