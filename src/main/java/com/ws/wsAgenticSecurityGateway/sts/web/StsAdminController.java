package com.ws.wsAgenticSecurityGateway.sts.web;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsRotationPolicyEntity;
import com.ws.wsAgenticSecurityGateway.sts.service.StsKeyService;
import com.ws.wsAgenticSecurityGateway.sts.service.StsRevocationService;
import com.ws.wsAgenticSecurityGateway.sts.service.StsRotationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final StsRevocationService revocationService;

    public StsAdminController(StsKeyService keyService,
                             StsRotationService rotationService,
                             StsRevocationService revocationService) {
        this.keyService = keyService;
        this.rotationService = rotationService;
        this.revocationService = revocationService;
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

    /**
     * Revoke a minted OBO token by {@code jti} (e.g. from its audit OBO receipt). Body:
     * {@code {jti, reason?, expiresAt?}} — {@code expiresAt} (the token's natural expiry from the receipt)
     * lets the revocation self-purge; when omitted a short default window is used.
     */
    @PostMapping("/revocations")
    public ResponseEntity<Map<String, Object>> revoke(@RequestBody Map<String, Object> req) {
        String tenant = TenantContext.get();
        Object rawJti = req.get("jti");
        String jti = rawJti != null ? String.valueOf(rawJti).trim() : "";
        if (jti.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "jti is required"));
        }
        String reason = req.get("reason") != null ? String.valueOf(req.get("reason")) : null;
        LocalDateTime expiresAt = parseExpiry(req.get("expiresAt"));
 log.info("POST /api/admin/sts/revocations (tenant={}, jti={})", tenant, jti);
        var saved = revocationService.revoke(tenant, jti, expiresAt, reason);
        return ResponseEntity.ok(Map.of("revoked", true, "jti", jti,
                "expiresAt", String.valueOf(saved.getExpiresAt())));
    }

    /** Still-active OBO token revocations for the tenant. */
    @GetMapping("/revocations")
    public ResponseEntity<List<Map<String, Object>>> revocations() {
        String tenant = TenantContext.get();
        List<Map<String, Object>> list = revocationService.listActive(tenant).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("jti", r.getJti());
                    m.put("reason", r.getReason());
                    m.put("expiresAt", r.getExpiresAt());
                    m.put("revokedAt", r.getRevokedAt());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(list);
    }

    /**
     * Revoke an entire agent SESSION (e.g. from the in-flight page) — kills the live chain: the gateway stops
     * minting OBO tokens for it and rejects its inbound requests. Body: {@code {sessionId, reason?, expiresAt?}}.
     */
    @PostMapping("/revocations/session")
    public ResponseEntity<Map<String, Object>> revokeSession(@RequestBody Map<String, Object> req) {
        String tenant = TenantContext.get();
        Object rawSession = req.get("sessionId");
        String sessionId = rawSession != null ? String.valueOf(rawSession).trim() : "";
        if (sessionId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
        }
        String reason = req.get("reason") != null ? String.valueOf(req.get("reason")) : null;
        LocalDateTime expiresAt = parseExpiry(req.get("expiresAt"));
 log.info("POST /api/admin/sts/revocations/session (tenant={}, sessionId={})", tenant, sessionId);
        var saved = revocationService.revokeSession(tenant, sessionId, expiresAt, reason);
        return ResponseEntity.ok(Map.of("revoked", true, "sessionId", sessionId,
                "expiresAt", String.valueOf(saved.getExpiresAt())));
    }

    /** Still-active session revocations for the tenant. */
    @GetMapping("/revocations/session")
    public ResponseEntity<List<Map<String, Object>>> sessionRevocations() {
        String tenant = TenantContext.get();
        List<Map<String, Object>> list = revocationService.listActiveSessions(tenant).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sessionId", r.getSessionId());
                    m.put("reason", r.getReason());
                    m.put("expiresAt", r.getExpiresAt());
                    m.put("revokedAt", r.getRevokedAt());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(list);
    }

    /** Best-effort parse of an ISO instant ({@code ...Z}) or local date-time; null when unparseable/absent. */
    private static LocalDateTime parseExpiry(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        try {
            return Instant.parse(s).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) {
            // not an instant — try a plain local date-time
        }
        try {
            return LocalDateTime.parse(s);
        } catch (Exception ignored) {
            return null;
        }
    }
}
