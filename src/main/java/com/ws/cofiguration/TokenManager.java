package com.ws.cofiguration;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;

import java.util.concurrent.ExecutionException;

public class TokenManager {
    private static TokenManager instance;
    private final ClientSecretCredential clientSecretCredential;
    private String accessToken;

    private TokenManager() {
        clientSecretCredential = new ClientSecretCredentialBuilder()
                .clientId("")
                .clientSecret("")
                .tenantId("")
                .build();
    }


    public static synchronized TokenManager getInstance() {
        if (instance == null) {
            instance = new TokenManager();
        }
        return instance;
    }

    public String getApplicationAccessToken() throws ExecutionException, InterruptedException {
        if (accessToken == null) {
            TokenRequestContext requestContext = new TokenRequestContext()
                    .addScopes("https://graph.microsoft.com/.default");
            accessToken = clientSecretCredential.getToken(requestContext).block().getToken();
        }
        return accessToken;
    }

    public String getDelegatedAccessToken() {
        return com.ws.service.TokenManager.getInstance().getAccessToken();
    }
}
