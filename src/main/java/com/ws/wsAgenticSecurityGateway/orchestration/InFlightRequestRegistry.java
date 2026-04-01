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

@Component
@Slf4j
public class InFlightRequestRegistry {

    private static final int MAX_HISTORY = 50;

    private final ConcurrentHashMap<String, InFlightEntry> registry = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<CompletedEntry> recentHistory = new ConcurrentLinkedDeque<>();

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

    public void updateTokenMode(String correlationId, String tokenMode) {
        InFlightEntry entry = registry.get(correlationId);
        if (entry != null) {
            entry.setTokenMode(tokenMode);
        }
    }

    public void updateClientSession(String correlationId, String clientSessionId) {
        InFlightEntry entry = registry.get(correlationId);
        if (entry != null) {
            entry.setClientSessionId(clientSessionId);
        }
    }

    public void updateRequest(String correlationId, String requestArgs) {
        InFlightEntry entry = registry.get(correlationId);
        if (entry != null) {
            entry.setRequestArgs(requestArgs);
        }
    }

    public void updateResponse(String correlationId, String summary, int contentCount, String contentTypes) {
        InFlightEntry entry = registry.get(correlationId);
        if (entry != null) {
            entry.setResponseSummary(summary);
            entry.setResponseContentCount(contentCount);
            entry.setResponseContentTypes(contentTypes);
        }
    }

    public void updateTimings(String correlationId, long registryLookupMs, long forwardCallMs) {
        InFlightEntry entry = registry.get(correlationId);
        if (entry != null) {
            entry.setRegistryLookupMs(registryLookupMs);
            entry.setForwardCallMs(forwardCallMs);
        }
    }

    public void complete(String correlationId) {
        InFlightEntry removed = registry.remove(correlationId);
        if (removed != null) {
            Instant now = Instant.now();
            long durationMs = now.toEpochMilli() - removed.getStartedAt().toEpochMilli();
            archiveToHistory(removed, now, durationMs, "SUCCESS", null);
            log.info("In-flight completed: {} ({}ms)", correlationId, durationMs);
        }
    }

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
 log.info("Archived to history: {} status={} durationMs={} (history size: {})",
                entry.getCorrelationId(), status, durationMs, recentHistory.size());
        while (recentHistory.size() > MAX_HISTORY) {
            recentHistory.removeLast();
        }
    }

    public int getActiveCount() {
        return registry.size();
    }

    public Collection<InFlightEntry> getActiveEntries() {
        return Collections.unmodifiableCollection(registry.values());
    }

    public List<CompletedEntry> getRecentHistory() {
        return List.copyOf(recentHistory);
    }

    public boolean isInFlight(String correlationId) {
        return registry.containsKey(correlationId);
    }

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
