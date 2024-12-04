package com.ws.azureResourcesIntegration.configuration;


import com.azure.core.credential.TokenRequestContext;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.microsoft.graph.authentication.IAuthenticationProvider;
import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.cofiguration.azure.TokenManager;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.concurrent.CompletableFuture;

@Component
public class AzureAuthConfigurationFactory {

    /*
        Method to create Azure GraphServiceClient
        To be use for Azure Active Directory apis
     */
    public GraphServiceClient createAzureGraphServiceClient(String clientId, String clientSecret, String tenantId) {
        ClientSecretCredential clientSecretCredential = createAzureClientSecretCredential(clientId, clientSecret, tenantId);
        return GraphServiceClient.builder()
                .authenticationProvider(new IAuthenticationProvider() {
                    @Override
                    public CompletableFuture<String> getAuthorizationTokenAsync(URL requestUrl) {
                        return CompletableFuture.completedFuture(getAccessToken());
                    }

                    private String getAccessToken() {
                        TokenRequestContext requestContext = new TokenRequestContext().addScopes("https://graph.microsoft.com/.default");
                        String accessToken = clientSecretCredential.getToken(requestContext).block().getToken();
                        TokenManager.getInstance().setAccessToken(accessToken);
                        return TokenManager.getInstance().getAccessToken();
                    }
                })
                .buildClient();
    }

    /*
        Method to create Azure AzureResourceManager
        To be use for Azure Resource related apis
    */
    public AzureResourceManager createAzureResourceClient(String clientId, String clientSecret, String tenantId, String subscriptionId) {
        ClientSecretCredential clientSecretCredential = createAzureClientSecretCredential(clientId, clientSecret, tenantId);
        AzureProfile profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);
        return AzureResourceManager
                .authenticate(clientSecretCredential, profile)
                .withSubscription(subscriptionId);
    }


    private ClientSecretCredential createAzureClientSecretCredential(String clientId, String clientSecret, String tenantId) {
        return new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .build();
    }

}
