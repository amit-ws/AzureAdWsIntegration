package com.ws.azureKuberntesJIT.controller;


import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.dto.PageResponse;
import com.ws.azureKuberntesJIT.enttity.K8sLogEntry;
import com.ws.azureKuberntesJIT.service.K8LogsAndMetricsService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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


    @GetMapping("v1/get-k8s-logs")
    public ResponseEntity<PageResponse<K8sLogEntry>> fingK8sLogsForWsTenantUsingCloudTypeHandler(@RequestParam String wsTenantName,
                                                                                                 @RequestParam("cloudType") CloudProviderType cloudType,
                                                                                                 @RequestParam(defaultValue = "0") int page,
                                                                                                 @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity
                .ok(k8LogsAndMetricsService.findK8sLogsForWsTenantUsingCloudType(wsTenantName, cloudType, page, size));
    }

}
