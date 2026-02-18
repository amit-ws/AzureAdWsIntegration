package com.ws.logMcpServer.controller;

import com.ws.logMcpServer.service.AzureResourcesLogsMetricsService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/azure-logs/")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourcesLogsMetricsContoller {

    final AzureResourcesLogsMetricsService logsMetricsService;

    @Autowired
    public AzureResourcesLogsMetricsContoller(AzureResourcesLogsMetricsService logsMetricsService) {
        this.logsMetricsService = logsMetricsService;
    }

    @GetMapping("v1/GET")
    public void fetchLogs() {
        logsMetricsService.getLogsForAllVMs();
    }
}
