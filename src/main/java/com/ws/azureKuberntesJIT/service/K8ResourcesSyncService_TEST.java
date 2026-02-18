//package com.ws.azureKuberntesJIT.service;
//
//
//import com.ws.azureAdIntegration.constants.CloudProviderType;
//import com.ws.azureAdIntegration.exception.AzureDataException;
//import com.ws.azureKuberntesJIT.constant.RoleBindingType;
//import com.ws.azureKuberntesJIT.dto.*;
//import io.kubernetes.client.openapi.ApiClient;
//import io.kubernetes.client.openapi.ApiException;
//import io.kubernetes.client.openapi.Configuration;
//import io.kubernetes.client.openapi.apis.*;
//import io.kubernetes.client.openapi.models.*;
//import io.kubernetes.client.util.Config;
//import lombok.AccessLevel;
//import lombok.experimental.FieldDefaults;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//import org.springframework.util.CollectionUtils;
//import org.springframework.util.ObjectUtils;
//
//import java.io.StringReader;
//import java.time.LocalDateTime;
//import java.util.*;
//import java.util.stream.Collectors;
//
//@Service
//@Slf4j
//@FieldDefaults(level = AccessLevel.PRIVATE)
//public class K8ResourcesSyncService_TEST {
//    CoreV1Api coreV1Api;
//    AppsV1Api appsV1Api;
//    BatchV1Api batchApi;
//    StorageV1Api storageV1Api;
//    NetworkingV1Api networkingApi;
//    RbacAuthorizationV1Api rbacApi;
//    ApiextensionsV1Api apiextensionsV1Api;
//    CloudProviderType cloudProviderType;
//
//
//    @Transactional
//    public void syncKubernetesData(Map<String, String> clusterIdAndKubeConfigMap, CloudProviderType cloudProviderType) {
//        if (CollectionUtils.isEmpty(clusterIdAndKubeConfigMap)) {
//            throw new AzureDataException("No kubernetes configurations provided");
//        }
//        this.cloudProviderType = cloudProviderType;
//        for (Map.Entry<String, String> stringStringEntry : clusterIdAndKubeConfigMap.entrySet()) {
//            initializeK8Clients(stringStringEntry.getValue());
//            log.info("K8 clients initialized successfully");
//            log.info(String.format("K8 resources data sync STARTED for cluster id: %s of type: %s at: %s", stringStringEntry.getKey(), cloudProviderType, LocalDateTime.now()));
//            executeSync(stringStringEntry.getKey());
//            log.info(String.format("K8 resources data sync COMPLETED for cluster id: %s of type: %s at: %s", stringStringEntry.getKey(), cloudProviderType, LocalDateTime.now()));
//        }
//    }
//
//    private void initializeK8Clients(String kubeConfig) {
//        try {
//            ApiClient client = Config.fromConfig(new StringReader(kubeConfig));
//            Configuration.setDefaultApiClient(client);
//            this.coreV1Api = new CoreV1Api();
//            this.appsV1Api = new AppsV1Api();
//            this.batchApi = new BatchV1Api();
//            this.storageV1Api = new StorageV1Api();
//            this.networkingApi = new NetworkingV1Api();
//            this.rbacApi = new RbacAuthorizationV1Api();
//            this.apiextensionsV1Api = new ApiextensionsV1Api();
//        } catch (Exception ex) {
//            log.error("Error in initializing k8 clients");
//            log.error("Error: {}", ex.getMessage());
//            throw new RuntimeException(ex.getMessage());
//        }
//    }
//
//
//    private void executeSync(String clusterId) {
//        try {
//            fetchNamespace(clusterId); /* Required to fetch */
//            fetchNodes(clusterId);
//            fetchCustomResourceDefinition(clusterId);
//            fetchClusterRoles(clusterId);
//            fetchClusterRoleBinding(clusterId);
//            fetchNamespaceRoles(clusterId);
//            fetchNamespaceRoleBinding(clusterId);
//            fetchDeployments(clusterId);
//            fetchSecrets(clusterId);
//            fetchServiceAccounts(clusterId);
//            fetchPersistentVolumes(clusterId);
//            fetchPersistentVolumeClaims(clusterId);
//            fetchStorageClasses(clusterId);
//            fetchConfigMap(clusterId);
//            fetchNetworkPolicies(clusterId);
//        } catch (Exception ex) {
//            log.error("Error occurred in syncing data from Kubernetes: {}", ex.getMessage());
//            throw new RuntimeException(ex.getMessage());
//        }
//
//    }
//
//    private void fetchNamespace(String clusterId) throws ApiException {
//        V1NamespaceList v1NamespaceList = this.coreV1Api.listNamespace().execute();
//        if (ObjectUtils.isEmpty(v1NamespaceList)) {
//            throw new AzureDataException("No NAMESPACE(s) found");
//        }
//
//        List<NamespaceDTO> namespaceDTOS = v1NamespaceList.getItems().stream()
//                .map(item -> {
//                    NamespaceDTO.NamespaceDTOBuilder builder = NamespaceDTO.builder();
//
//                    builder.apiVersion(item.getApiVersion());
//                    builder.kind(item.getKind());
//
//                    // Extracting and setting status information
//                    if (!ObjectUtils.isEmpty(item.getStatus())) {
//                        builder.phase(item.getStatus().getPhase());
//                    }
//
//
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    return builder.build();
//                })
//                .toList();
//    }
//
//    private void fetchNodes(String clusterId) throws ApiException {
//        V1NodeList v1NodeList = this.coreV1Api.listNode().execute();
//        if (ObjectUtils.isEmpty(v1NodeList)) {
//            log.warn(String.format("No NODE(s) found for cluster id: %S of cloud type: %s", clusterId, this.cloudProviderType));
//            return;
//        }
//
//        List<NodeDTO> nodeDTOS = v1NodeList.getItems().stream()
//                .map(item -> {
//                    NodeDTO.NodeDTOBuilder builder = NodeDTO.builder();
//
//                    builder.apiVersion(item.getApiVersion());
//                    builder.kind(item.getKind());
//
//                    if (!ObjectUtils.isEmpty(item.getStatus())) {
//                        builder.phase(item.getStatus().getPhase());
//                    }
//
//                    if (!ObjectUtils.isEmpty(item.getSpec())) {
//                        builder.externalID(item.getSpec().getExternalID());
//                        builder.podCIDR(item.getSpec().getPodCIDR());
//                        builder.unschedulable(item.getSpec().getUnschedulable());
//                        builder.providerID(item.getSpec().getProviderID());
//                    }
//
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    return builder.build();
//                })
//                .toList();
//    }
//
//    private void fetchCustomResourceDefinition(String clusterId) throws ApiException {
//        V1CustomResourceDefinitionList v1CustomResourceDefinitionList = this.apiextensionsV1Api.listCustomResourceDefinition().execute();
//        if (ObjectUtils.isEmpty(v1CustomResourceDefinitionList)) {
//            log.warn(String.format("No CustomResourceDefinition(s) found for cluster id: %S of cloud type: %s", clusterId, this.cloudProviderType));
//            return;
//        }
//
//        List<CustomResourceDefinitionDTO> customResourceDefinitionDTOS = v1CustomResourceDefinitionList.getItems().stream()
//                .map(item -> {
//                    CustomResourceDefinitionDTO.CustomResourceDefinitionDTOBuilder builder = CustomResourceDefinitionDTO.builder();
//
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    return builder.build();
//                })
//                .toList();
//    }
//
//    private void fetchClusterRoles(String clusterId) throws ApiException {
//        V1ClusterRoleList v1ClusterRoleList = this.rbacApi.listClusterRole().execute();
//        if (ObjectUtils.isEmpty(v1ClusterRoleList)) {
//            log.warn(String.format("No CLUSTER_ROLE(s) found for cluster id: %S of cloud type: %s", clusterId, this.cloudProviderType));
//            return;
//        }
//
//        List<ClusterRoleDTO> clusterRoleDTOS = v1ClusterRoleList.getItems().stream()
//                .map(item -> {
//                    ClusterRoleDTO.ClusterRoleDTOBuilder builder = ClusterRoleDTO.builder();
//
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    V1AggregationRule v1AggregationRule = item.getAggregationRule();
//                    if (!ObjectUtils.isEmpty(v1AggregationRule)) {
//                        AggregationRuleDTO aggregationRuleDTO = new AggregationRuleDTO();
//
//                        List<LabelSelectorDTO> labelSelectorDTOS = v1AggregationRule.getClusterRoleSelectors().stream()
//                                .map(v1LabelSelector -> {
//                                    List<LabelSelectorRequirementDTO> labelSelectorRequirementDTOS = v1LabelSelector.getMatchExpressions().stream()
//                                            .map(matchExpression -> LabelSelectorRequirementDTO.builder()
//                                                    .key(matchExpression.getKey())
//                                                    .operator(matchExpression.getOperator())
//                                                    .values(matchExpression.getValues())
//                                                    .build())
//                                            .collect(Collectors.toList());
//
//                                    return LabelSelectorDTO.builder()
//                                            .matchExpressions(labelSelectorRequirementDTOS)
//                                            .matchLabels(v1LabelSelector.getMatchLabels())
//                                            .build();
//                                })
//                                .collect(Collectors.toList());
//
//                        aggregationRuleDTO.setLabelSelectorDTOS(labelSelectorDTOS);
//                        builder.aggregationRuleDTO(aggregationRuleDTO);
//                    }
//                    List<PolicyRuleDTO> policyRuleDTOList = item.getRules().stream()
//                            .map(v1PolicyRule -> PolicyRuleDTO.builder()
//                                    .verbs(v1PolicyRule.getVerbs())
//                                    .apiGroups(v1PolicyRule.getApiGroups())
//                                    .resources(v1PolicyRule.getResources())
//                                    .nonResourceURLs(v1PolicyRule.getNonResourceURLs())
//                                    .resourceNames(v1PolicyRule.getResourceNames())
//                                    .build())
//                            .collect(Collectors.toList());
//
//                    builder.policyRuleDTOS(policyRuleDTOList);
//
//                    return builder.build();
//                })
//                .toList();
//    }
//
//    private void fetchClusterRoleBinding(String clusterId) throws ApiException {
//        V1ClusterRoleBindingList v1ClusterRoleBindingList = this.rbacApi.listClusterRoleBinding().execute();
//        if (ObjectUtils.isEmpty(v1ClusterRoleBindingList)) {
//            log.warn(String.format("NO CLUSTER_ROLE_BINDING found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
//        }
//
//        List<RoleBindDTO> roleBindDTOS = v1ClusterRoleBindingList.getItems().stream()
//                .map(item -> {
//                    RoleBindDTO.RoleBindDTOBuilder builder = RoleBindDTO.builder();
//
//                    builder.roleBindingType(RoleBindingType.CLUSTER);
//
//                    // Set metadata fields
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    Optional.of(item.getRoleRef())
//                            .ifPresent(roleRef -> builder.roleRefDTO(RoleRefDTO.builder()
//                                    .kind(roleRef.getKind())
//                                    .name(roleRef.getName())
//                                    .apiGroup(roleRef.getApiGroup())
//                                    .build()));
//
//                    List<RbacSubjectDTO> subjectDTOS = item.getSubjects().stream()
//                            .map(rbacV1Subject -> RbacSubjectDTO.builder()
//                                    .kind(rbacV1Subject.getKind())
//                                    .apiGroup(rbacV1Subject.getApiGroup())
//                                    .name(rbacV1Subject.getName())
//                                    .namespace(rbacV1Subject.getNamespace())
//                                    .build())
//                            .collect(Collectors.toList());
//
//                    builder.rbacSubjectDTOS(subjectDTOS);
//                    return builder.build();
//                })
//                .toList();
//    }
//
//    private void fetchNamespaceRoles(String clusterId) throws ApiException {
//        V1RoleList v1RoleList = rbacApi.listRoleForAllNamespaces().execute();
//        if (ObjectUtils.isEmpty(v1RoleList)) {
//            log.warn(String.format("NO NAMESPACE_ROLE(s) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
//            return;
//        }
//
//        List<NamespaceRoleDTO> namespaceRoleDTOS = v1RoleList.getItems().stream()
//                .map(item -> {
//                    NamespaceRoleDTO.NamespaceRoleDTOBuilder builder = NamespaceRoleDTO.builder();
//
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    List<PolicyRuleDTO> policyRuleDTOS = item.getRules().stream()
//                            .map(rule -> PolicyRuleDTO.builder()
//                                    .verbs(rule.getVerbs())
//                                    .apiGroups(rule.getApiGroups())
//                                    .resources(rule.getResources())
//                                    .nonResourceURLs(rule.getNonResourceURLs())
//                                    .resourceNames(rule.getResourceNames())
//                                    .build())
//                            .collect(Collectors.toList());
//
//                    builder.policyRuleDTOS(policyRuleDTOS);
//
//                    return builder.build();
//                })
//                .toList();
//    }
//
//    private void fetchNamespaceRoleBinding(String clusterId) throws ApiException {
//        V1RoleBindingList v1RoleBindingList = rbacApi.listRoleBindingForAllNamespaces().execute();
//        if (ObjectUtils.isEmpty(v1RoleBindingList)) {
//            log.warn(String.format("NO NAMESPACE_ROLE_BINDING found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
//            return;
//        }
//
//        List<RoleBindDTO> roleBindDTOS = v1RoleBindingList.getItems().stream()
//                .map(item -> {
//                    RoleBindDTO.RoleBindDTOBuilder builder = RoleBindDTO.builder();
//
//                    builder.roleBindingType(RoleBindingType.NAMESPACE);
//
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    Optional.of(item.getRoleRef())
//                            .ifPresent(roleRef -> builder.roleRefDTO(
//                                    RoleRefDTO.builder()
//                                            .kind(roleRef.getKind())
//                                            .name(roleRef.getName())
//                                            .apiGroup(roleRef.getApiGroup())
//                                            .build()
//                            ));
//
//                    List<RbacSubjectDTO> subjectDTOS = item.getSubjects().stream()
//                            .map(rbacV1Subject -> RbacSubjectDTO.builder()
//                                    .kind(rbacV1Subject.getKind())
//                                    .apiGroup(rbacV1Subject.getApiGroup())
//                                    .name(rbacV1Subject.getName())
//                                    .namespace(rbacV1Subject.getNamespace())
//                                    .build())
//                            .collect(Collectors.toList());
//
//                    builder.rbacSubjectDTOS(subjectDTOS);
//
//                    return builder.build();
//                })
//                .collect(Collectors.toList());
//    }
//
//    private void fetchDeployments(String clusterId) throws ApiException {
//        V1DeploymentList v1DeploymentList = this.appsV1Api.listDeploymentForAllNamespaces().execute();
//        if (ObjectUtils.isEmpty(v1DeploymentList.getItems())) {
//            log.warn(String.format("NO DEPLOYMENT(s) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
//            return;
//        }
//        List<DeploymentDTO> deploymentDTOS = v1DeploymentList.getItems().stream()
//                .map(item -> {
//                    DeploymentDTO.DeploymentDTOBuilder builder = DeploymentDTO.builder();
//
//                    if (item.getMetadata() != null) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    return builder.build();
//                })
//                .toList();
//
//    }
//
//    private void fetchSecrets(String clusterId) throws ApiException {
//        V1SecretList v1SecretList = this.coreV1Api.listSecretForAllNamespaces().execute();
//        if (ObjectUtils.isEmpty(v1SecretList)) {
//            log.warn(String.format("NO SECRET(s) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
//            return;
//        }
//        List<SecretDTO> secretDTOS = v1SecretList.getItems().stream()
//                .map(item -> {
//                    SecretDTO.SecretDTOBuilder builder = SecretDTO.builder();
//
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    builder.tyep(item.getType())
//                            .immutable(item.getImmutable())
//                            .stringData(item.getStringData());
//                    return builder.build();
//                })
//                .toList();
//
//    }
//
//    private void fetchServiceAccounts(String clusterId) throws ApiException {
//        V1ServiceAccountList v1ServiceAccountList = coreV1Api.listServiceAccountForAllNamespaces().execute();
//        if (ObjectUtils.isEmpty(v1ServiceAccountList)) {
//            log.warn(String.format("NO SERVICE_ACCOUNT(s) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
//            return;
//        }
//        List<ServiceAccountDTO> serviceAccountDTOS = v1ServiceAccountList.getItems().stream()
//                .map(item -> {
//                    ServiceAccountDTO.ServiceAccountDTOBuilder builder = ServiceAccountDTO.builder();
//
//                    // Set metadata fields using the builder
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    builder.automountServiceAccountToken(item.getAutomountServiceAccountToken());
//
//                    return builder.build();
//                })
//                .toList();
//    }
//
//    private void fetchPersistentVolumes(String clusterId) throws ApiException {
//        V1PersistentVolumeList v1PersistentVolumeList = this.coreV1Api.listPersistentVolume().execute();
//        if (ObjectUtils.isEmpty(v1PersistentVolumeList)) {
//            log.warn(String.format("NO PERSISTENT_VOLUME(s) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
//            return;
//        }
//        List<PersistentVolumeDTO> persistentVolumeDTOS = v1PersistentVolumeList.getItems().stream()
//                .map(item -> {
//                    PersistentVolumeDTO.PersistentVolumeDTOBuilder builder = PersistentVolumeDTO.builder();
//
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    return builder.build();
//                })
//                .toList();
//    }
//
//    private void fetchPersistentVolumeClaims(String clusterId) throws ApiException {
//        V1PersistentVolumeClaimList v1PersistentVolumeClaimList = this.coreV1Api.listPersistentVolumeClaimForAllNamespaces().execute();
//        if (ObjectUtils.isEmpty(v1PersistentVolumeClaimList)) {
//            log.warn(String.format("NO PERSISTENT_VOLUME_CLAIM(s) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
//            return;
//        }
//        List<PersistentVolumeClaimDTO> persistentVolumeClaimDTOS = v1PersistentVolumeClaimList.getItems().stream()
//                .map(item -> {
//                    PersistentVolumeClaimDTO.PersistentVolumeClaimDTOBuilder builder = PersistentVolumeClaimDTO.builder();
//
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    return builder.build();
//                })
//                .toList();
//    }
//
//    private void fetchStorageClasses(String clusterId) throws ApiException {
//        V1StorageClassList v1StorageClassList = this.storageV1Api.listStorageClass().execute();
//        if (ObjectUtils.isEmpty(v1StorageClassList)) {
//            log.warn(String.format("NO STORAE_CLASS(Es) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
//            return;
//        }
//        List<StorageClassDTO> storageClassDTOS = v1StorageClassList.getItems().stream()
//                .map(item -> {
//                    StorageClassDTO.StorageClassDTOBuilder builder = StorageClassDTO.builder();
//
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    builder.apiVersion(item.getApiVersion())
//                            .provisioner(item.getProvisioner())
//                            .volumeBindingMode(item.getVolumeBindingMode())
//                            .allowVolumeExpansion(item.getAllowVolumeExpansion())
//                            .reclaimPolicy(item.getReclaimPolicy());
//
//                    return builder.build();
//                })
//                .toList();
//
//    }
//
//    private void fetchConfigMap(String clusterId) throws ApiException {
//        V1ConfigMapList v1ConfigMapList = this.coreV1Api.listConfigMapForAllNamespaces().execute();
//        if (ObjectUtils.isEmpty(v1ConfigMapList)) {
//            log.warn(String.format("NO CONFIG_MAP(Es) found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
//            return;
//        }
//        List<ConfigMapDTO> configMapDTOS = v1ConfigMapList.getItems().stream()
//                .map(item -> {
//                    ConfigMapDTO.ConfigMapDTOBuilder builder = ConfigMapDTO.builder();
//
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    builder.immutable(item.getImmutable());
//
//                    return builder.build();
//                })
//                .toList();
//    }
//
//    private void fetchNetworkPolicies(String clusterId) throws ApiException {
//        V1NetworkPolicyList v1NetworkPolicyList = this.networkingApi.listNetworkPolicyForAllNamespaces().execute();
//        if (ObjectUtils.isEmpty(v1NetworkPolicyList)) {
//            log.warn(String.format("NO NETWORK_POLICIES found for cluster id: %s or cloud type: %s", clusterId, this.cloudProviderType));
//            return;
//        }
//        List<NetworkPolicyDTO> networkPolicyDTOS = v1NetworkPolicyList.getItems().stream()
//                .map(item -> {
//                    NetworkPolicyDTO.NetworkPolicyDTOBuilder builder = NetworkPolicyDTO.builder();
//
//                    if (!ObjectUtils.isEmpty(item.getMetadata())) {
//                        setMetadataFields(builder, item.getMetadata(), clusterId);
//                    }
//
//                    return builder.build();
//                })
//                .toList();
//
//    }
//
//
//    private void setMetadataFields(MetadataDTO.MetadataDTOBuilder builder, V1ObjectMeta metadata, String clusterId) {
//        builder.selfLink(metadata.getSelfLink());
//        builder.resourceVersion(metadata.getResourceVersion());
//        builder.generation(metadata.getGeneration());
//        builder.name(metadata.getName());
//        builder.uid(metadata.getUid());
//        builder.namespace(metadata.getNamespace());
//        builder.generateName(metadata.getGenerateName());
//        builder.annotations(metadata.getAnnotations());
//        builder.creationTimestamp(metadata.getCreationTimestamp());
//        builder.deletionTimestamp(metadata.getDeletionTimestamp());
//        builder.clusterId(clusterId);
//        builder.cloudProviderType(this.cloudProviderType);
//    }
//}
