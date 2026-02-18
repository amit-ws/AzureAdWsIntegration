package com.ws.mcpAccessMgmt;

import com.azure.core.annotation.Get;
import com.ws.mcpAccessMgmt.model.EnterprisePolicyRequest;
import com.ws.mcpAccessMgmt.service.EnterprisePolicyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class EnterprisePolicyController {


    private final EnterprisePolicyService policyService;

    @Autowired
    public EnterprisePolicyController(EnterprisePolicyService policyService) {
        this.policyService = policyService;
    }


    @PostMapping("/regoes/create")
    public ResponseEntity<String> convertToRego(@RequestBody EnterprisePolicyRequest policyDefinition) {
        return ResponseEntity.ok(policyService.convertToRego(policyDefinition));
    }

}
