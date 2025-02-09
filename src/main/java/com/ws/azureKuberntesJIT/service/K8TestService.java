package com.ws.azureKuberntesJIT.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.containerservice.models.CredentialResult;
import com.azure.resourcemanager.containerservice.models.KubernetesCluster;
import com.azure.resourcemanager.resources.fluentcore.arm.models.HasName;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.ws.azureAdIntegration.util.GenericUtil;
import com.ws.configuration.AzureAuthConfigurationFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Objects;
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

    public K8TestService(AzureAuthConfigurationFactory azureAuthConfigurationFactory) {
        this.azureAuthConfigurationFactory = azureAuthConfigurationFactory;
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
        }
    }

    public void listNamespaces() {
        String rgName = "k8s-poc";
        String clusterName = "poc";
        try {
            AzureResourceManager azureResourceManager = getAzureResourceManager(clientId2, clientSecret2, tenantId2, subscriptionId2);
            KubernetesCluster cluster = azureResourceManager
                    .kubernetesClusters()
                    .getByResourceGroup(rgName, clusterName);
            setK8ClusterCredential(cluster);
            CoreV1Api api = new CoreV1Api();
            V1NamespaceList namespaceList = listNamespaces(api);
            for (V1Namespace item : namespaceList.getItems()) {
                log.info("Name: {}", GenericUtil.getOrNull(() -> item.getMetadata().getName()));
                log.info("Namespace: {}", GenericUtil.getOrNull(() -> item.getMetadata().getNamespace()));
                log.info("UID: {}", GenericUtil.getOrNull(() -> item.getMetadata().getUid()));
                log.info("Status: {}", GenericUtil.getOrNull(() -> item.getStatus()));
            }
        } catch (Exception exp) {
            throw new RuntimeException(exp.getMessage());
        }
    }

    private void setK8ClusterCredential(KubernetesCluster cluster) {
        try {
            List<CredentialResult> credentials = cluster.adminKubeConfigs();
            if (credentials == null || credentials.isEmpty()) {
                throw new RuntimeException("Error: No credentials found for the AKS cluster.");
            }
            byte[] kubeConfigContent = credentials.get(0).value();
            String kubeConfigString = new String(kubeConfigContent);
            ApiClient client = Config.fromConfig(new StringReader(kubeConfigString));
            Configuration.setDefaultApiClient(client);
        } catch (Exception exp) {
            throw new RuntimeException(exp.getMessage());
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
            V1NamespaceList namespaceList = listNamespaces(coreV1Api);

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


    private V1NamespaceList listNamespaces(CoreV1Api api) throws ApiException {
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


    }


}
