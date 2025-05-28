package com.ws.mcpAgenticAIMgmt.controller;

import com.ws.mcpAgenticAIMgmt.service.OpaPolicyUploaderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/policies/")
public class OpaPolicyUploaderController {
    private final OpaPolicyUploaderService uploadPolicy;

    @Autowired
    public OpaPolicyUploaderController(OpaPolicyUploaderService uploadPolicy) {
        this.uploadPolicy = uploadPolicy;
    }

    @PostMapping("/v1/create")
    public ResponseEntity<String> convertToRego() {
        return ResponseEntity.ok().body(uploadPolicy.uploadPolicy());
    }

    @GetMapping("/v1/get")
    public ResponseEntity<String> fetchPolicy(@RequestParam String policyId) {
        return ResponseEntity.ok().body(uploadPolicy.fetchPolicy(policyId));
    }

    @GetMapping("/v1/list")
    public ResponseEntity<List<String>> listPolicyIds() {
        return ResponseEntity.ok().body(uploadPolicy.listPolicyIds());
    }

    @GetMapping("/v1/evaluate")
    public ResponseEntity<Boolean> evaluate(@RequestParam String user, @RequestParam("package") String opaPackageName) {
        return ResponseEntity.ok().body(uploadPolicy.evaluate(user, opaPackageName));
    }


    @GetMapping("/v2/evaluate")
    public ResponseEntity<Map<String, Object>> evaluateV2(@RequestParam("package") String opaPackageName) {
        return ResponseEntity.ok().body(uploadPolicy.evaluateV2(opaPackageName));
    }


}
