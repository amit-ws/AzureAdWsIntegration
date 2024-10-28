package com.ws.cofiguration;

import com.microsoft.graph.authentication.IAuthenticationProvider;
import com.microsoft.graph.requests.GraphServiceClient;
import org.springframework.util.StringUtils;

import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public abstract class GraphServiceClientFactory {

    public static GraphServiceClient createClient(String authType) {
        return GraphServiceClient
                .builder()
                .authenticationProvider(new IAuthenticationProvider() {
                    @Override
                    public CompletableFuture<String> getAuthorizationTokenAsync(URL requestUrl) {
                        return CompletableFuture.completedFuture(getAccessToken(authType));
                    }

                    private String getAccessToken(String authType) {
                        try {
                            if (StringUtils.isEmpty(authType)) {
                                return TokenManager.getInstance().getApplicationAccessToken();
                            } else {
                                return TokenManager.getInstance().getDelegatedAccessToken();
                            }
                        } catch (ExecutionException | InterruptedException e) {
                            throw new RuntimeException(String.format("Failed to get access token for auth: %s with error: %s", authType, e.getMessage()));
                        }
                    }
                })
                .buildClient();
    }
}
