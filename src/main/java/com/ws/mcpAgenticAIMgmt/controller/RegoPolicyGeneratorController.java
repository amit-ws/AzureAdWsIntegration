package com.ws.mcpAgenticAIMgmt.controller;

import com.ws.mcpAgenticAIMgmt.model.EnterprisePolicy;
import com.ws.mcpAgenticAIMgmt.service.RegoPolicyGenerator;
import com.ws.mcpAgenticAIMgmt.service.RegoPolicyGeneratorV2;
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
    final RegoPolicyGeneratorV2 policyGeneratorV2;

    @Autowired
    public RegoPolicyGeneratorController(RegoPolicyGenerator policyGenerator, RegoPolicyGeneratorV2 policyGeneratorV2) {
        this.policyGenerator = policyGenerator;
        this.policyGeneratorV2 = policyGeneratorV2;
    }


    @PostMapping("v1/create")
    public ResponseEntity<Map<String, UUID>> generateRegoPolicy(@RequestBody EnterprisePolicy policy) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(policyGeneratorV2.createAndSaveRego(policy));
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
