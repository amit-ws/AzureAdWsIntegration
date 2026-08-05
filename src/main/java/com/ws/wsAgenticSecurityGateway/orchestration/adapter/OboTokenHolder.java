package com.ws.wsAgenticSecurityGateway.orchestration.adapter;

/**
 * Request-scoped carrier for the per-hop OBO token the spine minted, so an adapter that puts it <em>on the
 * wire</em> can read it without changing the {@link ProtocolAdapter} interface.
 *
 * <p>MCP keeps the minted token brokered (see {@code HopTokenMinter} — the downstream MCP credential is not the
 * OBO token), but A2A is agent→agent: the {@code A2aAdapter} attaches this scoped, short-TTL token as the
 * outbound {@code Authorization} bearer so the downstream agent receives a delegation token representing the
 * governed act_chain, not the caller's original credential. The spine sets it right after minting and clears
 * it in the hop's {@code finally}, so it never leaks across hops or threads.
 */
public final class OboTokenHolder {

    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();

    private OboTokenHolder() {
    }

    /** Set the current hop's OBO token (a raw signed JWT), or clear it when {@code null} (open mode / skipped mint). */
    public static void set(String token) {
        if (token != null && !token.isBlank()) {
            TOKEN.set(token);
        } else {
            TOKEN.remove();
        }
    }

    /** The current hop's OBO token, or {@code null} if none was minted for this hop. */
    public static String get() {
        return TOKEN.get();
    }

    /** Clear the holder — always called in the hop's {@code finally}. */
    public static void clear() {
        TOKEN.remove();
    }
}
