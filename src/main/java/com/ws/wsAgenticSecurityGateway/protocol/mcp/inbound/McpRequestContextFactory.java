package com.ws.wsAgenticSecurityGateway.protocol.mcp.inbound;

import com.ws.wsAgenticSecurityGateway.orchestration.model.ClientInfo;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestAttributes;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestContext;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps an inbound MCP {@link McpSyncServerExchange} to a protocol-neutral {@link RequestContext}.
 *
 * <p>This is the single place the MCP request handle is read — the caller {@code Implementation}, the
 * transport session id, and the per-request transport-context bag. Everything downstream (the spine and
 * the PDP) reads the neutral {@code RequestContext}, so no MCP SDK type leaks past this boundary. The A2A
 * inbound boundary will have its own equivalent factory building the same {@code RequestContext}.
 */
@Slf4j
final class McpRequestContextFactory {

    private McpRequestContextFactory() {
    }

    static RequestContext from(McpSyncServerExchange exchange) {
        if (exchange == null) {
            return RequestContext.empty();
        }

        ClientInfo clientInfo = null;
        try {
            McpSchema.Implementation impl = exchange.getClientInfo();
            if (impl != null) {
                clientInfo = new ClientInfo(impl.name(), impl.version());
            }
        } catch (Exception e) {
            log.debug("Could not read client info from exchange: {}", e.getMessage());
        }

        String sessionId = null;
        try {
            sessionId = exchange.sessionId();
        } catch (Exception e) {
            log.debug("Could not read session id from exchange: {}", e.getMessage());
        }

        // Read-through by key (not a copy): the spine reads attributes exactly as it did via
        // exchange.transportContext().get(key), so per-key semantics are preserved.
        RequestAttributes attributes = RequestAttributes.EMPTY;
        try {
            McpTransportContext tc = exchange.transportContext();
            if (tc != null) {
                attributes = tc::get;
            }
        } catch (Exception e) {
            log.debug("Could not read transport context from exchange: {}", e.getMessage());
        }

        return new RequestContext(clientInfo, sessionId, attributes);
    }
}
