package com.ws.wsAgenticSecurityGateway.protocol.a2a.source;

import com.ws.wsAgenticSecurityGateway.protocol.a2a.capability.A2aAgentIngestionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link AgentSourceReconciler}: it activates every agent every {@link AgentSource} reports, and
 * isolates failures — a source that cannot enumerate, or a single agent that cannot be activated, is skipped
 * without blocking the rest. This is what lets a platform-sync source snap in additively and lets one flaky
 * downstream agent never take out startup.
 */
class AgentSourceReconcilerTest {

    private final A2aAgentIngestionService ingestion = mock(A2aAgentIngestionService.class);

    @Test
    void reconcile_activatesEveryAgentFromEverySource() {
        AgentSource selfDescribe = source("self-describe",
                new DiscoveredAgent("billing", "http://billing/a2a", "self-describe"),
                new DiscoveredAgent("support", "http://support/a2a", "self-describe"));
        AgentSource platform = source("kore-platform",
                new DiscoveredAgent("triage", "http://triage/a2a", "kore-platform"));

        AgentSourceReconciler reconciler = new AgentSourceReconciler(List.of(selfDescribe, platform), ingestion);

        int activated = reconciler.reconcile();

        assertThat(activated).isEqualTo(3);
        verify(ingestion).activate("billing", "http://billing/a2a");
        verify(ingestion).activate("support", "http://support/a2a");
        verify(ingestion).activate("triage", "http://triage/a2a");
    }

    @Test
    void reconcile_skipsSourceThatFailsToEnumerate_andStillProcessesOthers() {
        AgentSource broken = mock(AgentSource.class);
        when(broken.name()).thenReturn("broken");
        when(broken.discover()).thenThrow(new IllegalStateException("control plane unreachable"));
        AgentSource healthy = source("self-describe",
                new DiscoveredAgent("billing", "http://billing/a2a", "self-describe"));

        AgentSourceReconciler reconciler = new AgentSourceReconciler(List.of(broken, healthy), ingestion);

        int activated = reconciler.reconcile();

        assertThat(activated).isEqualTo(1);
        verify(ingestion).activate("billing", "http://billing/a2a");
    }

    @Test
    void reconcile_skipsAgentThatFailsToActivate_andStillProcessesTheRest() {
        AgentSource selfDescribe = source("self-describe",
                new DiscoveredAgent("flaky", "http://flaky/a2a", "self-describe"),
                new DiscoveredAgent("billing", "http://billing/a2a", "self-describe"));
        doThrow(new IllegalStateException("boom")).when(ingestion).activate(eq("flaky"), eq("http://flaky/a2a"));

        AgentSourceReconciler reconciler = new AgentSourceReconciler(List.of(selfDescribe), ingestion);

        int activated = reconciler.reconcile();

        assertThat(activated).isEqualTo(1);
        verify(ingestion).activate("billing", "http://billing/a2a");
    }

    @Test
    void reconcile_withNoSources_activatesNothing() {
        AgentSourceReconciler reconciler = new AgentSourceReconciler(List.of(), ingestion);

        assertThat(reconciler.reconcile()).isZero();
        verify(ingestion, never()).activate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private static AgentSource source(String name, DiscoveredAgent... agents) {
        AgentSource source = mock(AgentSource.class);
        when(source.name()).thenReturn(name);
        when(source.discover()).thenReturn(List.of(agents));
        return source;
    }
}
