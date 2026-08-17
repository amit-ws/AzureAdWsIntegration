package com.ws.wsAgenticSecurityGateway.orchestration.model;

/**
 * The protocol-neutral result of a governed capability invocation. The spine dispatches through the
 * {@code ProtocolAdapter}, which returns one of these; the spine audits and returns it without knowing the
 * protocol, and the protocol's inbound facade maps {@link #payload()} back to its own wire type — for MCP:
 * {@code CallToolResult} / {@code GetPromptResult} / {@code ReadResourceResult}; for A2A: its own shape.
 *
 * <p>This is the keystone of the spine/adapter seam: it is what lets {@code HopOrchestrator} return a result
 * without referencing any protocol's SDK types. The {@link #payload()} is opaque to the spine — only the
 * matching adapter and facade understand it.
 *
 * @param error     a tool-style error result (surfaced by the facade as {@code isError=true}); prompt and
 *                  resource errors propagate as exceptions instead and never reach here
 * @param summary   first text content (truncated) — neutral audit / in-flight summary, or the error message
 * @param itemCount number of content/result items (in-flight display + logging)
 * @param itemTypes comma-joined content kinds (e.g. {@code "TEXT,IMAGE"}) for in-flight display
 * @param payload   the adapter's wire-specific result; opaque to the spine, mapped by the inbound facade
 * @param fullText  the FULL, untruncated response text for egress post-processing (classifier input only —
 *                  never surfaced on any wire response); {@code ""} for errors
 */
public record CapabilityResult(boolean error, String summary, int itemCount, String itemTypes, Object payload,
                               String fullText) {

    /** A successful result carrying the adapter's wire payload plus neutral audit/in-flight metadata. */
    public static CapabilityResult ok(Object payload, String summary, int itemCount, String itemTypes) {
        return ok(payload, summary, summary, itemCount, itemTypes);
    }

    /**
     * A successful result that also carries the FULL, untruncated response text for egress post-processing.
     * {@code summary} stays the short (≤2000-char) audit/in-flight string; {@code fullText} is the complete text
     * the classifier scans off the hot path — text only, never the wire payload, and never surfaced on the wire.
     */
    public static CapabilityResult ok(Object payload, String summary, String fullText,
                                      int itemCount, String itemTypes) {
        return new CapabilityResult(false, summary != null ? summary : "", itemCount,
                itemTypes != null ? itemTypes : "", payload, fullText != null ? fullText : "");
    }

    /** An error result carrying the message; the inbound facade renders it into its protocol's error shape. */
    public static CapabilityResult error(String message) {
        return new CapabilityResult(true, message != null ? message : "", 0, "", null, "");
    }
}
