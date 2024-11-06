package com.ws.azureResourcesIntegration.service;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;

public class AzureAuthentication {
    private static final String CLIENT_ID = "your-client-id";
    private static final String TENANT_ID = "your-tenant-id";
    private static final String CLIENT_SECRET = "your-client-secret";

    public static ClientSecretCredential authenticate() {
        return new ClientSecretCredentialBuilder()
                .clientId(CLIENT_ID)
                .tenantId(TENANT_ID)
                .clientSecret(CLIENT_SECRET)
                .build();
    }
}
