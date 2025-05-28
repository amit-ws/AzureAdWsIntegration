package com.ws.azureKuberntesJIT.controller;


import com.ws.azureKuberntesJIT.service.K8LogsAndMetricsService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/k8s-observability/")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8LogsAndMetricsController {
    final K8LogsAndMetricsService k8LogsAndMetricsService;

    @Autowired
    public K8LogsAndMetricsController(K8LogsAndMetricsService k8LogsAndMetricsService) {
        this.k8LogsAndMetricsService = k8LogsAndMetricsService;
    }
}
