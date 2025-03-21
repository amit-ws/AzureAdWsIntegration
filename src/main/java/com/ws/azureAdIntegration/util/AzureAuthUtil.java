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


import com.azure.resourcemanager.AzureResourceManager;
import com.microsoft.graph.requests.GraphServiceClient;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.exception.AzureDataException;
import com.ws.configuration.AzureAuthConfigurationFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

import okhttp3.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AzureAuthUtil {
    private final AzureAuthConfigurationFactory azureAuthConfigurationFactory;
    private static final Map<String, String> AZURE_AUTH_ERROR_MAP;

    static {
        AZURE_AUTH_ERROR_MAP = new HashMap<>() {{
            put("AADSTS700016", "Invalid Client ID: %s");
            put("AADSTS7000215", "Invalid Client Secret: %s");
            put("AADSTS900023", "Invalid Tenant ID: %s");
            put("InvalidSubscriptionId", "Invalid Subscription ID: %s");
//            put("Request_BadRequest", "Invalid Object ID");
        }};
    }


    @Autowired
    public AzureAuthUtil(AzureAuthConfigurationFactory azureAuthConfigurationFactory) {
        this.azureAuthConfigurationFactory = azureAuthConfigurationFactory;
    }

    public GraphServiceClient<Request> validateAzureCredentials(String tenantId, String clientId, String clientSecret) {
        try {
            return azureAuthConfigurationFactory.createAzureGraphServiceClient(clientId, clientSecret, tenantId);
        } catch (Exception e) {
            log.error("Error in creating GraphServiceClient with provided Azure credentials");
            log.error("Message: {}", e.getMessage());
            String message = resolveAzureCredentialError(e.getMessage(), clientId, clientSecret, tenantId, null);
            throw new RuntimeException(message);
        }
    }

    public GraphServiceClient<Request> validateAzureCredentials(AzureUserCredentialDTO azureUserCredentialDTO) {
        try {
            return azureAuthConfigurationFactory.createAzureGraphServiceClient(azureUserCredentialDTO.getClientId(), azureUserCredentialDTO.getClientSecret(), azureUserCredentialDTO.getTenantId());
        } catch (Exception e) {
            log.error("Error in creating GraphServiceClient with provided Azure credentials");
            log.error("Message: {}", e.getMessage());
            String message = resolveAzureCredentialError(e.getMessage(), azureUserCredentialDTO.getClientId(), azureUserCredentialDTO.getClientSecret(), azureUserCredentialDTO.getTenantId(), null);
            throw new RuntimeException(message);
        }
    }


    public AzureResourceManager validateAzureCredentialsWithSubscriptionId(String tenantId, String clientId, String clientSecret, String subscriptionId) {
        try {
            AzureResourceManager azureResourceManager = azureAuthConfigurationFactory.createAzureResourceClient(clientId, clientSecret, tenantId, subscriptionId);
            validateSubscriptionId(azureResourceManager);
            return azureResourceManager;
        } catch (Exception e) {
            log.error("Error in AzureResourceManager with provided Azure credentials");
            log.error("Message: {}", e.getMessage());
//            String error = null;
//            if (e.getMessage().contains("AADSTS7000215")) {
//                error = String.format(AZURE_AUTH_ERROR_MAP.get("AADSTS7000215"), clientSecret);
//            } else if (e.getMessage().contains("AADSTS700016")) {
//                error = AZURE_AUTH_ERROR_MAP.get("AADSTS700016");
//            } else if (e.getMessage().contains("AADSTS900023")) {
//                error = String.format(AZURE_AUTH_ERROR_MAP.get("AADSTS900023"), tenantId);
//            } else if (e.getMessage().contains("InvalidSubscriptionId")) {
//                error = String.format(AZURE_AUTH_ERROR_MAP.get("InvalidSubscriptionId"), subscriptionId);
//            } else {
//
//            }
//            String message = resolveAzureCredentialError(e.getMessage());
            throw new AzureDataException(resolveAzureCredentialError(e.getMessage(), clientId, clientSecret, tenantId, subscriptionId));
        }
    }


    private void validateSubscriptionId(AzureResourceManager azureResourceManager) {
        azureResourceManager.getCurrentSubscription();
    }


    private String resolveAzureCredentialError(String errorMessage, String clientId, String clientSecret, String tenantId, String subscriptionId) {
        return AZURE_AUTH_ERROR_MAP.entrySet().stream()
                .filter(entry -> errorMessage.contains(entry.getKey()))
                .map(entry -> String.format(entry.getValue(), getReplacementValue(entry.getKey(), clientId, clientSecret, tenantId, subscriptionId)))
                .findFirst()
                .orElse("Unknown error: Please check your Azure credentials and try again!");
    }

    private String getReplacementValue(String errorCode, String clientId, String clientSecret, String tenantId, String subscriptionId) {
        switch (errorCode) {
            case "AADSTS7000215":
                return clientSecret;
            case "AADSTS900023":
                return tenantId;
            case "InvalidSubscriptionId":
                return subscriptionId;
            default:
                return clientId;
        }
    }


//    public AzureResourceManager validateAzureCredentialsWithSubscriptionId(AzureUserCredentialDTO azureUserCredentialDTO) {
//        try {
//            return azureAuthConfigurationFactory.createAzureResourceClient(azureUserCredentialDTO.getClientId(), azureUserCredentialDTO.getClientSecret(),
//                    azureUserCredentialDTO.getTenantId(), azureUserCredentialDTO.getSubscriptionId());
//        } catch (Exception e) {
//            log.error("Error in creating azure resource manager with provided Azure credentials");
//            log.error("Message: {}", e.getMessage());
//            String message = resolveAzureCredentialError(e.getMessage(), azureUserCredentialDTO.getClientId(), azureUserCredentialDTO.getClientSecret(),
//                    azureUserCredentialDTO.getTenantId(), azureUserCredentialDTO.getSubscriptionId());
//            throw new RuntimeException(message);
//        }
//    }
}


