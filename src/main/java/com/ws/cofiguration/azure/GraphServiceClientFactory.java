package com.ws.cofiguration.azure;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.authentication.IAuthenticationProvider;
import com.microsoft.graph.requests.GraphServiceClient;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.concurrent.CompletableFuture;

@Component
public class GraphServiceClientFactory {
    public GraphServiceClient createClient(String clientId, String clientSecret, String tenantId) {
        ClientSecretCredential clientSecretCredential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .build();

        return GraphServiceClient
                .builder()
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
}
