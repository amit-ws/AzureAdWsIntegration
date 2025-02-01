//package com.ws.service;
//
//import com.microsoft.graph.models.PasswordProfile;
//import com.microsoft.graph.models.User;
//import com.microsoft.graph.requests.GraphServiceClient;
//import com.microsoft.graph.requests.UserCollectionPage;
//import com.ws.azureAdIntegration.entity.AzureUser;
//import com.ws.azureAdIntegration.util.AzureAuthUtil;
//import com.ws.azureAdIntegration.util.AzureEntityUtil;
//import com.ws.azureAdIntegration.util.GenericUtil;
//import lombok.extern.slf4j.Slf4j;
//import okhttp3.Request;
//import org.apache.commons.lang3.ObjectUtils;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.util.List;
//import java.util.Objects;
//import java.util.stream.Collectors;
//
//@Service
//@Slf4j
//public class AzureAdTestService {
//    private static final String clientId = "9acacaf6-02e1-4e06-84d9-5da4a7ffd2aa";
//    private static final String clientSecret = "sJB8Q~G-YDCgTRPv6J~LZCQkNyDyUATwQvP_Bcx0";
//    private static final String tenantId = "00b1d06b-e316-45af-a6d2-2734f62a5acd";
//    final AzureAuthUtil azureAuthUtil;
//    GraphServiceClient<Request> graphServiceClient;
//
//    @Autowired
//    public AzureAdTestService(AzureAuthUtil azureAuthUtil) {
//        this.azureAuthUtil = azureAuthUtil;
//    }
//
//    public void initializeGraphClient() {
//        graphServiceClient = azureAuthUtil.validateAzureCredentials(tenantId, clientId, clientSecret);
//    }
//
//    public void fetchUser() {
//        initializeGraphClient();
//        List<User> users = graphServiceClient.users()
//                .buildRequest()
//                .get().getCurrentPage();
//
//        users.forEach((user -> {
//            log.info("User name: {}", user.displayName);
//            PasswordProfile passwordProfile = user.passwordProfile;
//                log.info("Password: {}", passwordProfile.password);
//        }));
//    }
//
//
//}
