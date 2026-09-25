package com.ws.wsAgenticSecurityGateway.security.workload;

import com.ws.wsAgenticSecurityGateway.security.GatewayOAuth2Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link JwtWorkloadIdentitySource}: the default seam derives the calling agent's identity from
 * the {@code jwt.*} request attributes {@link GatewayOAuth2Filter} stamped after validating the token, and
 * reports {@link WorkloadIdentity#ANONYMOUS} when the request carries no identity.
 */
class JwtWorkloadIdentitySourceTest {

    private final JwtWorkloadIdentitySource source = new JwtWorkloadIdentitySource();

    @Test
    void resolve_readsClientIdAndSubject_andMarksVerified() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ID)).thenReturn("billing-agent");
        when(request.getAttribute(GatewayOAuth2Filter.ATTR_SUBJECT)).thenReturn("svc-account-42");

        WorkloadIdentity id = source.resolve(request);

        assertThat(id.agentId()).isEqualTo("billing-agent");
        assertThat(id.subject()).isEqualTo("svc-account-42");
        assertThat(id.verified()).isTrue();
        assertThat(id.method()).isEqualTo("JWT");
        assertThat(id.isPresent()).isTrue();
    }

    @Test
    void resolve_withNoIdentityAttributes_isAnonymous() {
        HttpServletRequest request = mock(HttpServletRequest.class);

        WorkloadIdentity id = source.resolve(request);

        assertThat(id).isSameAs(WorkloadIdentity.ANONYMOUS);
        assertThat(id.isPresent()).isFalse();
        assertThat(id.verified()).isFalse();
    }

    @Test
    void resolve_withNullRequest_isAnonymous() {
        assertThat(source.resolve(null)).isSameAs(WorkloadIdentity.ANONYMOUS);
    }

    @Test
    void resolve_ignoresBlankAttributes() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ID)).thenReturn("   ");
        when(request.getAttribute(GatewayOAuth2Filter.ATTR_SUBJECT)).thenReturn("");

        WorkloadIdentity id = source.resolve(request);

        assertThat(id).isSameAs(WorkloadIdentity.ANONYMOUS);
    }
}
