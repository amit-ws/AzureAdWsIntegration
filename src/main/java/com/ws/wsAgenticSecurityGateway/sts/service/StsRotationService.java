package com.ws.wsAgenticSecurityGateway.sts.service;

import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsRotationPolicyEntity;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsRotationPolicyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The per-tenant STS key auto-rotation policy (whether + how often) and the scheduled sweep that enforces it.
 * A tenant opts in via {@link #setPolicy}; the daily {@link #autoRotateDue} sweep rotates that tenant's key
 * (via {@link StsKeyService#rotate}) once its ACTIVE key is older than the configured interval.
 */
@Service
@Slf4j
public class StsRotationService {

    private final GatewayStsRotationPolicyRepository policyRepo;
    private final StsKeyService keyService;

    public StsRotationService(GatewayStsRotationPolicyRepository policyRepo, StsKeyService keyService) {
        this.policyRepo = policyRepo;
        this.keyService = keyService;
    }

    /** The tenant's policy, or a default (auto-rotate off, 90 days) if none is set. */
    public GatewayStsRotationPolicyEntity getPolicy(String tenant) {
        return policyRepo.findByWsTenantName(tenant)
                .orElseGet(() -> GatewayStsRotationPolicyEntity.builder()
                        .wsTenantName(tenant).autoRotate(false).intervalDays(90).build());
    }

    @Transactional
    public GatewayStsRotationPolicyEntity setPolicy(String tenant, boolean autoRotate, int intervalDays) {
        GatewayStsRotationPolicyEntity policy = policyRepo.findByWsTenantName(tenant)
                .orElseGet(() -> GatewayStsRotationPolicyEntity.builder().wsTenantName(tenant).build());
        policy.setAutoRotate(autoRotate);
        policy.setIntervalDays(Math.max(1, intervalDays));
        GatewayStsRotationPolicyEntity saved = policyRepo.save(policy);
        log.info("STS rotation policy set for tenant '{}': autoRotate={}, intervalDays={}",
                tenant, saved.isAutoRotate(), saved.getIntervalDays());
        return saved;
    }

    /** Daily sweep: rotate each auto-rotate tenant whose ACTIVE key has aged past its interval. */
    @Scheduled(fixedDelayString = "${ws.sts.auto-rotate.check-interval-ms:86400000}")
    public void autoRotateDue() {
        LocalDateTime now = LocalDateTime.now();
        for (GatewayStsRotationPolicyEntity policy : policyRepo.findByAutoRotateTrue()) {
            try {
                keyService.activeKeyCreatedAt(policy.getWsTenantName()).ifPresent(created -> {
                    if (created.plusDays(policy.getIntervalDays()).isBefore(now)) {
                        keyService.rotate(policy.getWsTenantName(), "auto");
                        log.info("Auto-rotated STS key for tenant '{}' ({}-day interval elapsed)",
                                policy.getWsTenantName(), policy.getIntervalDays());
                    }
                });
            } catch (Exception e) {
                log.warn("Auto-rotate check failed for tenant '{}': {}",
                        policy.getWsTenantName(), e.getMessage());
            }
        }
    }
}
