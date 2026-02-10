package com.ws.azureKuberntesJIT.controller;

import com.ws.azureKuberntesJIT.dto.K8sAuditLog;
import com.ws.azureKuberntesJIT.service.K8TestService;
import com.ws.azureKuberntesJIT.service.LogsAndMetricsService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@Slf4j
@RestController
@RequestMapping("/api/k8Test")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8TestController {

    final K8TestService k8TestService;
    final LogsAndMetricsService logsAndMetricsService;

    @Autowired
    public K8TestController(K8TestService k8TestService, LogsAndMetricsService logsAndMetricsService) {
        this.k8TestService = k8TestService;
        this.logsAndMetricsService = logsAndMetricsService;
    }
//    final CertificateService certificateService;

//    @Autowired
//    public K8TestController(K8TestService k8TestService, LogsAndMetricsService logsAndMetricsService, CertificateService certificateService) {
//        this.k8TestService = k8TestService;
//        this.logsAndMetricsService = logsAndMetricsService;
//        this.certificateService = certificateService;
//    }

    @GetMapping("/getRG")
    public ResponseEntity getRGListHandler() {
        return ResponseEntity.ok(k8TestService.getRGList());
    }

    @GetMapping("/k8Clusters")
    public ResponseEntity listK8Clusters() {
        k8TestService.listK8Clusters();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/listNamespaces")
    public ResponseEntity listResourcesHandler() {
        k8TestService.listResources();
        return ResponseEntity.ok().build();
    }


    @GetMapping("/savingData")
    public ResponseEntity savingData() {
        k8TestService.savingData();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/createK8Resources")
    public ResponseEntity createK8ResourcesWithSampleData() {
        k8TestService.createK8ResourcesWithSampleData();
        return ResponseEntity.ok().build();
    }


    @GetMapping("/getPodLogs")
    public ResponseEntity fetchK8LogsFromPods() {
        k8TestService.fetchK8LogsFromPods();
        return ResponseEntity.ok().build();
    }


    @GetMapping("/getLogs")
    public ResponseEntity<List<K8sAuditLog>> fetchK8LogsHandler() {
        return ResponseEntity.ok(logsAndMetricsService.fetchK8Logs());
    }

    @GetMapping("/testLogs")
    public ResponseEntity<?> testLogs() {
        return ResponseEntity.ok(logsAndMetricsService.testLogs());

    }


    @GetMapping("/logs/customSA")
    public ResponseEntity<List<?>> fetchK8LogsForCustomSAsHandler() {
        return ResponseEntity.ok(logsAndMetricsService.fetchK8LogsForCustomSAs());
    }

    @GetMapping("/logs/new/customSA")
    public ResponseEntity<List<?>> fetchK8LogsForCustomSAs_NEW() {
        return ResponseEntity.ok(logsAndMetricsService.fetchK8LogsForCustomSAs_NEW());
    }


//    @GetMapping("/getCustomSA")
//    public ResponseEntity<List<String>> fetchCustomServiceAccounts() {
//        return ResponseEntity.ok(logsAndMetricsService.fetchCustomServiceAccounts());
//    }


    @GetMapping("/create/CRB")
    public ResponseEntity createClusterRoleBindingForUser() {
        k8TestService.createClusterRoleBindingForUser();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/create/RB")
    public ResponseEntity createRoleAndBindingForSA(@RequestParam String ns, @RequestParam String sa) {
        k8TestService.createRoleAndBindingForSA(ns, sa);
        return ResponseEntity.ok().build();
    }

//
//    @PostMapping("/jit/certificate")
//    public ResponseEntity<?> requestAccess(@RequestBody JitRequest req) {
//        try {
//            if (req.username == null || req.username.isBlank())
//                return ResponseEntity.badRequest().body("username required");
//            if (req.namespace == null || req.namespace.isBlank())
//                return ResponseEntity.badRequest().body("namespace required");
//            if (req.verbs == null || req.verbs.isEmpty()) return ResponseEntity.badRequest().body("verbs required");
//            String kubeconfig = certificateService.createTemporaryKubeconfig(
//                    req.username,
//                    req.namespace,
//                    req.resourceType,
//                    req.verbs,
//                    req.resourceNames,
//                    req.ttlSeconds
//            );
//
//            return ResponseEntity.ok().body(kubeconfig);
//        } catch (Exception ex) {
//            log.error("Error:{}", ex.getMessage());
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getMessage());
//        }
//    }
//
//
//    public static class JitRequest {
//        public String username;
//        public String namespace;
//        public String resourceType;
//        public List<String> verbs;
//        public List<String> resourceNames;
//        public int ttlSeconds;
//    }
}
