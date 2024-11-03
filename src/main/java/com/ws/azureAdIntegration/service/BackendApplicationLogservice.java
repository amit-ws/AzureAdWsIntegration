package com.ws.azureAdIntegration.service;

import com.ws.azureAdIntegration.entity.BackendApplicationLog;
import com.ws.azureAdIntegration.repository.BackendApplicationLogRepository;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BackendApplicationLogservice {
    final BackendApplicationLogRepository backendApplicationLogsRepository;

    @Autowired
    public BackendApplicationLogservice(BackendApplicationLogRepository backendApplicationLogsRepository) {
        this.backendApplicationLogsRepository = backendApplicationLogsRepository;
    }


    public void saveAuditLog(String tenantName, Integer wsTenantId, String action, String message, String logLevel) {
        backendApplicationLogsRepository.save(
                BackendApplicationLog.builder()
                        .action(action)
                        .message(message)
                        .wsTenantId(wsTenantId)
                        .loggedInUserEmail(tenantName)
                        .time(LocalDateTime.now())
                        .logLevel(logLevel)
                        .build());
    }
}