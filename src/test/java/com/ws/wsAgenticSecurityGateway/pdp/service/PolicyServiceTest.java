package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.repository.GatewayPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the Stage-2 default lineage-policy seeder (LD-3): idempotent per-tenant upsert of the baseline
 * {@code deny-unverified-root} / {@code deny-unverified-actor} guardrails.
 */
class PolicyServiceTest {

    private final GatewayPolicyRepository repository = mock(GatewayPolicyRepository.class);
    private final CedarPolicyEngine cedarEngine = mock(CedarPolicyEngine.class);
    private final GatewayAuditService auditService = mock(GatewayAuditService.class);

    private final PolicyService service = new PolicyService(repository, cedarEngine, auditService, true);

    private static final String TENANT = "amitdev.local";

    @BeforeEach
    void stubPrincipalExtraction() {
        // The seeder now derives the principal read-model from each policy's Cedar text before saving. The
        // baseline guardrails are principal-agnostic, so the real engine returns ANY — stub the mock to match.
        when(cedarEngine.extractPrincipal(any()))
                .thenReturn(new CedarPolicyEngine.PolicyPrincipal("ANY", null));
    }

    @Test
    void seedsBothGuardrails_whenAbsent() {
        when(repository.findByPolicyNameAndWsTenantName(any(), eq(TENANT))).thenReturn(Optional.empty());

        int seeded = service.seedDefaultLineagePolicies(TENANT);

        assertThat(seeded).isEqualTo(2);

        ArgumentCaptor<GatewayPolicyEntity> saved = ArgumentCaptor.forClass(GatewayPolicyEntity.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .allSatisfy(p -> {
                    assertThat(p.getEffect()).isEqualTo("FORBID");
                    assertThat(p.getSource()).isEqualTo("DEFAULT");
                    assertThat(p.getEnabled()).isTrue();
                    assertThat(p.getWsTenantName()).isEqualTo(TENANT);
                })
                .extracting(GatewayPolicyEntity::getPolicyName)
                .containsExactlyInAnyOrder("deny-unverified-root", "deny-unverified-actor");
    }

    @Test
    void isIdempotent_skipsWhenAlreadyPresent() {
        when(repository.findByPolicyNameAndWsTenantName(any(), eq(TENANT)))
                .thenReturn(Optional.of(new GatewayPolicyEntity()));

        int seeded = service.seedDefaultLineagePolicies(TENANT);

        assertThat(seeded).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    void skipsSecondGuardrail_whenOnlyOnePresent() {
        when(repository.findByPolicyNameAndWsTenantName(eq("deny-unverified-root"), eq(TENANT)))
                .thenReturn(Optional.of(new GatewayPolicyEntity()));
        when(repository.findByPolicyNameAndWsTenantName(eq("deny-unverified-actor"), eq(TENANT)))
                .thenReturn(Optional.empty());

        int seeded = service.seedDefaultLineagePolicies(TENANT);

        assertThat(seeded).isEqualTo(1);
        verify(repository, times(1)).save(any());
    }

    @Test
    void ignoresBlankOrNullTenant() {
        assertThat(service.seedDefaultLineagePolicies("  ")).isZero();
        assertThat(service.seedDefaultLineagePolicies(null)).isZero();
        verify(repository, never()).save(any());
    }
}
