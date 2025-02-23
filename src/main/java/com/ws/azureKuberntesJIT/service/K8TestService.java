package com.ws.azureKuberntesJIT.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.containerservice.models.CredentialResult;
import com.azure.resourcemanager.containerservice.models.KubernetesCluster;
import com.azure.resourcemanager.resources.fluentcore.arm.models.HasName;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.ws.azureAdIntegration.repository.AzureApplicationRepository;
import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import com.ws.azureKuberntesJIT.enttity.K8LabelSelector;
import com.ws.azureKuberntesJIT.enttity.K8Role;
import com.ws.azureKuberntesJIT.enttity.K8RolePolicyRule;
import com.ws.azureKuberntesJIT.repository.K8RoleRepository;
import com.ws.configuration.AzureAuthConfigurationFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class K8TestService {
    final String clientId2 = "f741d2f8-8ec5-4246-9051-96fd8f041267";
    final String clientSecret2 = "C6n8Q~Pe3lYUXaRp6gLNOZUK~uM5UUSkqP~9JbuY";
    final String tenantId2 = "0079de83-6146-45cb-a189-5d5b03507ce8";
    final String subscriptionId2 = "15b85f1d-1983-469c-a593-46fe8fc514f7";


    final String clientId = "cb51e8d1-519c-4e18-9b2f-28d53e6badd1";
    final String clientSecret = "3F18Q~iM8DjCXg7rL~2.BZZPtdGNAzfOf2qXRdhC";
    final String tenantId = "f875ebf8-f5f0-4915-a2c9-4442e0118fd2";
    final String subscriptionId = "4769af8e-ca3d-448d-bd1a-80e03ed94158";
    final AzureAuthConfigurationFactory azureAuthConfigurationFactory;
    final K8RoleRepository k8RoleRepository;
    final TestEntityRepo testEntityRepo;
    final TestListEntityRepo testListEntityRepo;
    final AzureApplicationRepository azureApplicationRepository;

    @Autowired
    public K8TestService(AzureAuthConfigurationFactory azureAuthConfigurationFactory, K8RoleRepository k8RoleRepository, TestEntityRepo testEntityRepo, TestListEntityRepo testListEntityRepo, AzureApplicationRepository azureApplicationRepository) {
        this.azureAuthConfigurationFactory = azureAuthConfigurationFactory;
        this.k8RoleRepository = k8RoleRepository;
        this.testEntityRepo = testEntityRepo;
        this.testListEntityRepo = testListEntityRepo;
        this.azureApplicationRepository = azureApplicationRepository;
    }

    private AzureResourceManager getAzureResourceManager(String clientId, String clientSecret, String tenantId, String subscriptionId) {
        return azureAuthConfigurationFactory.createAzureResourceClient(clientId, clientSecret, tenantId, subscriptionId);
    }

    private AzureResourceManager getAzureResourceManager() {
        return azureAuthConfigurationFactory.createAzureResourceClient(clientId, clientSecret, tenantId, subscriptionId);
    }


    public List<String> getRGList() {
        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId2, clientSecret2, tenantId2, subscriptionId2);
        List<ResourceGroup> resourceGroups = azureResourceManager.resourceGroups().list().stream().toList();
        return resourceGroups.stream().map((HasName::name)).collect(Collectors.toList());
    }


    //  cd-workload -->  wl-test-private (cluster)
    //  k8s-poc -->   poc (cluster)
    public void listK8Clusters() {
//        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId2, clientSecret2, tenantId2, subscriptionId2);
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        List<KubernetesCluster> kubernetesClusters = azureResourceManager.kubernetesClusters().list().stream().toList();
        log.info("total clusters: {}", kubernetesClusters.size());
        for (KubernetesCluster kubernetesCluster : kubernetesClusters) {
            log.info("Name: {}", kubernetesCluster.name());
            log.info("ID: {}", kubernetesCluster.id());
            log.info("Type: {}", kubernetesCluster.type());
            log.info("RG name: {}", kubernetesCluster.resourceGroupName());
            log.info("creds: {}", kubernetesCluster.adminKubeConfigs().size());
            ;
            log.info(" ");
        }
    }

    public static String[] extractServerAndTokenFromKubeConfigYAML(String config) {
        String[] result = new String[2];

        String serverPrefix = "server: ";
        int serverStart = config.indexOf(serverPrefix) + serverPrefix.length();
        int serverEnd = config.indexOf("\n", serverStart);
        result[0] = config.substring(serverStart, serverEnd).trim();

        String tokenPrefix = "token: ";
        int tokenStart = config.indexOf(tokenPrefix) + tokenPrefix.length();
        int tokenEnd = config.indexOf("\n", tokenStart);
        result[1] = config.substring(tokenStart, tokenEnd).trim();

        return result;
    }


    public void listResources() {
        String rgName = "ws-test-aks-rg";
        String clusterName = "ws-test-aks-cluster-1";
        try {
            AzureResourceManager azureResourceManager = getAzureResourceManager(clientId, clientSecret, tenantId, subscriptionId);
            KubernetesCluster cluster = azureResourceManager
                    .kubernetesClusters()
                    .getByResourceGroup(rgName, clusterName);
            String kubeConfigContent = new String(cluster.adminKubeConfigs().get(0).value());
//            System.out.println(kubeConfigContent);
            String[] extractedValues = extractServerAndTokenFromKubeConfigYAML(kubeConfigContent);

//            System.out.println("Server: " + extractedValues[0]);
//            System.out.println("Token: " + extractedValues[1]);

            initialiseK8Client(extractedValues[0], extractedValues[1]);

        } catch (Exception exp) {
            throw new RuntimeException(exp.getMessage());
        }
    }

    @Transactional
    public void savingData1() {
        log.info("---------");
        TestEntity testEntity = new TestEntity();
        testEntity.setName("Test name 1");
        log.info("---------");

        List<TestListEntity> entities = new LinkedList<>();
        TestListEntity entity1 = new TestListEntity();
        entity1.setName("Child 1");
        log.info("---------");
        entity1.setTestEntity(testEntity);
        log.info("---------");


        TestListEntity entity2 = new TestListEntity();
        entity2.setName("Child 2");
        log.info("---------");
        entity2.setTestEntity(testEntity);
        log.info("---------");

        entities.add(entity1);
        log.info("---------");
        entities.add(entity2);
        log.info("---------");

        testEntity.setEntities(entities);
        log.info("Done");
        testEntityRepo.save(testEntity);
        log.info("saved");


//        AzureApplication application = new AzureApplication();
//        application.setObjectId("Object 12");
//
//        List<AzureAppRoles> roles = new LinkedList<>();
//        AzureAppRoles role1 = new AzureAppRoles();
//        role1.setDisplayName("D1");
//        role1.setApplication(application);
//        AzureAppRoles role2 = new AzureAppRoles();
//        role2.setDisplayName("D1");
//        role2.setApplication(application);
//
//        roles.add(role1);
//        roles.add(role2);
//
//        application.setAppRoles(roles);
//        azureApplicationRepository.save(application);
    }

    @Transactional
    public void savingData() {
        try {
            String rgName = "ws-test-aks-rg";
            String clusterName = "ws-test-aks-cluster-1";
            AzureResourceManager azureResourceManager = getAzureResourceManager(clientId, clientSecret, tenantId, subscriptionId);
            KubernetesCluster cluster = azureResourceManager
                    .kubernetesClusters()
                    .getByResourceGroup(rgName, clusterName);
            String kubeConfigContent = new String(cluster.adminKubeConfigs().get(0).value());
            String[] extractedValues = extractServerAndTokenFromKubeConfigYAML(kubeConfigContent);

            ApiClient client = Config.fromToken(extractedValues[0], extractedValues[1]);
            client.setVerifyingSsl(false);
            Configuration.setDefaultApiClient(client);
            RbacAuthorizationV1Api rbacApi = new RbacAuthorizationV1Api();
            V1ClusterRoleList clusterRoleList = rbacApi.listClusterRole().execute();
            if (clusterRoleList == null) {
                return;
            }

            List<K8Role> clusterRoles = new ArrayList<>();

            for (V1ClusterRole item : clusterRoleList.getItems()) {
                K8Role k8Role = K8Role.builder()
                        .kind(item.getKind())
                        .roleType(K8ResourceLevel.CLUSTER)
                        .build();

                Set<K8RolePolicyRule> rules = new HashSet<>();
                for (V1PolicyRule policyRule : item.getRules()) {
                    K8RolePolicyRule rule = K8RolePolicyRule.builder()
                            .roleUID(item.getMetadata().getUid())
                            .kubernetesRole(k8Role)
                            .build();
                    rules.add(rule);
                }
                k8Role.setK8RolePolicyRules(rules);
                clusterRoles.add(k8Role);
                break;
            }
//            log.info("clusterRoles: {}", clusterRoles);
//            log.info("clusterRoles: {}",clusterRoles.size());
            k8RoleRepository.saveAll(clusterRoles);
        } catch (Exception exp) {
            throw new RuntimeException(exp.getMessage());
        }
    }

    private void initialiseK8Client(String clusterURL, String clusterStaticToken) {
        try {
            ApiClient client = Config.fromToken(clusterURL, clusterStaticToken);
            client.setVerifyingSsl(false);
            Configuration.setDefaultApiClient(client);

            CoreV1Api coreV1Api = new CoreV1Api();
            AppsV1Api appsV1Api = new AppsV1Api();
            ApiextensionsV1Api apiextensionsV1Api = new ApiextensionsV1Api();
            NetworkingV1Api networkingApi = new NetworkingV1Api();
            RbacAuthorizationV1Api rbacApi = new RbacAuthorizationV1Api();

            V1NamespaceList namespaceList = coreV1Api.listNamespace().execute();
            V1NodeList nodesList = coreV1Api.listNode().execute();
            V1PersistentVolumeClaimList persistentVolumeClaimListList = coreV1Api.listPersistentVolumeClaimForAllNamespaces().execute();
            V1ServiceAccountList serviceAccountList = coreV1Api.listServiceAccountForAllNamespaces().execute();
            V1DeploymentList deploymentList = appsV1Api.listDeploymentForAllNamespaces().execute();
            V1CustomResourceDefinitionList definitionList = apiextensionsV1Api.listCustomResourceDefinition().execute();
            V1SecretList secretList = coreV1Api.listSecretForAllNamespaces().execute();
            V1PersistentVolumeList persistentVolumeList = coreV1Api.listPersistentVolume().execute();
            V1NetworkPolicyList networkPolicyList = networkingApi.listNetworkPolicyForAllNamespaces().execute();
            V1ConfigMapList configMapList = coreV1Api.listConfigMapForAllNamespaces().execute();
            V1ClusterRoleList clusterRoleList = rbacApi.listClusterRole().execute();
            V1RoleList namespaceList1 = rbacApi.listRoleForAllNamespaces().execute();
            V1ClusterRoleBindingList clusterRoleBindingList = rbacApi.listClusterRoleBinding().execute();
            V1RoleBindingList namespaceRoleHindingList = rbacApi.listRoleBindingForAllNamespaces().execute();

            // Process results
            List<String> namespaces = namespaceList.getItems().stream()
                    .map(V1Namespace::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            List<String> deployments = deploymentList.getItems().stream()
                    .map(V1Deployment::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            List<String> nodes = nodesList.getItems().stream()
                    .map(V1Node::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            List<String> definitions = definitionList.getItems().stream()
                    .map(V1CustomResourceDefinition::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            List<String> secrets = secretList.getItems().stream()
                    .map(V1Secret::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            List<String> serviceAccounts = serviceAccountList.getItems().stream()
                    .map(V1ServiceAccount::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            List<String> persistentVolumes = persistentVolumeList.getItems().stream()
                    .map(V1PersistentVolume::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            List<String> persistentVolumeClaims = persistentVolumeClaimListList.getItems().stream()
                    .map(V1PersistentVolumeClaim::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            List<String> networkPolicies = networkPolicyList.getItems().stream()
                    .map(V1NetworkPolicy::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            List<String> configMaps = configMapList.getItems().stream()
                    .map(V1ConfigMap::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            List<String> clusterRoles = clusterRoleList.getItems().stream()
                    .map(V1ClusterRole::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            int totalClusterRules = 0;
            int counet = 0;
            log.info(" ");
            for (V1ClusterRole item : clusterRoleList.getItems()) {
                V1AggregationRule v1AggregationRule = item.getAggregationRule();
                if (!ObjectUtils.isEmpty(v1AggregationRule)) {
                    List<V1LabelSelector> k8LabelSelectors = v1AggregationRule.getClusterRoleSelectors();
                    for (V1LabelSelector k8LabelSelector : k8LabelSelectors) {
                        if (!CollectionUtils.isEmpty(k8LabelSelector.getMatchExpressions())) {

                            log.info("map: {}",  k8LabelSelector.getMatchExpressions());
                            counet += k8LabelSelector.getMatchLabels().size();
                        }
                    }
                }


                if (!CollectionUtils.isEmpty(item.getRules())) {
                    totalClusterRules += item.getRules().size();
                }
            }
            log.info(" ");
            log.info("counet: {}", counet);
            log.info("totalClusterRules: {}", totalClusterRules);

            int totalNameSpaceRules = 0;
            for (V1Role item : namespaceList1.getItems()) {
                if (!CollectionUtils.isEmpty(item.getRules())) {
                    totalNameSpaceRules += item.getRules().size();
                }
            }
            log.info("totalNameSpaceRules: {}", totalNameSpaceRules);


            List<String> namespaceRoles = namespaceList1.getItems().stream()
                    .map(V1Role::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            List<String> clusterRoleBindings = clusterRoleBindingList.getItems().stream()
                    .map(V1ClusterRoleBinding::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            List<String> namespaceRoleBindings = namespaceRoleHindingList.getItems().stream()
                    .map(V1RoleBinding::getMetadata).filter(Objects::nonNull)
                    .map(V1ObjectMeta::getName)
                    .toList();

            log.info(" ");
            log.info("clusterRoles size: {}", clusterRoles.size());
            log.info("NamespaceRoles size: {}", namespaceRoles.size());
            log.info(" ");

            log.info("Namespaces: {}", namespaces);
            log.info("Deployments: {}", deployments);
            log.info("Nodes: {}", nodes);
            log.info("DefinitionList: {}", definitions);
            log.info("Secrets: {}", secrets);
            log.info("ServiceAccounts: {}", serviceAccounts);
            log.info("PersistentVolumes: {}", persistentVolumes);
            log.info("PersistentVolumeClaims: {}", persistentVolumeClaims);
            log.info("NetworkPolicies: {}", networkPolicies);
            log.info("configMaps: {}", configMaps);
            log.info("ClusterRoles: {}", clusterRoles);
            log.info("NamespaceRoles: {}", namespaceRoles);
            log.info("ClusterRoleBindings: {}", clusterRoleBindings);
            log.info("NamespaceRoleBindings: {}", namespaceRoleBindings);
        } catch (Exception e) {
            log.error("Error: {}", e.getMessage());
            throw new RuntimeException(e.getMessage());
        }
    }


    /**
     * 1. Fetch all k8 clusters from the Azure using Azure SDK
     * 2. Get the credentials from the k8 clusters
     * 3. Use the credentials for these clusters and Interact with the K8 aoi client to fetch the resources within
     */
    // Resource Groups to be used -->
    // cd-workload
    // k8s-poc
    public void listK8Resources_legacy_code() {
        try {
            // Fetch the Azure AKS cluster's admin kubeconfig credentials
            AzureResourceManager azureResourceManager = getAzureResourceManager(clientId2, clientSecret2, tenantId2, subscriptionId2);

            PagedIterable<KubernetesCluster> kubernetesClusters = azureResourceManager.kubernetesClusters().listByResourceGroup("rgGroup");
            kubernetesClusters.stream().forEach((kubernetesCluster -> {
                String name = kubernetesCluster.name();
                String rgName = kubernetesCluster.resourceGroupName();
            }));

            KubernetesCluster cluster = azureResourceManager
                    .kubernetesClusters()
                    .getByResourceGroup("resourceGroupName", "aksClusterName");

            // Get the admin kubeconfig credentials (returns a List of CredentialResult)
            List<CredentialResult> credentials = cluster.adminKubeConfigs();
            if (credentials == null || credentials.isEmpty()) {
                log.error("Error: No credentials found for the AKS cluster.");
                return;
            }

            // Extract the kubeconfig content as byte array and convert it into a String
            byte[] kubeConfigContent = credentials.get(0).value();
            String kubeConfigString = new String(kubeConfigContent); // Convert byte[] to String
            // Use Config.fromConfig() to get ApiClient from the kubeconfig string
            ApiClient client = Config.fromConfig(new StringReader(kubeConfigString));
            // Set the default API client
            Configuration.setDefaultApiClient(client);

            // Create the CoreV1Api instance to interact with Kubernetes
            CoreV1Api coreV1Api = new CoreV1Api();
            // Create RBAC coreV1Api to interact with Roles
            RbacAuthorizationV1Api rbacApi = new RbacAuthorizationV1Api();
            // Create APP coreV1Api client to interact with Deployments, ReplicaSets
            AppsV1Api appsApi = new AppsV1Api();
            // Create Storage coreV1Api client to interact with Storages related resources like StorageClass
            StorageV1Api storageV1Api = new StorageV1Api();
            // To be used to fetch Custom Resource Definitions (CRD)
            ApiextensionsV1Api apiextensionsV1Api = new ApiextensionsV1Api();


            // List custom resources
            V1CustomResourceDefinitionList v1CustomResourceDefinitionList = listCustomResourceDefinition(apiextensionsV1Api);

            // List down storage classes
            V1StorageClassList v1StorageClassList = listStorageClasses(storageV1Api);

            // List down persistent volume
            V1PersistentVolumeList v1PersistentVolumeList = listPV(coreV1Api);

            // List down Nodes
            V1NodeList viNodeList = listNodes(coreV1Api);

            // List down all the Namespaces
            V1NamespaceList namespaceList = listResources(coreV1Api);

            // LIst down service accounts
            V1ServiceAccountList v1ServiceAccountList = listServiceAccounts(namespaceList, coreV1Api);

            // List all pods in a namespace
            V1PodList podList = listPods(namespaceList, coreV1Api);

            // List down secrets
            V1SecretList v1SecretList = listSecrestForNamespace(namespaceList, coreV1Api);

            // List down PVCs
            V1PersistentVolumeClaimList v1PersistentVolumeClaimList = listPVC(coreV1Api, namespaceList);

            // List down roles
            V1RoleList v1RoleList = listNamespaceRoles(rbacApi, namespaceList);


            // List dowm Namespace roles-binding
            V1RoleBindingList v1RoleBindingList = listNamespaceRoleBindings(rbacApi, namespaceList);

            // List down cluster roles
            V1ClusterRoleList v1ClusterRoleList = listClusterRoles(rbacApi);

            // List down cluster roles-binding
            V1ClusterRoleBindingList v1ClusterRoleBindingList = listClusterRoleBindings(rbacApi);

            // List down deployment
            V1DeploymentList v1DeploymentList = listDeployments(appsApi, namespaceList);

            // Fetch POD SECURITY POLICIES via Namespace annotations
            fetchPodSecurityPolicies(namespaceList.getItems().get(0));

            // Fetch other resources
            fetchOtherResources(client);

            // Print the names of all pods in the 'default' namespace
            podList.getItems().forEach(pod -> System.out.println("Pod name: " + Objects.requireNonNull(pod.getMetadata()).getName()));
        } catch (IOException exp) {
            log.error("Error interacting with AKS resources: {}", exp.getMessage(), exp);
        } catch (Exception exp) {
            log.error("Unexpected error: {}", exp.getMessage(), exp);
        }
    }


    private V1NamespaceList listResources(CoreV1Api api) throws ApiException {
        return api.listNamespace().execute();
    }

    private V1NodeList listNodes(CoreV1Api api) throws ApiException {
        return api.listNode().execute();
    }

    private V1PodList listPods(V1NamespaceList v1NamespaceList, CoreV1Api api) throws ApiException {
        return api.listNamespacedPod(Objects.requireNonNull(v1NamespaceList.getItems().get(0).getMetadata()).getName()).execute();
    }

    private V1PersistentVolumeList listPV(CoreV1Api api) throws ApiException {
        return api.listPersistentVolume().execute();
    }

    private V1PersistentVolumeClaimList listPVC(CoreV1Api api, V1NamespaceList v1NamespaceList) throws ApiException {
        return api.listNamespacedPersistentVolumeClaim(Objects.requireNonNull(v1NamespaceList.getItems().get(0).getMetadata()).getName()).execute();
    }

    private V1ServiceAccountList listServiceAccounts(V1NamespaceList v1NamespaceList, CoreV1Api api) throws ApiException {
        return api.listNamespacedServiceAccount(Objects.requireNonNull(v1NamespaceList.getItems().get(0).getMetadata()).getName()).execute();
    }

    private V1SecretList listSecrestForNamespace(V1NamespaceList v1NamespaceList, CoreV1Api api) throws ApiException {
        return api.listNamespacedSecret(Objects.requireNonNull(v1NamespaceList.getItems().get(0).getMetadata()).getName()).execute();
    }

    private static V1RoleList listNamespaceRoles(RbacAuthorizationV1Api api, V1NamespaceList v1NamespaceList) throws ApiException {
        return api.listNamespacedRole(Objects.requireNonNull(v1NamespaceList.getItems().get(0).getMetadata()).getName()).execute();
    }

    private static V1RoleBindingList listNamespaceRoleBindings(RbacAuthorizationV1Api api, V1NamespaceList v1NamespaceList) throws ApiException {
        return api.listNamespacedRoleBinding(Objects.requireNonNull(v1NamespaceList.getItems().get(0).getMetadata()).getName()).execute();
    }

    private static V1ClusterRoleList listClusterRoles(RbacAuthorizationV1Api api) throws ApiException {
        return api.listClusterRole().execute();
    }

    private static V1ClusterRoleBindingList listClusterRoleBindings(RbacAuthorizationV1Api api) throws ApiException {
        return api.listClusterRoleBinding().execute();
    }

    private static V1DeploymentList listDeployments(AppsV1Api api, V1NamespaceList v1NamespaceList) throws ApiException {
        return api.listNamespacedDeployment(Objects.requireNonNull(v1NamespaceList.getItems().get(0).getMetadata()).getName()).execute();
    }

    private static V1StorageClassList listStorageClasses(StorageV1Api api) throws ApiException {
        return api.listStorageClass().execute();
    }

    private V1CustomResourceDefinitionList listCustomResourceDefinition(ApiextensionsV1Api apiextensionsV1Api) throws ApiException {
        return apiextensionsV1Api.listCustomResourceDefinition().execute();
    }

    /*
     * There is no dedicated API resource to fetch PodSecurity policies directly in Kubernetes 1.25 and beyond. PodSecurity policies are enforced via
     * namespace annotations, which are part of the PodSecurityAdmission controller, and Kubernetes does not expose a separate "PodSecurity" API resource.
     * The three main annotations are:
     *      enforce: To enforce the PodSecurity standard.
     *      audit: To audit the namespace for PodSecurity policy compliance.
     *      warn: To generate warnings for non-compliant pods.
     *
     * And then we have 3 levels:
     *      privileged
     *      baseline
     *      restricted
     *   */
    private void fetchPodSecurityPolicies(V1Namespace v1Namespace) {
        // Check for PodSecurity annotations
        String enforcePolicy = v1Namespace.getMetadata().getAnnotations().get("pod-security.kubernetes.io/enforce");
        String auditPolicy = v1Namespace.getMetadata().getAnnotations().get("pod-security.kubernetes.io/audit");
        String warnPolicy = v1Namespace.getMetadata().getAnnotations().get("pod-security.kubernetes.io/warn");

        // Output the PodSecurity policies (if configured)
        if (enforcePolicy != null) {
            System.out.println("Enforce Policy: " + enforcePolicy);
        }
        if (auditPolicy != null) {
            System.out.println("Audit Policy: " + auditPolicy);
        }
        if (warnPolicy != null) {
            System.out.println("Warn Policy: " + warnPolicy);
        }
    }


    private void fetchOtherResources(ApiClient client) throws ApiException {
        AppsV1Api appsApi = new AppsV1Api(client);
        BatchV1Api batchApi = new BatchV1Api(client);
        CoreV1Api coreApi = new CoreV1Api(client);
        NetworkingV1Api networkingApi = new NetworkingV1Api(client);


        // Fetch ReplicaSets
        V1ReplicaSetList replicaSets = appsApi.listNamespacedReplicaSet("").execute();

        // Fetch StatefulSets (using the same AppsV1Api)
        V1StatefulSetList statefulSets = appsApi.listNamespacedStatefulSet("").execute();

        // Fetch Jobs
        V1JobList jobs = batchApi.listNamespacedJob("").execute();

        // Fetch CronJobs
        V1CronJobList cronJobs = batchApi.listNamespacedCronJob("").execute();

        // Fetch ConfigMaps
        V1ConfigMapList configMaps = coreApi.listNamespacedConfigMap("").execute();

        // Fetch Ingress
        V1IngressList ingresses = networkingApi.listNamespacedIngress("").execute();

        // Fetch NetworkPolicies
        V1NetworkPolicyList networkPolicies = networkingApi.listNamespacedNetworkPolicy("").execute();

        DiscoveryApi discoveryApi = new DiscoveryApi(client);

    }


    /**
     * @param namespace       -> namespace name
     * @param roleBindingName -> Must be UNIQUE
     * @param roleName        -> The name of the target namespace role
     * @param userName        ->
     * @param roleApiVersion  -> getting from metaData -> apiVersion
     * @param requestedByType -> User | ServiceAccount
     * @return
     * @throws ApiException
     */
    public V1RoleBinding assignRoleToUser(String namespace,
                                          String roleBindingName,
                                          String roleName,
                                          String userName,
                                          String roleApiVersion,
                                          String requestedByType,
                                          RbacAuthorizationV1Api api) throws ApiException {
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(roleBindingName);
        metadata.setNamespace(namespace);

        V1RoleRef roleRef = new V1RoleRef();
        roleRef.setApiGroup(roleApiVersion); //<-- example its gonna be -> "rbac.authorization.k8s.io" for Role, RoleBinding, ClusterRole, ClusterRoleBinding
        roleRef.setKind("Role");
        roleRef.setName(roleName);

        RbacV1Subject subject = new RbacV1Subject();
        subject.setKind(requestedByType);
        subject.setName(userName);
        //        subject.setKind("Group"); // <-- IF the rople is supposed to be assigned to a Griup
        //        subject.setName(groupName); // <--- group  name of the group
        subject.setApiGroup(roleApiVersion);

        V1RoleBinding roleBinding = new V1RoleBinding();
        roleBinding.setMetadata(metadata);
        roleBinding.setRoleRef(roleRef);
        roleBinding.addSubjectsItem(subject);

        return api.createNamespacedRoleBinding(namespace, roleBinding).execute();
    }


    /**
     * @param roleBindingName -> Must be uniqye
     * @param clusterRoleName -> The name of the target cluster role
     * @param userName        ->
     * @return
     * @throws ApiException
     */
    public V1ClusterRoleBinding assignClusterRoleToUser(String clusterRoleName,
                                                        String roleBindingName,
                                                        String userName,
                                                        String roleApiVersion,
                                                        String requestedByType,
                                                        RbacAuthorizationV1Api api) throws ApiException {
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(roleBindingName);
        metadata.setNamespace(null); // <--- as it is gonna be the cluster wide role binding

        V1RoleRef roleRef = new V1RoleRef();
        roleRef.setKind("ClusterRole");
        roleRef.setName(clusterRoleName); // This is the cluster role you're assigning
        roleRef.setApiGroup(roleApiVersion);

        RbacV1Subject subject = new RbacV1Subject();
        subject.setKind(requestedByType);
        subject.setName(userName);
//        subject.setKind("Group"); // <-- IF the rople is supposed to be assigned to a Griup
//        subject.setName(groupName); // <--- group  name of the group
        subject.setApiGroup(roleApiVersion);

        V1ClusterRoleBinding clusterRoleBinding = new V1ClusterRoleBinding();
        clusterRoleBinding.setMetadata(metadata);
        clusterRoleBinding.setRoleRef(roleRef);
        clusterRoleBinding.setSubjects(Collections.singletonList(subject));

        return api.createClusterRoleBinding(clusterRoleBinding).execute();
    }


    public V1RoleBinding assignRoleToUserForSpecificResource(String roleName,
                                                             String roleBindingName,
                                                             String userName,
                                                             String resourceName,
                                                             String namespace,
                                                             String apiVersion,
                                                             String requestedByType,
                                                             RbacAuthorizationV1Api api) throws ApiException {
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(roleBindingName);
        metadata.setNamespace(namespace);  // Specify the namespace

        V1RoleRef roleRef = new V1RoleRef();
        roleRef.setKind("Role");
        roleRef.setName(roleName);  // The Role assigned to the user
        roleRef.setApiGroup(apiVersion);

        RbacV1Subject subject = new RbacV1Subject();
        subject.setKind("User");
        subject.setName(userName);
        subject.setApiGroup("rbac.authorization.k8s.io");  // Correct apiGroup for User

        // Define permissions on a specific resource instance (e.g., "secret1")
        V1PolicyRule rule = new V1PolicyRule();
        rule.setApiGroups(Collections.singletonList(""));  // API group for core resources
        rule.setResources(Collections.singletonList("secrets"));  // Resource type (secrets)
        rule.setResourceNames(Collections.singletonList(resourceName));  // Specific resource name ("secret1")
        rule.setVerbs(Collections.singletonList("get"));  // Action the user can perform


        V1Role role1 = null;
        for (V1Role item : api.listNamespacedRole(namespace).execute().getItems()) {
            if (item.getMetadata().getName().contains(roleName)) {
                role1 = item;
                break;
            }
        }

//        V1Role role = new V1Role();
//        role.setMetadata(metadata);
//        role.setRules(Collections.singletonList(rule));  // Define rules
        if (!ObjectUtils.isEmpty(role1)) {
            role1.setRules(Collections.singletonList(rule));
            role1.setMetadata(metadata);
        }


        // Create Role if it doesn't exist
        api.createNamespacedRole(namespace, role1);

        // Create the RoleBinding for the user
        V1RoleBinding roleBinding = new V1RoleBinding();
        roleBinding.setMetadata(metadata);
        roleBinding.setRoleRef(roleRef);
        roleBinding.setSubjects(Collections.singletonList(subject));


        api.replaceNamespacedRole(roleName, namespace, role1).execute();

        return api.createNamespacedRoleBinding(namespace, roleBinding).execute();
    }


    public void removeK8Role(String namespace, String roleBindingName, RbacAuthorizationV1Api api) throws ApiException {
        api.deleteNamespacedRoleBinding(roleBindingName, namespace).execute();
        api.deleteClusterRoleBinding(roleBindingName).execute();
    }


    public void modifyAndAssignRoleToUser(String userName, String roleName, String namespace,
                                          boolean isClusterRole, List<String> resources, List<String> verbs,
                                          RbacAuthorizationV1Api api) throws ApiException {
        try {
            if (isClusterRole) {
                // Fetch the existing ClusterRole
                V1ClusterRole existingClusterRole = api.readClusterRole(roleName).execute();

                if (ObjectUtils.isEmpty(existingClusterRole)) {
                    throw new RuntimeException("No such roles found with provided role name: " + roleName);
                }

                // Add requested resources and verbs to the existing rules
                for (V1PolicyRule rule : existingClusterRole.getRules()) {
                    if (rule.getResources().containsAll(resources)) {
                        // If the resources already exist, add the requested verbs
                        rule.getVerbs().addAll(verbs);
                    } else {
                        // Otherwise, add a new resource entry with the requested verbs
                        rule.getResourceNames().addAll(resources);
                        rule.getVerbs().addAll(verbs);
                    }
                }

                // Update the ClusterRole in Kubernetes
                api.replaceClusterRole(roleName, existingClusterRole).execute();

                // Create a ClusterRoleBinding to bind the updated ClusterRole to the user
                V1ClusterRoleBinding clusterRoleBinding = new V1ClusterRoleBinding()
                        .metadata(new V1ObjectMeta().name(roleName + "-binding-" + userName))
                        .subjects(Collections.singletonList(new RbacV1Subject().kind("User").name(userName).apiGroup("rbac.authorization.k8s.io")))
                        .roleRef(new V1RoleRef().kind("ClusterRole").name(roleName).apiGroup("rbac.authorization.k8s.io/v1"));

                api.createClusterRoleBinding(clusterRoleBinding).execute();
                System.out.println("ClusterRoleBinding created for user: " + userName);


            } else {
                // Fetch the existing Role (in the given namespace)
                V1Role existingRole = api.readNamespacedRole(roleName, namespace).execute();

                if (ObjectUtils.isEmpty(existingRole)) {
                    throw new RuntimeException("No such roles found with provided role name: " + roleName);
                }
                // Add requested resources and verbs to the existing rules
                for (V1PolicyRule rule : existingRole.getRules()) {
                    if (rule.getResources().containsAll(resources)) {
                        // If the resources already exist, add the requested verbs
                        rule.getVerbs().addAll(verbs);
                    } else {
                        // Otherwise, add a new resource entry with the requested verbs
                        rule.getResourceNames().addAll(resources);
                        rule.getVerbs().addAll(verbs);
                    }
                }

                // Update the Role in Kubernetes
                api.replaceNamespacedRole(roleName, namespace, existingRole).execute();

                // Create a RoleBinding to bind the updated Role to the user
                V1RoleBinding roleBinding = new V1RoleBinding()
                        .metadata(new V1ObjectMeta().name(roleName + "-binding-" + userName).namespace(namespace))
                        .subjects(Collections.singletonList(new RbacV1Subject().kind("User").name(userName).apiGroup("rbac.authorization.k8s.io")))
                        .roleRef(new V1RoleRef().kind("Role").name(roleName).apiGroup("rbac.authorization.k8s.io/v1"));

                api.createNamespacedRoleBinding(namespace, roleBinding).execute();
                System.out.println("RoleBinding created for user: " + userName + " in namespace: " + namespace);
            }

        } catch (Exception e) {
            System.err.println("Error modifying and assigning role to user: " + e.getMessage());
            throw new RuntimeException(e.getMessage());
        }
    }
}
