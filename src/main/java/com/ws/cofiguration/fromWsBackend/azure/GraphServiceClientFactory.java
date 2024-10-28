//package com.ws.cofiguration.fromWsBackend.azure;
//
//import com.microsoft.graph.authentication.IAuthenticationProvider;
//import com.microsoft.graph.requests.GraphServiceClient;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;
//
//import java.net.URL;
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.ExecutionException;
//
//@Component
//public class GraphServiceClientFactory {
//
//    private final TokenManagerFactory tokenManagerFactory;
//
//    @Autowired
//    public GraphServiceClientFactory(TokenManagerFactory tokenManagerFactory) {
//        this.tokenManagerFactory = tokenManagerFactory;
//    }
//
//    public GraphServiceClient createClient() {
//        return GraphServiceClient
//                .builder()
//                .authenticationProvider(new IAuthenticationProvider() {
//                    @Override
//                    public CompletableFuture<String> getAuthorizationTokenAsync(URL requestUrl) {
//                        return CompletableFuture.completedFuture(getAccessToken());
//                    }
//                    private String getAccessToken() {
//                        try {
//                            tokenManagerFactory.generateAccessToken();
//                        } catch (ExecutionException | InterruptedException e) {
//                            throw new RuntimeException(e);
//                        }
//                        return TokenManager.getInstance().getAccessToken();
//                    }
//                })
//                .buildClient();
//    }
//}
//
