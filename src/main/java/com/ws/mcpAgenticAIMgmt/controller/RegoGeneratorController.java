//package com.ws.mcpAgenticAIMgmt.controller;
//
//import com.ws.mcpAgenticAIMgmt.dto.Policy;
//import com.ws.mcpAgenticAIMgmt.service.RegoGenerator;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//@RestController
//@RequestMapping("/api/enterprises/policyMgmt")
//public class RegoGeneratorController {
//
//    private final RegoGenerator regoGenerator;
//
//    public RegoGeneratorController(RegoGenerator regoGenerator) {
//        this.regoGenerator = regoGenerator;
//    }
//
//
//    @PostMapping("/v1/createRego")
//    public ResponseEntity<String> convertToRego(@RequestBody Policy policy) {
//        return ResponseEntity.ok(regoGenerator.generateRegoPolicy(policy));
//    }
//}
