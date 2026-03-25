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
 *   <li>Observability — see what's currently active with full flow context</li>
 *   <li>Recent history — last {@value MAX_HISTORY} completed requests preserved in memory</li>
 *   <li>Dual session tracking — both Agent↔Gateway and Gateway↔MCP Server sessions</li>
 *   <li>Request/Response capture — truncated payloads for admin visibility</li>
 *   <li>Timing breakdown — registry lookup, forward call, and total durations</li>
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
                                   String agentSessionId,
                                   String requestId,
                                   String agentName,
                                   String agentVersion) {
        InFlightEntry entry = new InFlightEntry(
                correlationId, publicName, serverName,
                originalToolName, agentSessionId, requestId, Instant.now(),
                agentName, agentVersion);
        registry.put(correlationId, entry);
        log.debug("In-flight registered: {} → {}.{} (agentSession={}, agent={} v{}, requestId={})",
                correlationId, serverName, originalToolName, agentSessionId, agentName, agentVersion, requestId);
        return entry;
    }

    // ── Mid-flight update methods ────────────────────────────────────────

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
     * Update the client (WS Client-side) session ID — Gateway ↔ MCP Server.
     * Set after registry lookup when serverName is resolved (step 5).
     */
    public void updateClientSession(String correlationId, String clientSessionId) {
        InFlightEntry entry = registry.get(correlationId);
        if (entry != null) {
            entry.setClientSessionId(clientSessionId);
        }
    }

    /**
     * Update the captured request arguments (truncated JSON string).
     * Set after argument conversion (step 7).
     */
    public void updateRequest(String correlationId, String requestArgs) {
        InFlightEntry entry = registry.get(correlationId);
        if (entry != null) {
            entry.setRequestArgs(requestArgs);
        }
    }

    /**
     * Update the captured response summary after the MCP server call returns (step 8).
     *
     * @param correlationId  the request's correlation ID
     * @param summary        first N chars of text content (truncated)
     * @param contentCount   number of Content items returned
     * @param contentTypes   comma-separated content type labels (e.g. "TEXT,IMAGE")
     */
    public void updateResponse(String correlationId, String summary, int contentCount, String contentTypes) {
        InFlightEntry entry = registry.get(correlationId);
        if (entry != null) {
            entry.setResponseSummary(summary);
            entry.setResponseContentCount(contentCount);
            entry.setResponseContentTypes(contentTypes);
        }
    }

    /**
     * Update timing breakdown for the request.
     *
     * @param correlationId    the request's correlation ID
     * @param registryLookupMs time spent on capability registry lookup (step 4-5)
     * @param forwardCallMs    time spent forwarding the call to the MCP server (step 8)
     */
    public void updateTimings(String correlationId, long registryLookupMs, long forwardCallMs) {
        InFlightEntry entry = registry.get(correlationId);
        if (entry != null) {
            entry.setRegistryLookupMs(registryLookupMs);
            entry.setForwardCallMs(forwardCallMs);
        }
    }

    // ── Completion methods ───────────────────────────────────────────────

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
            log.info("In-flight completed: {} ({}ms)", correlationId, durationMs);
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
                entry.getOriginalToolName(), entry.getAgentSessionId(), entry.getRequestId(),
                entry.getStartedAt(), entry.getAgentName(), entry.getAgentVersion(),
                entry.getTokenMode(), entry.getClientSessionId(),
                entry.getRequestArgs(), entry.getResponseSummary(),
                entry.getResponseContentCount(), entry.getResponseContentTypes(),
                entry.getRegistryLookupMs(), entry.getForwardCallMs(),
                completedAt, durationMs, status, errorMessage);
        recentHistory.addFirst(completed);
        log.info("📊 Archived to history: {} status={} durationMs={} (history size: {})",
                entry.getCorrelationId(), status, durationMs, recentHistory.size());
        while (recentHistory.size() > MAX_HISTORY) {
            recentHistory.removeLast();
        }
    }

    // ── Query methods ────────────────────────────────────────────────────

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

    // ═════════════════════════════════════════════════════════════════════
    //  Inner classes
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Record representing an in-flight tool call request.
     *
     * <p>Tracks the full end-to-end flow: Agent → Gateway → MCP Server.
     * Fields marked with {@code @Setter volatile} are updated mid-flight as
     * the orchestration progresses through its steps.
     *
     * <p><strong>Mutable fields (set progressively):</strong>
     * <ul>
     *   <li>{@code tokenMode} — set after agent token resolution (step 7.5)</li>
     *   <li>{@code clientSessionId} — set after registry lookup resolves server (step 5)</li>
     *   <li>{@code requestArgs} — set after argument conversion (step 7)</li>
     *   <li>{@code responseSummary}, {@code responseContentCount}, {@code responseContentTypes}
     *       — set after MCP server responds (step 8)</li>
     *   <li>{@code registryLookupMs}, {@code forwardCallMs} — set at completion</li>
     * </ul>
     */
    @Getter
    public static class InFlightEntry {
        private final String correlationId;
        private final String publicName;
        private final String serverName;
        private final String originalToolName;
        private final String agentSessionId;
        private final String requestId;
        private final Instant startedAt;
        private final String agentName;
        private final String agentVersion;

        // Mutable fields — updated mid-flight
        @lombok.Setter private volatile String tokenMode;
        @lombok.Setter private volatile String clientSessionId;
        @lombok.Setter private volatile String requestArgs;
        @lombok.Setter private volatile String responseSummary;
        @lombok.Setter private volatile int responseContentCount;
        @lombok.Setter private volatile String responseContentTypes;
        @lombok.Setter private volatile long registryLookupMs;
        @lombok.Setter private volatile long forwardCallMs;

        public InFlightEntry(String correlationId,
                             String publicName,
                             String serverName,
                             String originalToolName,
                             String agentSessionId,
                             String requestId,
                             Instant startedAt,
                             String agentName,
                             String agentVersion) {
            this.correlationId = correlationId;
            this.publicName = publicName;
            this.serverName = serverName;
            this.originalToolName = originalToolName;
            this.agentSessionId = agentSessionId;
            this.requestId = requestId;
            this.startedAt = startedAt;
            this.agentName = agentName;
            this.agentVersion = agentVersion;
            this.tokenMode = "PENDING";
        }

        @Override
        public String toString() {
            return String.format("InFlight[%s: %s → %s.%s (agent=%s v%s, token=%s, agentSession=%s, clientSession=%s, requestId=%s)]",
                    correlationId, publicName, serverName, originalToolName,
                    agentName, agentVersion, tokenMode, agentSessionId, clientSessionId, requestId);
        }
    }

    /**
     * Immutable record representing a completed/failed tool call request.
     * Archived from {@link InFlightEntry} when {@link #complete} or {@link #fail} is called.
     * Captures all mutable fields at the moment of completion for full flow visibility.
     */
    @Getter
    public static class CompletedEntry {
        private final String correlationId;
        private final String publicName;
        private final String serverName;
        private final String originalToolName;
        private final String agentSessionId;
        private final String requestId;
        private final Instant startedAt;
        private final String agentName;
        private final String agentVersion;
        private final String tokenMode;
        private final String clientSessionId;
        private final String requestArgs;
        private final String responseSummary;
        private final int responseContentCount;
        private final String responseContentTypes;
        private final long registryLookupMs;
        private final long forwardCallMs;
        private final Instant completedAt;
        private final long durationMs;
        private final String status;
        private final String errorMessage;

        public CompletedEntry(String correlationId, String publicName, String serverName,
                              String originalToolName, String agentSessionId, String requestId,
                              Instant startedAt, String agentName, String agentVersion,
                              String tokenMode, String clientSessionId,
                              String requestArgs, String responseSummary,
                              int responseContentCount, String responseContentTypes,
                              long registryLookupMs, long forwardCallMs,
                              Instant completedAt, long durationMs,
                              String status, String errorMessage) {
            this.correlationId = correlationId;
            this.publicName = publicName;
            this.serverName = serverName;
            this.originalToolName = originalToolName;
            this.agentSessionId = agentSessionId;
            this.requestId = requestId;
            this.startedAt = startedAt;
            this.agentName = agentName;
            this.agentVersion = agentVersion;
            this.tokenMode = tokenMode;
            this.clientSessionId = clientSessionId;
            this.requestArgs = requestArgs;
            this.responseSummary = responseSummary;
            this.responseContentCount = responseContentCount;
            this.responseContentTypes = responseContentTypes;
            this.registryLookupMs = registryLookupMs;
            this.forwardCallMs = forwardCallMs;
            this.completedAt = completedAt;
            this.durationMs = durationMs;
            this.status = status;
            this.errorMessage = errorMessage;
        }
    }
}
