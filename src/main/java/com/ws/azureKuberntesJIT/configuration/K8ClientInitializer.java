//package com.ws.azureKuberntesJIT.configuration;
//
//
//import io.kubernetes.client.openapi.ApiClient;
//import io.kubernetes.client.openapi.Configuration;
//import io.kubernetes.client.openapi.apis.*;
//import io.kubernetes.client.util.Config;
//import lombok.AccessLevel;
//import lombok.experimental.FieldDefaults;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Component;
//
//@FieldDefaults(level = AccessLevel.PRIVATE)
//@Slf4j
//@Component
//public class K8ClientInitializer {
//    CoreV1Api coreV1Api;
//    AppsV1Api appsV1Api;
//    BatchV1Api batchApi;
//    StorageV1Api storageV1Api;
//    NetworkingV1Api networkingApi;
//    RbacAuthorizationV1Api rbacApi;
//    ApiextensionsV1Api apiextensionsV1Api;
//
//
//    private void initializeK8Client(String clusterURL, String token) {
//        try {
//            ApiClient client = Config.fromToken(clusterURL, token);
//            client.setVerifyingSsl(false);
//            Configuration.setDefaultApiClient(client);
//        } catch (Exception ex) {
//            log.error("Error in initializing k8 client");
//            log.error("Error: {}", ex.getMessage());
//            throw new RuntimeException(ex.getMessage());
//        }
//    }
//
//    private void initializeK8sApis() {
//        this.coreV1Api = new CoreV1Api();
//        this.appsV1Api = new AppsV1Api();
//        this.batchApi = new BatchV1Api();
//        this.storageV1Api = new StorageV1Api();
//        this.networkingApi = new NetworkingV1Api();
//        this.rbacApi = new RbacAuthorizationV1Api();
//        this.apiextensionsV1Api = new ApiextensionsV1Api();
//    }
//
//    private void initializeK8CoreApi() {
//        this.coreV1Api = new CoreV1Api();
//    }
//
//    private void initializeK8RbackApi() {
//        this.rbacApi = new RbacAuthorizationV1Api();
//    }
//
//
//}
