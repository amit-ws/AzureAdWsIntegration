package com.ws.wsAgenticSecurity.orchestration;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks all in-flight (active) tool call requests flowing through the orchestration layer.
 *
 * <p>Every tool call is registered when it enters the orchestrator and deregistered when
 * it completes (success or failure). This provides:
 * <ul>
 *   <li>Observability — see what's currently active</li>
 *   <li>Cleanup hooks — detect stale requests on shutdown</li>
 *   <li>Concurrency safety — ConcurrentHashMap for lock-free reads</li>
 * </ul>
 *
 * <p>Keyed by {@code correlationId} (UUID generated per tool call).
 */
@Component
@Slf4j
public class InFlightRequestRegistry {

    private final ConcurrentHashMap<String, InFlightEntry> registry = new ConcurrentHashMap<>();

    /**
     * Register a new in-flight request. Called at the start of orchestration.
     */
    public InFlightEntry register(String correlationId,
                                   String publicName,
                                   String serverName,
                                   String originalToolName,
                                   String sessionId) {
        InFlightEntry entry = new InFlightEntry(
                correlationId, publicName, serverName,
                originalToolName, sessionId, Instant.now());
        registry.put(correlationId, entry);
        log.debug("In-flight registered: {} → {}.{} (session={})",
                correlationId, serverName, originalToolName, sessionId);
        return entry;
    }

    /**
     * Mark a request as completed successfully and remove from registry.
     */
    public void complete(String correlationId) {
        InFlightEntry removed = registry.remove(correlationId);
        if (removed != null) {
            long durationMs = Instant.now().toEpochMilli() - removed.getStartedAt().toEpochMilli();
            log.debug("In-flight completed: {} ({}ms)", correlationId, durationMs);
        }
    }

    /**
     * Mark a request as failed and remove from registry.
     */
    public void fail(String correlationId, String reason) {
        InFlightEntry removed = registry.remove(correlationId);
        if (removed != null) {
            long durationMs = Instant.now().toEpochMilli() - removed.getStartedAt().toEpochMilli();
            log.warn("In-flight failed: {} after {}ms — {}", correlationId, durationMs, reason);
        }
    }

    /**
     * Get count of currently in-flight requests. For observability.
     */
    public int getActiveCount() {
        return registry.size();
    }

    /**
     * Get snapshot of all in-flight entries. For observability/debugging.
     */
    public Collection<InFlightEntry> getActiveEntries() {
        return Collections.unmodifiableCollection(registry.values());
    }

    /**
     * Check if a specific request is still in-flight.
     */
    public boolean isInFlight(String correlationId) {
        return registry.containsKey(correlationId);
    }

    /**
     * Immutable record representing an in-flight tool call request.
     * Tracks the full ID mapping: agent session → public tool → enterprise server + original tool.
     */
    @Getter
    public static class InFlightEntry {
        private final String correlationId;
        private final String publicName;
        private final String serverName;
        private final String originalToolName;
        private final String sessionId;
        private final Instant startedAt;

        public InFlightEntry(String correlationId,
                             String publicName,
                             String serverName,
                             String originalToolName,
                             String sessionId,
                             Instant startedAt) {
            this.correlationId = correlationId;
            this.publicName = publicName;
            this.serverName = serverName;
            this.originalToolName = originalToolName;
            this.sessionId = sessionId;
            this.startedAt = startedAt;
        }

        @Override
        public String toString() {
            return String.format("InFlight[%s: %s → %s.%s (session=%s, started=%s)]",
                    correlationId, publicName, serverName, originalToolName, sessionId, startedAt);
        }
    }
}
