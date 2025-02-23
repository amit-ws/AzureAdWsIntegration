//package com.ws.azureResourcesIntegration.service;
//
//import com.azure.core.credential.AccessToken;
//import com.azure.core.credential.TokenCredential;
//import com.azure.identity.ClientSecretCredential;
//import com.azure.identity.ClientSecretCredentialBuilder;
//import com.azure.identity.ManagedIdentityCredentialBuilder;
//import com.azure.resourcemanager.containerservice.models.KubernetesCluster;
//
//import com.azure.core.credential.TokenRequestContext;
//import org.springframework.stereotype.Component;
//import org.springframework.util.ObjectUtils;
//
//import com.azure.identity.ClientSecretCredential;
//import com.azure.identity.ClientSecretCredentialBuilder;
//import com.azure.core.credential.AccessToken;
//import com.azure.core.credential.TokenRequestContext;
//
//@Component
//public class AzureAdAuthenticator {
//
//    private String tenantId;
//    private String clientId;
//    private String clientSecret;
//
//    // Constructor: Accepts Azure AD credentials (clientId, clientSecret, tenantId)
//    public AzureAdAuthenticator(String tenantId, String clientId, String clientSecret) {
//        this.tenantId = tenantId;
//        this.clientId = clientId;
//        this.clientSecret = clientSecret;
//    }
//
//    // Method to get the Azure AD token for the AKS cluster
//    public String getAzureAdTokenForCluster(KubernetesCluster kubernetesCluster) {
//        // Azure SDK's ClientSecretCredential to authenticate using the Service Principal
//        ClientSecretCredential clientSecretCredential = new ClientSecretCredentialBuilder()
//                .tenantId(tenantId)
//                .clientId(clientId)
//                .clientSecret(clientSecret)
//                .build();
//
//        // Use the scope for Azure resource management API
//        String scope = "https://management.azure.com/.default";  // Correct scope for Azure Resource Management
//
//        // Create a TokenRequestContext with the Azure Resource Management API scope
//        TokenRequestContext requestContext = new TokenRequestContext().addScopes(scope);
//
//        try {
//            // Request the token for interacting with the Azure management API
//            AccessToken token = clientSecretCredential.getToken(requestContext).block();
//
//            // Return the token string
//            return token.getToken();
//        } catch (Exception e) {
//            // Handle error: Failed to get token
//            throw new RuntimeException("Failed to get Azure AD token for AKS cluster "
//                    + kubernetesCluster.name(), e);
//        }
//    }
//
//
//    public static String getTokenWithManagedIdentity(String tenantId, String clientId, String clusterName, String resourceGroup) {
//        // Build a credential object for Managed Identity
//        TokenCredential credential = new ManagedIdentityCredentialBuilder()
//                .clientId(clientId)
//                .build();
//
//        // Get the token for the Kubernetes cluster (you can use a valid Azure resource URL scope)
//        TokenRequestContext context = new TokenRequestContext().addScopes("https://management.azure.com/.default");
//
//        try {
//            // Get the Azure AD token
//            AccessToken token = credential.getToken(context).block();
//
//            // Return the token as a string
//            return token.getToken();
//        } catch (Exception e) {
//            throw new RuntimeException("Error fetching token for AKS cluster", e);
//        }
//    }
//}
//
//
