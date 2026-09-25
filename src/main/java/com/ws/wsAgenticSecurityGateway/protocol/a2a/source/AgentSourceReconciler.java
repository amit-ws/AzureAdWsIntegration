package com.ws.wsAgenticSecurityGateway.protocol.a2a.source;

import com.ws.wsAgenticSecurityGateway.protocol.a2a.capability.A2aAgentIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reconciles the gateway's live agent inventory against every {@link AgentSource}. On startup it discovers the
 * agents each source reports and {@link A2aAgentIngestionService#activate activates} them — registering
 * endpoints and skills from each agent's current Agent Card, without persisting (the source owns the
 * inventory). This is the one place startup wiring lives, so adding a platform-sync source is purely additive:
 * drop in a new {@link AgentSource} bean and it is reconciled here with no other change.
 *
 * <p>Failures are isolated: a source that cannot enumerate, or a single agent that cannot be activated, is
 * logged and skipped so one bad source or agent never blocks the rest (or startup).
 */
@Component
@Slf4j
public class AgentSourceReconciler {

    private final List<AgentSource> sources;
    private final A2aAgentIngestionService ingestionService;

    public AgentSourceReconciler(List<AgentSource> sources, A2aAgentIngestionService ingestionService) {
        this.sources = sources;
        this.ingestionService = ingestionService;
    }

    /**
     * Reconcile every source once the context is ready (all beans, DB, and transport wiring are up).
     *
     * <p>{@code @Order(10)} runs this before {@code AgentCapabilityFilterService.warmCache()} ({@code @Order(100)}),
     * so a downstream agent's skills are registered in the capability registry <em>before</em> the filter cache
     * resolves each agent's skill-exposure profile — otherwise the skill sets would warm empty and every A2A
     * skill invocation would fail-closed after a restart.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void reconcileOnStartup() {
        int activated = reconcile();
        if (activated > 0) {
            log.info("AgentSource reconcile: {} agent(s) activated across {} source(s)", activated, sources.size());
        }
    }

    /** Discover and activate every agent from every source; returns how many agents were activated. */
    public int reconcile() {
        int activated = 0;
        for (AgentSource source : sources) {
            List<DiscoveredAgent> agents;
            try {
                agents = source.discover();
            } catch (Exception e) {
                log.warn("AgentSource '{}': discovery failed — {} (skipped)", source.name(), e.getMessage());
                continue;
            }
            for (DiscoveredAgent agent : agents) {
                try {
                    ingestionService.activate(agent.name(), agent.baseUrl());
                    activated++;
                } catch (Exception e) {
                    log.warn("AgentSource '{}': could not activate agent '{}' at {} — {} (skipped)",
                            source.name(), agent.name(), agent.baseUrl(), e.getMessage());
                }
            }
        }
        return activated;
    }
}
