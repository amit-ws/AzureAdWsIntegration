package com.ws.wsAgenticSecurityGateway.security.workload;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The seam for establishing the calling workload's (agent's) verified identity from an inbound request.
 *
 * <p>Today the only implementation is {@link JwtWorkloadIdentitySource} (identity from the validated OAuth2
 * JWT). A {@code SpiffeWorkloadIdentitySource} (identity from the workload's SVID over mTLS) can be added as a
 * new bean later and selected via {@code @Primary} / config — the inbound boundaries depend on <em>this
 * interface</em>, not on any concrete source, so SPIFFE snaps in with no change to the spine, the PDP, the
 * act_chain, or the adapters.
 */
public interface WorkloadIdentitySource {

    /** How this source establishes identity — {@code "JWT"}, {@code "SPIFFE"}, … (for logging / audit). */
    String method();

    /** Resolve the calling workload's identity from the inbound request; {@link WorkloadIdentity#ANONYMOUS} if none. */
    WorkloadIdentity resolve(HttpServletRequest request);
}
