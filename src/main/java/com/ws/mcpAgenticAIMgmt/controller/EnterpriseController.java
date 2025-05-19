package com.ws.mcpAgenticAIMgmt.controller;

import com.ws.mcpAgenticAIMgmt.model.Enterprise;
import com.ws.mcpAgenticAIMgmt.service.EnterpriseService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/enterprises/")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class EnterpriseController {
    final EnterpriseService enterpriseService;

    @Autowired
    public EnterpriseController(EnterpriseService enterpriseService) {
        this.enterpriseService = enterpriseService;
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

}
