package com.ws.wsAgenticSecurityGateway.orchestration;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Tracks all in-flight (active) tool call requests flowing through the orchestration layer.
 *
 * <p>Every tool call is registered when it enters the orchestrator and deregistered when
 * it completes (success or failure). This provides:
 * <ul>
 *   <li>Observability — see what's currently active</li>
 *   <li>Recent history — last {@value MAX_HISTORY} completed requests preserved in memory</li>
 *   <li>Cleanup hooks — detect stale requests on shutdown</li>
 *   <li>Concurrency safety — ConcurrentHashMap for lock-free reads</li>
 * </ul>
 *
 * <p>Keyed by {@code correlationId} (UUID generated per tool call).
 */
@Component
@Slf4j
public class InFlightRequestRegistry {

    private static final int MAX_HISTORY = 50;

    private final ConcurrentHashMap<String, InFlightEntry> registry = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<CompletedEntry> recentHistory = new ConcurrentLinkedDeque<>();

    /**
     * Register a new in-flight request. Called at the start of orchestration.
     */
    public InFlightEntry register(String correlationId,
                                   String publicName,
                                   String serverName,
                                   String originalToolName,
                                   String sessionId,
                                   String requestId,
                                   String agentName,
                                   String agentVersion) {
        InFlightEntry entry = new InFlightEntry(
                correlationId, publicName, serverName,
                originalToolName, sessionId, requestId, Instant.now(),
                agentName, agentVersion);
        registry.put(correlationId, entry);
        log.debug("In-flight registered: {} → {}.{} (session={}, agent={} v{}, requestId={})",
                correlationId, serverName, originalToolName, sessionId, agentName, agentVersion, requestId);
        return entry;
    }

    /**
     * Update the token mode after agent token resolution (step 7.5 in orchestration).
     */
    public void updateTokenMode(String correlationId, String tokenMode) {
        InFlightEntry entry = registry.get(correlationId);
        if (entry != null) {
            entry.setTokenMode(tokenMode);
        }
    }

    /**
     * Mark a request as completed successfully and remove from registry.
     * Archives the entry into recent history before removal.
     */
    public void complete(String correlationId) {
        InFlightEntry removed = registry.remove(correlationId);
        if (removed != null) {
            Instant now = Instant.now();
            long durationMs = now.toEpochMilli() - removed.getStartedAt().toEpochMilli();
            archiveToHistory(removed, now, durationMs, "SUCCESS", null);
            log.debug("In-flight completed: {} ({}ms)", correlationId, durationMs);
        }
    }

    /**
     * Mark a request as failed and remove from registry.
     * Archives the entry into recent history before removal.
     */
    public void fail(String correlationId, String reason) {
        InFlightEntry removed = registry.remove(correlationId);
        if (removed != null) {
            Instant now = Instant.now();
            long durationMs = now.toEpochMilli() - removed.getStartedAt().toEpochMilli();
            archiveToHistory(removed, now, durationMs, "FAILED", reason);
            log.warn("In-flight failed: {} after {}ms — {}", correlationId, durationMs, reason);
        }
    }

    private void archiveToHistory(InFlightEntry entry, Instant completedAt, long durationMs,
                                   String status, String errorMessage) {
        CompletedEntry completed = new CompletedEntry(
                entry.getCorrelationId(), entry.getPublicName(), entry.getServerName(),
                entry.getOriginalToolName(), entry.getSessionId(), entry.getRequestId(),
                entry.getStartedAt(), entry.getAgentName(), entry.getAgentVersion(),
                entry.getTokenMode(), completedAt, durationMs, status, errorMessage);
        recentHistory.addFirst(completed);
        while (recentHistory.size() > MAX_HISTORY) {
            recentHistory.removeLast();
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
     * Get snapshot of recent completed/failed requests. Newest first.
     * Bounded to last {@value MAX_HISTORY} entries.
     */
    public List<CompletedEntry> getRecentHistory() {
        return List.copyOf(recentHistory);
    }

    /**
     * Check if a specific request is still in-flight.
     */
    public boolean isInFlight(String correlationId) {
        return registry.containsKey(correlationId);
    }

    /**
     * Record representing an in-flight tool call request.
     * Tracks the full ID mapping: agent session → public tool → enterprise server + original tool.
     * {@code tokenMode} is mutable — set after agent token resolution.
     */
    @Getter
    public static class InFlightEntry {
        private final String correlationId;
        private final String publicName;
        private final String serverName;
        private final String originalToolName;
        private final String sessionId;
        private final String requestId;
        private final Instant startedAt;
        private final String agentName;
        private final String agentVersion;
        @lombok.Setter
        private volatile String tokenMode;

        public InFlightEntry(String correlationId,
                             String publicName,
                             String serverName,
                             String originalToolName,
                             String sessionId,
                             String requestId,
                             Instant startedAt,
                             String agentName,
                             String agentVersion) {
            this.correlationId = correlationId;
            this.publicName = publicName;
            this.serverName = serverName;
            this.originalToolName = originalToolName;
            this.sessionId = sessionId;
            this.requestId = requestId;
            this.startedAt = startedAt;
            this.agentName = agentName;
            this.agentVersion = agentVersion;
            this.tokenMode = "PENDING";
        }

        @Override
        public String toString() {
            return String.format("InFlight[%s: %s → %s.%s (agent=%s v%s, token=%s, session=%s, requestId=%s, started=%s)]",
                    correlationId, publicName, serverName, originalToolName,
                    agentName, agentVersion, tokenMode, sessionId, requestId, startedAt);
        }
    }

    /**
     * Immutable record representing a completed/failed tool call request.
     * Archived from {@link InFlightEntry} when {@link #complete} or {@link #fail} is called.
     */
    @Getter
    public static class CompletedEntry {
        private final String correlationId;
        private final String publicName;
        private final String serverName;
        private final String originalToolName;
        private final String sessionId;
        private final String requestId;
        private final Instant startedAt;
        private final String agentName;
        private final String agentVersion;
        private final String tokenMode;
        private final Instant completedAt;
        private final long durationMs;
        private final String status;
        private final String errorMessage;

        public CompletedEntry(String correlationId, String publicName, String serverName,
                              String originalToolName, String sessionId, String requestId,
                              Instant startedAt, String agentName, String agentVersion,
                              String tokenMode, Instant completedAt, long durationMs,
                              String status, String errorMessage) {
            this.correlationId = correlationId;
            this.publicName = publicName;
            this.serverName = serverName;
            this.originalToolName = originalToolName;
            this.sessionId = sessionId;
            this.requestId = requestId;
            this.startedAt = startedAt;
            this.agentName = agentName;
            this.agentVersion = agentVersion;
            this.tokenMode = tokenMode;
            this.completedAt = completedAt;
            this.durationMs = durationMs;
            this.status = status;
            this.errorMessage = errorMessage;
        }
    }
}
