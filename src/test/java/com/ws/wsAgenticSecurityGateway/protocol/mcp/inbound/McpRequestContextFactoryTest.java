package com.ws.wsAgenticSecurityGateway.protocol.mcp.inbound;

import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestAttributeKeys;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestContext;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the MCP request-context boundary: the exact extraction of client info, session id, and per-request
 * attributes from an MCP exchange into the neutral {@link RequestContext}. The governed-flow characterization
 * tests stub these to null/EMPTY, so without this test a swapped name/version or a dropped attribute in
 * {@link McpRequestContextFactory} would slip through green.
 */
class McpRequestContextFactoryTest {

    @Test
    void extractsClientInfoSessionIdAndAttributes() {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.getClientInfo()).thenReturn(new McpSchema.Implementation("agent-x", "2.1"));
        when(exchange.sessionId()).thenReturn("sess-123");
        when(exchange.transportContext()).thenReturn(McpTransportContext.create(Map.of(
                RequestAttributeKeys.AGENT_CLIENT_ID, "client-9",
                RequestAttributeKeys.JWT_SUBJECT, "sub-1")));

        RequestContext rc = McpRequestContextFactory.from(exchange);

        assertThat(rc.clientInfo()).isNotNull();
        assertThat(rc.clientInfo().name()).isEqualTo("agent-x");
        assertThat(rc.clientInfo().version()).isEqualTo("2.1");
        assertThat(rc.sessionId()).isEqualTo("sess-123");
        assertThat(rc.attributes().get(RequestAttributeKeys.AGENT_CLIENT_ID)).isEqualTo("client-9");
        assertThat(rc.attributes().get(RequestAttributeKeys.JWT_SUBJECT)).isEqualTo("sub-1");
        assertThat(rc.attributes().get("absent-key")).isNull();
    }

    @Test
    void nullExchangeYieldsEmptyContext() {
        RequestContext rc = McpRequestContextFactory.from(null);

        assertThat(rc.clientInfo()).isNull();
        assertThat(rc.sessionId()).isNull();
        assertThat(rc.attributes().get(RequestAttributeKeys.AGENT_CLIENT_ID)).isNull();
    }

    @Test
    void nullClientInfoLeavesClientInfoNullButKeepsAttributes() {
        // Stateless synthetic exchange: no clientInfo, but the JWT client_id still rides on the attributes.
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.getClientInfo()).thenReturn(null);
        when(exchange.sessionId()).thenReturn("sess-stateless");
        when(exchange.transportContext()).thenReturn(McpTransportContext.create(Map.of(
                RequestAttributeKeys.AGENT_CLIENT_ID, "client-42")));

        RequestContext rc = McpRequestContextFactory.from(exchange);

        assertThat(rc.clientInfo()).isNull();
        assertThat(rc.sessionId()).isEqualTo("sess-stateless");
        assertThat(rc.attributes().get(RequestAttributeKeys.AGENT_CLIENT_ID)).isEqualTo("client-42");
    }
}
