package com.ws.azureKuberntesJIT.service;


import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.exception.AzureDataException;
import com.ws.azureAdIntegration.util.GenericUtil;
import com.ws.azureKuberntesJIT.dto.MetadataDTO;
import com.ws.azureKuberntesJIT.dto.NamespaceDTO;
import com.ws.azureKuberntesJIT.dto.NodeDTO;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.io.StringReader;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8ResourcesSyncService {
    CoreV1Api coreV1Api;
    AppsV1Api appsApi;
    BatchV1Api batchApi;
    StorageV1Api storageV1Api;
    NetworkingV1Api networkingApi;
    RbacAuthorizationV1Api rbacApi;
    ApiextensionsV1Api apiextensionsV1Api;
    CloudProviderType cloudProviderType;


    @Transactional
    public void syncKubernetesData(Map<String, String> clusterIdAndKubeConfigMap, CloudProviderType cloudProviderType) {
        if (CollectionUtils.isEmpty(clusterIdAndKubeConfigMap)) {
            throw new AzureDataException("No kubernetes configurations provided");
        }
        this.cloudProviderType = cloudProviderType;
        for (Map.Entry<String, String> stringStringEntry : clusterIdAndKubeConfigMap.entrySet()) {
            initializeK8Clients(stringStringEntry.getValue());
            executeSync(stringStringEntry.getKey());
            log.info(String.format("K8 resources data sync completed for cluster id: %s of type: %s", stringStringEntry.getKey(), cloudProviderType));
        }
    }

    private void initializeK8Clients(String kubeConfig) {
        try {
            ApiClient client = Config.fromConfig(new StringReader(kubeConfig));
            Configuration.setDefaultApiClient(client);
            this.coreV1Api = new CoreV1Api();
            this.appsApi = new AppsV1Api();
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
            fetchNamespace(clusterId);
            fetchNodes(clusterId);
//            fetchClusterRoles(rbacApi, clusterId);
//            fetchStorageClasses(storageV1Api, clusterId);
        } catch (Exception ex) {
            log.error("Error occurred in syncing data from Azure AD: {}", ex.getMessage());
            throw new RuntimeException(ex.getMessage());
        }

    }

    private void fetchNamespace(String clusterId) throws ApiException {
        V1NamespaceList v1NamespaceList = this.coreV1Api.listNamespace().execute();
        if (ObjectUtils.isEmpty(v1NamespaceList)) {
            throw new AzureDataException("No NAMESPACE(s) found");
        }

        List<NamespaceDTO> namespaceDTOS = v1NamespaceList.getItems().stream()
                .map(item -> {
                    NamespaceDTO.NamespaceDTOBuilder builder = NamespaceDTO.builder();

                    builder.apiVersion(item.getApiVersion());
                    builder.kind(item.getKind());

                    // Extracting and setting status information
                    if (!ObjectUtils.isEmpty(item.getStatus())) {
                        builder.phase(item.getStatus().getPhase());
//                        item.getStatus().getConditions().stream().forEach(v1NamespaceCondition -> {
//                            builder.conditionStatus(v1NamespaceCondition.getStatus());
//                            builder.conditionMessage(v1NamespaceCondition.getMessage());
//                            builder.conditionType(v1NamespaceCondition.getType());
//                            builder.conditionReason(v1NamespaceCondition.getReason());
//                            builder.conditionLastTransitionTime(v1NamespaceCondition.getLastTransitionTime());
//                        });
                    }

//                    if (!ObjectUtils.isEmpty(item.getSpec())) {
//                        builder.finalizers(item.getSpec().getFinalizers());
//                    }

                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                        builder.deletionGracePeriodSeconds(metadata.getDeletionGracePeriodSeconds());
//                        builder.labels(metadata.getLabels());
                        // Setting ownerReferences (controller field)
//                        if (!ObjectUtils.isEmpty(metadata.getOwnerReferences())) {
//                            builder.ownerReferencesController(
//                                    metadata.getOwnerReferences().stream()
//                                            .map(V1OwnerReference::getController)
//                                            .filter(Objects::nonNull)
//                                            .findFirst()
//                                            .orElse(null)
//                            );
//                        }
//                        metadata.getManagedFields().forEach((v1ManagedFieldsEntry -> {
//                            v1ManagedFieldsEntry.getFieldsType();
//                            v1ManagedFieldsEntry.getManager();
//                            v1ManagedFieldsEntry.getOperation();
//                            v1ManagedFieldsEntry.getSubresource();
//                        }));

                    }

                    return builder.build();
                })
                .toList();
    }


    private List<NodeDTO> fetchNodes(String clusterId) throws ApiException {
        V1NodeList v1NodeList = this.coreV1Api.listNode().execute();
        if (ObjectUtils.isEmpty(v1NodeList)) {
            throw new AzureDataException("No NODE(s) found");
        }

        return v1NodeList.getItems().stream()
                .map(item -> {
                    NodeDTO.NodeDTOBuilder builder = NodeDTO.builder();

                    builder.apiVersion(item.getApiVersion());
                    builder.kind(item.getKind());

                    // Extracting and setting status information
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
                .collect(Collectors.toList());
    }


    private void setMetadataFields(MetadataDTO.MetadataDTOBuilder builder, V1ObjectMeta metadata, String clusterId) {
        builder.selfLink(metadata.getSelfLink());
        builder.resourceVersion(metadata.getResourceVersion());
        builder.generation(metadata.getGeneration());
        builder.name(metadata.getName());
        builder.uid(metadata.getUid());
        builder.namespace(metadata.getNamespace());
        builder.annotations(metadata.getAnnotations());
        builder.creationTimestamp(metadata.getCreationTimestamp());
        builder.deletionTimestamp(metadata.getDeletionTimestamp());
        builder.annotations(metadata.getAnnotations());
        builder.clusterId(clusterId);
        builder.cloudProviderType(this.cloudProviderType);
    }


    private void fetchClusterRoles(RbacAuthorizationV1Api api) throws ApiException {
        V1ClusterRoleList v1ClusterRoleList = api.listClusterRole().execute();
    }

    private void fetchStorageClasses(StorageV1Api api) throws ApiException {
        V1StorageClassList v1StorageClassList = api.listStorageClass().execute();
    }

}
