package com.ws.mcpAgenticAIMgmt.controller;

import com.ws.mcpAgenticAIMgmt.model.EnterprisePolicy;
import com.ws.mcpAgenticAIMgmt.service.RegoPolicyGenerator;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/enterprises/policies")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RegoPolicyGeneratorController {

    final RegoPolicyGenerator policyGenerator;

    @Autowired
    public RegoPolicyGeneratorController(RegoPolicyGenerator policyGenerator) {
        this.policyGenerator = policyGenerator;
    }


    @PostMapping("v1/create")
    public ResponseEntity<Map<String, UUID>> generateRegoPolicy(@RequestBody EnterprisePolicy policy) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(policyGenerator.createAndSaveRego(policy));
    }

    @GetMapping("v1/all")
    public ResponseEntity<List<EnterprisePolicy>> getEnterprisePolicyByEnterpriseIdHandler(@RequestParam String enterpriseId) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(policyGenerator.getEnterprisePolicyByEnterpriseId(enterpriseId));
    }


    @GetMapping("v1/getRego")
    public ResponseEntity<Map<String, String>> getRegoForEnterprisePolicyHandler(@RequestParam String policyId) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(policyGenerator.getRegoForEnterprisePolicy(policyId.trim()));
    }

}
