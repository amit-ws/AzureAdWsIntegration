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

//    private TokenManager() {
//        clientSecretCredential = new ClientSecretCredentialBuilder()
//                .clientId("927b5559-d13f-4a17-b63f-20695d2f3490")
//                .clientSecret("T4T8Q~~5lTlxM7KZPh2o~.d6hHC8Pq_dShFS3dqR")
//                .tenantId("3c10c941-37e4-4b03-8d97-d3524abe6040")
//                .build();
//    }

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
