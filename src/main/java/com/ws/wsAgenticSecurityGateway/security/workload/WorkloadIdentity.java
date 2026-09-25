package com.ws.wsAgenticSecurityGateway.security.workload;

/**
 * The verified identity of the workload (agent) making an inbound call — deliberately separate from the
 * <em>delegation</em> the token carries (the {@code act_chain} / subject). Keeping the workload identity
 * behind a seam is what lets its <em>source</em> change without touching the spine or the protocol boundaries:
 * today it comes from the validated JWT ({@code method="JWT"}); once SPIFFE is deployed it comes from the
 * workload's SVID ({@code method="SPIFFE"}) with no change to callers.
 *
 * @param agentId  the workload/agent identifier (JWT {@code client_id} today; the SPIFFE ID later)
 * @param subject  the token subject (the principal the workload acts for), if any
 * @param verified whether the gateway cryptographically verified this identity
 * @param method   how it was established — {@code "JWT"} today, {@code "SPIFFE"} later, {@code "none"} if absent
 */
public record WorkloadIdentity(String agentId, String subject, boolean verified, String method) {

    /** No identifiable workload on the request. */
    public static final WorkloadIdentity ANONYMOUS = new WorkloadIdentity(null, null, false, "none");

    public boolean isPresent() {
        return agentId != null || subject != null;
    }
}
