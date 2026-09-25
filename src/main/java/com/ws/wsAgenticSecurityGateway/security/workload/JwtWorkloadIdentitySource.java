package com.ws.wsAgenticSecurityGateway.security.workload;

import com.ws.wsAgenticSecurityGateway.security.GatewayOAuth2Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * The default {@link WorkloadIdentitySource}: derives the calling agent's identity from the validated OAuth2
 * JWT that {@link GatewayOAuth2Filter} stamped onto the request ({@code client_id} = the workload, {@code sub}
 * = the token subject). A present identity implies the gateway already verified the token, so it is reported
 * {@code verified}. Superseded by a SPIFFE source (marked {@code @Primary}) once mTLS / SVID is deployed —
 * with no change to the code that consumes {@link WorkloadIdentitySource}.
 */
@Component
public class JwtWorkloadIdentitySource implements WorkloadIdentitySource {

    @Override
    public String method() {
        return "JWT";
    }

    @Override
    public WorkloadIdentity resolve(HttpServletRequest request) {
        if (request == null) {
            return WorkloadIdentity.ANONYMOUS;
        }
        String agentId = str(request.getAttribute(GatewayOAuth2Filter.ATTR_CLIENT_ID));
        String subject = str(request.getAttribute(GatewayOAuth2Filter.ATTR_SUBJECT));
        if (agentId == null && subject == null) {
            return WorkloadIdentity.ANONYMOUS;
        }
        return new WorkloadIdentity(agentId, subject, true, "JWT");
    }

    private static String str(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
