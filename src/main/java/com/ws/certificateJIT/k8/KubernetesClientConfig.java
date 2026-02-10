package com.ws.certificateJIT.k8;


import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.containerservice.models.KubernetesCluster;
import com.ws.configuration.AzureAuthConfigurationFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class KubernetesClientConfig {
    final AzureAuthConfigurationFactory azureAuthConfigurationFactory;

    @Autowired
    public KubernetesClientConfig(AzureAuthConfigurationFactory azureAuthConfigurationFactory) {
        this.azureAuthConfigurationFactory = azureAuthConfigurationFactory;
    }


    /**
     * Initialize Kubernetes API client using kubeconfig
     * Automatically reads from ~/.kube/config
     * //
     */
//    @Bean
//    public ApiClient kubernetesApiClient() throws Exception {
//        ApiClient client = Config.defaultClient();
//        client.setConnectTimeout(10000);
//        client.setReadTimeout(30000);
//        return client;
//    }
    @Bean
    public ApiClient kubernetesApiClient() throws Exception {
        return initializeK8Client();
    }

//
//    private ApiClient initializeK8Client() {
//        String rgName = "ws-test-aks-rg";
//        String clusterName = "ws-test-aks-cluster-1";
//        AzureResourceManager azureResourceManager = getAzureResourceManager("cb51e8d1-519c-4e18-9b2f-28d53e6badd1", "yye8Q~FxfhNLvs07nM3PIPF0.H0zAvcvQ1Z5FcCJ",
//                "f875ebf8-f5f0-4915-a2c9-4442e0118fd2", "4769af8e-ca3d-448d-bd1a-80e03ed94158");
//        KubernetesCluster cluster = azureResourceManager
//                .kubernetesClusters()
//                .getByResourceGroup(rgName, clusterName);
//        String kubeConfigContent = new String(cluster.adminKubeConfigs().get(0).value());
//        String[] extractedValues = extractServerAndTokenFromKubeConfigYAML(kubeConfigContent);
//
//        ApiClient client = Config.fromToken(extractedValues[0], extractedValues[1]);
//        client.setVerifyingSsl(false);
//        String token = extractedValues[1];  // This is your Bearer token
//        client.setApiKey("Bearer " + token);  // Set it explicitly
//        client.setApiKeyPrefix("Authorization", "Bearer");  // Also set the prefix
//
//        io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);
//        return client;
//    }


    private ApiClient initializeK8Client() {
        String rgName = "ws-test-aks-rg";
        String clusterName = "ws-test-aks-cluster-1";
        AzureResourceManager azureResourceManager = getAzureResourceManager("cb51e8d1-519c-4e18-9b2f-28d53e6badd1", "yye8Q~FxfhNLvs07nM3PIPF0.H0zAvcvQ1Z5FcCJ",
                "f875ebf8-f5f0-4915-a2c9-4442e0118fd2", "4769af8e-ca3d-448d-bd1a-80e03ed94158");
        KubernetesCluster cluster = azureResourceManager
                .kubernetesClusters()
                .getByResourceGroup(rgName, clusterName);
        String kubeConfigContent = new String(cluster.adminKubeConfigs().get(0).value());
//        System.out.println(" ");
//        System.out.println(kubeConfigContent);
//        System.out.println(" ");
        String[] extractedValues = extractServerTokenAndCaCertBase64FromKubeConfigYAML(kubeConfigContent);

        String serverUrl = extractedValues[0];
        String bearerToken = extractedValues[1];
        log.info("bearerToken: {}", bearerToken);


        ApiClient client = Config.fromToken(serverUrl, bearerToken);
        client.setVerifyingSsl(false);
        client.setApiKeyPrefix("Bearer");

        io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);
        return client;
    }


    private AzureResourceManager getAzureResourceManager(String clientId, String clientSecret, String tenantId, String subscriptionId) {
        return azureAuthConfigurationFactory.createAzureResourceClient(clientId, clientSecret, tenantId, subscriptionId);
    }


    private static String[] extractServerAndTokenFromKubeConfigYAML(String config) {
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


    private static String[] extractServerTokenAndCaCertBase64FromKubeConfigYAML(String config) {
        String[] result = new String[3];

        String serverPrefix = "server: ";
        int serverStart = config.indexOf(serverPrefix) + serverPrefix.length();
        int serverEnd = config.indexOf("\n", serverStart);
        result[0] = config.substring(serverStart, serverEnd).trim();

        String tokenPrefix = "token: ";
        int tokenStart = config.indexOf(tokenPrefix) + tokenPrefix.length();
        int tokenEnd = config.indexOf("\n", tokenStart);
        result[1] = config.substring(tokenStart, tokenEnd).trim();

        String caCertPrefix = "certificate-authority-data: ";
        int caCertStart = config.indexOf(caCertPrefix) + caCertPrefix.length();
        int caCertEnd = config.indexOf("\n", caCertStart);
        result[2] = config.substring(caCertStart, caCertEnd).trim();

//        log.info("server: {}", result[0]);
//        log.info("toke: {}", result[1]);
//        log.info("data: {}", result[2]);

        return result;
    }


}
