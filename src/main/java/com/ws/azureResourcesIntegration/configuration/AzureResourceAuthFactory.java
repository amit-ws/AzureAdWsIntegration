package com.ws.azureResourcesIntegration.configuration;


import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import org.springframework.stereotype.Component;

@Component
public class AzureResourceAuthFactory {
    public AzureResourceManager createResourceClient(String clientId, String clientSecret, String tenantId, String subscriptionId) {
        ClientSecretCredential clientSecretCredential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .build();

        AzureProfile profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);

        return AzureResourceManager
                .authenticate(clientSecretCredential, profile)
                .withSubscription(subscriptionId);
    }
}
