package com.ws.cofiguration.azure;


import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


@Component
public class TokenManagerFactory {

    @Value("${spring.cloud.azure.active-directory.tenant-id}")
    private String tenantId;

    @Value("${spring.cloud.azure.active-directory.client-id}")
    private String clientId;

    @Value("${spring.cloud.azure.active-directory.client-secret}")
    private String clientSecret;

    private ClientSecretCredential clientSecretCredential;

    @PostConstruct
    public void TokenManagerFactory() {
        clientSecretCredential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .build();
    }

    public void generateAccessToken() {
        TokenRequestContext requestContext = new TokenRequestContext()
                .addScopes("https://graph.microsoft.com/.default");
        String accessToken = this.clientSecretCredential.getToken(requestContext).block().getToken();
        TokenManager.getInstance().setAccessToken(accessToken);
    }
}

