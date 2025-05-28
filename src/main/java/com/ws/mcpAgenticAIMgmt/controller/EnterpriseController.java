package com.ws.mcpAgenticAIMgmt.controller;

import com.ws.mcpAgenticAIMgmt.model.Enterprise;
import com.ws.mcpAgenticAIMgmt.model.PdpAuditLogEntry;
import com.ws.mcpAgenticAIMgmt.service.EnterpriseService;
import com.ws.mcpAgenticAIMgmt.service.PdpAuditLogService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/enterprises/")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class EnterpriseController {
    final EnterpriseService enterpriseService;
    final PdpAuditLogService pdpAuditLogService;

    @Autowired
    public EnterpriseController(EnterpriseService enterpriseService, PdpAuditLogService pdpAuditLogService) {
        this.enterpriseService = enterpriseService;
        this.pdpAuditLogService = pdpAuditLogService;
    }


    @PostMapping("v1/onboard")
    public ResponseEntity<Map<String, String>> onboardEnterpriseHandler(@RequestBody Enterprise enterprise) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(enterpriseService.onboardEnterprise(enterprise));
    }


    @GetMapping("v1/get")
    public ResponseEntity<Enterprise> findEnterpriseHandler(@RequestParam String email) {
        return ResponseEntity
                .ok()
                .body(enterpriseService.findEnterprise(email));
    }

    @GetMapping("v1/all/auditLogs")
    public ResponseEntity<List<PdpAuditLogEntry>> fetchAuditLogEntriesHandler(@RequestParam String enterpriseId) {
        return ResponseEntity
                .ok()
                .body(pdpAuditLogService.fetchAuditLogEntries(enterpriseId));
    }


}
