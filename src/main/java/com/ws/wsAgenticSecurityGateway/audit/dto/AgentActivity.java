package com.ws.wsAgenticSecurityGateway.audit.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One agent "activity" — a single request rolled up from its audit events (grouped by {@code traceId}).
 *
 * <p>This is the transport-agnostic unit of observability the dashboard shows in place of "sessions":
 * a session request and a stateless request each collapse to one activity. Stateless requests have no
 * session row, so this — sourced entirely from the audit trail — is how an admin sees what an agent did
 * "from behind" and, crucially, <b>which human it acted for</b>.
 *
 * @param traceId       the umbrella id shared by every leg of the request (one activity == one traceId)
 * @param sessionId     the MCP session id, or the synthetic {@code stateless-*} id for stateless requests
 * @param transport     {@code "SESSION"} or {@code "STATELESS"} (derived from the session id shape)
 * @param startedAt     earliest event timestamp in the trace
 * @param endedAt       latest event timestamp in the trace
 * @param agentName     the policy principal (Cedar {@code Agent::"..."})
 * @param agentClientId the OAuth client id — the stable cross-mode agent identifier
 * @param humanUserId   the human behind the request (null for non-delegated / machine roots)
 * @param userIdentity  human-readable identity of that human (email / username)
 * @param tokenType     {@code HUMAN_DELEGATED} / {@code AUTOMATED_AGENT}
 * @param wsTenantName  owning tenant
 * @param tools         distinct capabilities touched in this activity
 * @param serverName    the enterprise server the capability resolved to
 * @param outcome       {@code ALLOWED} / {@code DENIED} / {@code ERROR} / {@code UNKNOWN}
 * @param eventCount    number of audit events rolled up
 */
public record AgentActivity(
        String traceId,
        String sessionId,
        String transport,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        String agentName,
        String agentClientId,
        String humanUserId,
        String userIdentity,
        String tokenType,
        String wsTenantName,
        List<String> tools,
        String serverName,
        String outcome,
        int eventCount) {
}
