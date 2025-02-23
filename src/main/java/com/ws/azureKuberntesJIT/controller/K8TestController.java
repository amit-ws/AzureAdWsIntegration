package com.ws.azureKuberntesJIT.controller;

import com.ws.azureKuberntesJIT.service.K8TestService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/k8Test")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8TestController {

    final K8TestService k8TestService;

    @Autowired
    public K8TestController(K8TestService k8TestService) {
        this.k8TestService = k8TestService;
    }

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
}
