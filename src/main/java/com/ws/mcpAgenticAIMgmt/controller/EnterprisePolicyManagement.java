//package com.ws.mcpAgenticAIMgmt.controller;
//
//import com.ws.mcpAgenticAIMgmt.model.EnterprisePolicy;
//import com.ws.mcpAgenticAIMgmt.service.RegoGenerationService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//@RestController
//@RequestMapping("/api/enterprises/policyMgmt")
//public class EnterprisePolicyManagement {
//
//    private final RegoGenerationService regoGenerationService;
//
//    @Autowired
//    public EnterprisePolicyManagement(RegoGenerationService regoGenerationService) {
//        this.regoGenerationService = regoGenerationService;
//    }
//
//
//    @PostMapping("/v1/createRego")
//    public ResponseEntity<String> convertToRego(@RequestBody EnterprisePolicy enterprisePolicyDefinition) {
//        return ResponseEntity.ok(regoGenerationService.convertPolicyToRego(enterprisePolicyDefinition));
//    }
//}
