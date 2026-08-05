package com.ws.wsAgenticSecurityGateway.protocol.a2a.source;

import java.util.List;

/**
 * The seam for <em>where the gateway's downstream-agent inventory comes from</em>.
 *
 * <p>Today the only implementation is {@link SelfDescribeAgentSource} (agents an admin ingested, persisted in
 * the gateway's own DB). A {@code PlatformAgentSource} that pulls the agent roster from a vendor control plane
 * (Kore.ai, Automation Anywhere, …) can be added as a new bean later — {@code AgentSourceReconciler} discovers
 * every {@code AgentSource} bean and activates whatever they report, so platform-sync snaps in with no change
 * to the ingestion service, the directory, the capability registry, or the spine.
 */
public interface AgentSource {

    /** Which source this is — {@code "self-describe"}, {@code "kore-platform"}, … (for logging / audit). */
    String name();

    /** The downstream agents this source currently knows about; empty if none. Never {@code null}. */
    List<DiscoveredAgent> discover();
}
