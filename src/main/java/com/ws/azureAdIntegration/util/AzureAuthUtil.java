package com.ws.azureAdIntegration.util;
//
//import com.ws.cofiguration.azure.GraphServiceClientFactory;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;
//
//import java.util.HashMap;
//import java.util.Map;
//
//@Component
//public class AzureAuthUtil {
//    final Logger log = LoggerFactory.getLogger(this.getClass());
//    final GraphServiceClientFactory graphServiceClientFactory;
//    static final Map<String, String> AZURE_AUTH_ERROR_MAP;
//
//    static {
//        AZURE_AUTH_ERROR_MAP = new HashMap<>() {{
//            put("AADSTS700016", "Invalid Client ID or Client Secret");
//            put("AADSTS7000215", "Invalid Client Secret");
//            put("AADSTS900023", "Invalid Tenant ID");
//            put("Request_BadRequest", "Invalid Object ID");
//        }};
//    }
//
//    @Autowired
//    public AzureAuthUtil(GraphServiceClientFactory graphServiceClientFactory) {
//        this.graphServiceClientFactory = graphServiceClientFactory;
//    }
//
//    public final String validateAzureCredentialsWithGraphApi(String tenantId, String clientId, String clientSecret, String objectId) {
//        String message = null;
//        try {
//            graphServiceClientFactory.createClient(clientId, clientSecret, tenantId)
//                    .applications(objectId)
//                    .buildRequest()
//                    .get();
//        } catch (Exception e) {
//            log.error("Error in verifying azure credentials");
//            log.error("Error message: {}", e.getMessage());
//            message = resolveAzureCredentialError (e.getMessage());
//        }
//        return message;
//    }
//
//    private final String resolveAzureCredentialError (String errorMessage) {
//        return AZURE_AUTH_ERROR_MAP.entrySet()
//                .stream()
//                .filter(entry -> errorMessage.contains(entry.getKey()))
//                .map(Map.Entry::getValue)
//                .findFirst()
//                .orElse("Unknown error: Please check your Azure credentials and try again.");
//    }
//}



import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.cofiguration.azure.GraphServiceClientFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AzureAuthUtil {
    private final GraphServiceClientFactory graphServiceClientFactory;
    private static final Map<String, String> AZURE_AUTH_ERROR_MAP;

    static {
        AZURE_AUTH_ERROR_MAP = new HashMap<>() {{
            put("AADSTS700016", "Invalid Client ID or Client Secret");
            put("AADSTS7000215", "Invalid Client Secret");
            put("AADSTS900023", "Invalid Tenant ID");
            put("Request_BadRequest", "Invalid Object ID");
        }};
    }

    @Autowired
    public AzureAuthUtil(GraphServiceClientFactory graphServiceClientFactory) {
        this.graphServiceClientFactory = graphServiceClientFactory;
    }

    public GraphServiceClient validateAzureCredentialsWithGraphApi(String tenantId, String clientId, String clientSecret, String objectId) {
        try {
            GraphServiceClient graphClient = graphServiceClientFactory.createClient(clientId, clientSecret, tenantId);
            graphClient.applications(objectId).buildRequest().get();
            return graphClient;
        } catch (Exception e) {
            log.error("Error in verifying Azure credentials: {}", e.getMessage());
            String message = resolveAzureCredentialError(e.getMessage());
            throw new RuntimeException(message);
        }
    }

    private static String resolveAzureCredentialError(String errorMessage) {
        return AZURE_AUTH_ERROR_MAP.entrySet()
                .stream()
                .filter(entry -> errorMessage.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("Unknown error: Please check your Azure credentials and try again.");
    }
}

