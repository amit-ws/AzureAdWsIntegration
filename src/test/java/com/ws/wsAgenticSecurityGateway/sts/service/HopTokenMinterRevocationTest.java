package com.ws.wsAgenticSecurityGateway.sts.service;

import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the mint-time revocation gate in {@link HopTokenMinter}: once an agent session is revoked,
 * the gateway must refuse to mint any further OBO token for it (fail-closed → the hop is denied), and it must
 * never reach the STS. Open mode (no tenant) is unaffected.
 */
class HopTokenMinterRevocationTest {

    private final ScopeDeriver scopeDeriver = mock(ScopeDeriver.class);
    private final StsService stsService = mock(StsService.class);
    private final GatewayAuditService auditService = mock(GatewayAuditService.class);
    private final StsRevocationService revocationService = mock(StsRevocationService.class);

    private HopTokenMinter minter;

    @BeforeEach
    void setUp() {
        minter = new HopTokenMinter(scopeDeriver, stsService, auditService, revocationService);
    }

    @Test
    void mintForHop_revokedSession_failsClosedAndNeverMints() {
        when(auditService.resolveTenant("sess-revoked")).thenReturn("acme");
        when(revocationService.isSessionRevoked("sess-revoked")).thenReturn(true);

        // hop/chain are dereferenced only AFTER the revocation check, so null is fine here — the point is that
        // we never get that far.
        assertThatThrownBy(() ->
                minter.mintForHop(null, "sess-revoked", null, "req-1", "corr-1", 1))
                .isInstanceOf(StsMintException.class);

        verify(stsService, never()).mint(any());
    }

    @Test
    void mintForHop_openMode_checksRevocationThenSkipsMint() {
        when(auditService.resolveTenant("sess-open")).thenReturn("unknown"); // open mode / no tenant
        when(revocationService.isSessionRevoked("sess-open")).thenReturn(false);

        var result = minter.mintForHop(null, "sess-open", null, "req-2", "corr-2", 1);

        // Revocation is enforced BEFORE the open-mode skip (mode-independent, so stdio/internal hops are
        // covered), then — not revoked — open mode returns null and never mints.
        assertThat(result).isNull();
        verify(revocationService).isSessionRevoked("sess-open");
        verify(stsService, never()).mint(any());
    }
}
