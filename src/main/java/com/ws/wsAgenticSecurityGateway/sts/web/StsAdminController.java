package com.ws.wsAgenticSecurityGateway.sts.web;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.sts.service.StsKeyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin read API for the STS signing keys behind OBO-token minting — exposes the public metadata
 * (kid / status / createdAt / public JWK) for the dashboard's "STS status" panel. Never exposes the
 * encrypted private key material.
 */
@RestController
@RequestMapping("/api/admin/sts")
@Slf4j
public class StsAdminController {

    private final StsKeyService keyService;

    public StsAdminController(StsKeyService keyService) {
        this.keyService = keyService;
    }

    @GetMapping("/keys")
    public ResponseEntity<List<Map<String, Object>>> keys() {
        String tenant = TenantContext.get();
 log.info("GET /api/admin/sts/keys (tenant={})", tenant);
        return ResponseEntity.ok(keyService.listPublicKeys(tenant));
    }
}
