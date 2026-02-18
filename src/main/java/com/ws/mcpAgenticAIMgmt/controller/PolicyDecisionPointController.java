package com.ws.mcpAgenticAIMgmt.controller;

import com.ws.mcpAgenticAIMgmt.dto.PepRequest;
import com.ws.mcpAgenticAIMgmt.dto.PdpResponse;
import com.ws.mcpAgenticAIMgmt.service.PolicyDecisionPointService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/pdp/")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PolicyDecisionPointController {
    final PolicyDecisionPointService policyDecisionPointService;

    @Autowired
    public PolicyDecisionPointController(PolicyDecisionPointService policyDecisionPointService) {
        this.policyDecisionPointService = policyDecisionPointService;
    }


    @PostMapping("/v1/authorize")
    public ResponseEntity<PdpResponse> processAuthorizationRequestHandler(@Valid @RequestBody PepRequest pepRequest) {
        return ResponseEntity.ok().body(policyDecisionPointService.processAuthorizationRequest(pepRequest));
    }
}
