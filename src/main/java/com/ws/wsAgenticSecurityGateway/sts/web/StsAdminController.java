package com.ws.wsAgenticSecurityGateway.sts.web;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsRotationPolicyEntity;
import com.ws.wsAgenticSecurityGateway.sts.service.StsKeyService;
import com.ws.wsAgenticSecurityGateway.sts.service.StsRotationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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
    private final StsRotationService rotationService;

    public StsAdminController(StsKeyService keyService, StsRotationService rotationService) {
        this.keyService = keyService;
        this.rotationService = rotationService;
    }

    @GetMapping("/keys")
    public ResponseEntity<List<Map<String, Object>>> keys() {
        String tenant = TenantContext.get();
 log.info("GET /api/admin/sts/keys (tenant={})", tenant);
        return ResponseEntity.ok(keyService.listPublicKeys(tenant));
    }

    /**
     * Rotate the tenant's STS signing key: a fresh ACTIVE key starts signing new OBO tokens, the previous
     * key is demoted to RETIRING and kept in the JWKS during the grace window so its tokens still verify.
     */
    @PostMapping("/keys/rotate")
    public ResponseEntity<Map<String, Object>> rotate() {
        String tenant = TenantContext.get();
 log.info("POST /api/admin/sts/keys/rotate (tenant={})", tenant);
        String newKid = keyService.rotate(tenant);
        return ResponseEntity.ok(Map.of("rotated", true, "newKid", newKid));
    }

    /** The tenant's auto-rotation preference, plus the projected next auto-rotation time. */
    @GetMapping("/rotation-policy")
    public ResponseEntity<Map<String, Object>> getRotationPolicy() {
        String tenant = TenantContext.get();
        GatewayStsRotationPolicyEntity policy = rotationService.getPolicy(tenant);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("autoRotate", policy.isAutoRotate());
        body.put("intervalDays", policy.getIntervalDays());
        keyService.activeKeyCreatedAt(tenant)
                .ifPresent(created -> body.put("nextRotationAt", created.plusDays(policy.getIntervalDays())));
        return ResponseEntity.ok(body);
    }

    /** Set the tenant's auto-rotation preference: {@code {autoRotate: boolean, intervalDays: int}}. */
    @PutMapping("/rotation-policy")
    public ResponseEntity<Map<String, Object>> setRotationPolicy(@RequestBody Map<String, Object> req) {
        String tenant = TenantContext.get();
        boolean autoRotate = Boolean.TRUE.equals(req.get("autoRotate"));
        int intervalDays = req.get("intervalDays") instanceof Number n ? n.intValue() : 90;
 log.info("PUT /api/admin/sts/rotation-policy (tenant={}, autoRotate={}, intervalDays={})",
                tenant, autoRotate, intervalDays);
        GatewayStsRotationPolicyEntity saved = rotationService.setPolicy(tenant, autoRotate, intervalDays);
        return ResponseEntity.ok(Map.of("autoRotate", saved.isAutoRotate(), "intervalDays", saved.getIntervalDays()));
    }
}
