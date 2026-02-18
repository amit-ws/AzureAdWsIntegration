package com.ws.configuration;


import com.azure.core.credential.TokenRequestContext;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.microsoft.graph.requests.GraphServiceClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
public class AzureAuthConfigurationFactory {

    /*
        Method to create Azure GraphServiceClient
        To be use for Azure Active Directory apis
     */
    public GraphServiceClient<Request> createAzureGraphServiceClient(String clientId, String clientSecret, String tenantId) {
        ClientSecretCredential clientSecretCredential = createAzureClientSecretCredential(clientId, clientSecret, tenantId);
        String token;
        try {
            TokenRequestContext requestContext = new TokenRequestContext().addScopes("https://graph.microsoft.com/.default");
            token = Objects.requireNonNull(clientSecretCredential.getToken(requestContext).block()).getToken();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return GraphServiceClient.builder()
                .authenticationProvider(requestUrl -> CompletableFuture.completedFuture(token))
                .buildClient();
    }

    /*
        Method to create Azure AzureResourceManager
        To be use for Azure Resource related apis
        Create with specific  SubscriptionId
        Note: Subscription is required anyhow
    */
    public AzureResourceManager createAzureResourceClient(String clientId, String clientSecret, String tenantId, String subscriptionId) {
        log.info("clientSecret: " + clientSecret);
        log.info("clientId: " + clientId);
        log.info("tenantId: " + tenantId);
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
