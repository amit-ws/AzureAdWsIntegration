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
                .clientId("9acacaf6-02e1-4e06-84d9-5da4a7ffd2aa")
                .clientSecret("sJB8Q~G-YDCgTRPv6J~LZCQkNyDyUATwQvP_Bcx0")
                .tenantId("00b1d06b-e316-45af-a6d2-2734f62a5acd")
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
        TokenRequestContext requestContext = new TokenRequestContext()
                .addScopes("https://graph.microsoft.com/.default");
        return accessToken = clientSecretCredential.getToken(requestContext).block().getToken();
    }

    public String getDelegatedAccessToken() {
        return com.ws.service.TokenManager.getInstance().getAccessToken();
    }
}
