package com.ws.wsAgenticSecurityGateway.protocol.a2a.outbound;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The gateway's directory of downstream A2A agents — maps a registered agent name to its base URL (the
 * endpoint the a2a client dials for discovery + JSON-RPC). This is the "today" implementation of the
 * <b>AgentSource seam</b>: agents are registered here from their self-describing Agent Card (Band 5 ingestion)
 * or manually, and a platform-sync source can populate the same directory later with no adapter change.
 *
 * <p>The {@code A2aAdapter} treats "registered here" as the A2A analogue of "connected" — an agent is reachable
 * because a known endpoint exists for it.
 */
@Component
@Slf4j
public class A2aAgentDirectory {

    private final Map<String, String> agentBaseUrls = new ConcurrentHashMap<>();

    /** Register (or update) a downstream agent's base URL under its gateway name. */
    public void register(String agentName, String baseUrl) {
        if (agentName == null || agentName.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        agentBaseUrls.put(agentName, baseUrl);
        log.info("A2A agent registered: '{}' -> {}", agentName, baseUrl);
    }

    /** Remove a downstream agent (e.g. deprovisioned). */
    public void remove(String agentName) {
        if (agentName != null) {
            agentBaseUrls.remove(agentName);
        }
    }

    /** The base URL for a registered agent, or empty if unknown. */
    public Optional<String> resolve(String agentName) {
        return agentName != null ? Optional.ofNullable(agentBaseUrls.get(agentName)) : Optional.empty();
    }

    /** Whether an agent is registered (the A2A analogue of "connected" — reachable via a known endpoint). */
    public boolean isKnown(String agentName) {
        return agentName != null && agentBaseUrls.containsKey(agentName);
    }

}
