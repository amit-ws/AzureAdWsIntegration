package com.ws.wsAgenticSecurityGateway.sts.service;

import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsRotationPolicyEntity;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsRotationPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the STS auto-rotation policy + the scheduled sweep: a tenant gets a safe default policy,
 * saving upserts (clamping the interval to ≥1), and the sweep rotates only when the ACTIVE key has aged
 * past the tenant's interval.
 */
class StsRotationServiceTest {

    private final GatewayStsRotationPolicyRepository policyRepo = mock(GatewayStsRotationPolicyRepository.class);
    private final StsKeyService keyService = mock(StsKeyService.class);

    private StsRotationService service;

    @BeforeEach
    void setUp() {
        service = new StsRotationService(policyRepo, keyService);
    }

    @Test
    void getPolicy_returnsSafeDefaultWhenNoneSet() {
        when(policyRepo.findByWsTenantName("acme")).thenReturn(Optional.empty());

        GatewayStsRotationPolicyEntity p = service.getPolicy("acme");

        assertThat(p.isAutoRotate()).isFalse();
        assertThat(p.getIntervalDays()).isEqualTo(90);
    }

    @Test
    void setPolicy_upserts_andClampsIntervalToAtLeastOne() {
        when(policyRepo.findByWsTenantName("acme")).thenReturn(Optional.empty());
        when(policyRepo.save(any())).thenAnswer(returnsFirstArg());

        GatewayStsRotationPolicyEntity p = service.setPolicy("acme", true, 0); // 0 → clamped to 1

        assertThat(p.isAutoRotate()).isTrue();
        assertThat(p.getIntervalDays()).isEqualTo(1);
    }

    @Test
    void autoRotateDue_rotatesWhenActiveKeyOlderThanInterval() {
        GatewayStsRotationPolicyEntity policy = GatewayStsRotationPolicyEntity.builder()
                .wsTenantName("acme").autoRotate(true).intervalDays(30).build();
        when(policyRepo.findByAutoRotateTrue()).thenReturn(List.of(policy));
        when(keyService.activeKeyCreatedAt("acme")).thenReturn(Optional.of(LocalDateTime.now().minusDays(31)));

        service.autoRotateDue();

        verify(keyService).rotate("acme");
    }

    @Test
    void autoRotateDue_skipsWhenActiveKeyWithinInterval() {
        GatewayStsRotationPolicyEntity policy = GatewayStsRotationPolicyEntity.builder()
                .wsTenantName("acme").autoRotate(true).intervalDays(30).build();
        when(policyRepo.findByAutoRotateTrue()).thenReturn(List.of(policy));
        when(keyService.activeKeyCreatedAt("acme")).thenReturn(Optional.of(LocalDateTime.now().minusDays(5)));

        service.autoRotateDue();

        verify(keyService, never()).rotate(anyString());
    }
}
