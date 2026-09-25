package com.ws.wsAgenticSecurityGateway.protocol.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.ws.wsAgenticSecurityGateway.security.ProtocolRouteRegistry;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpTransportConfigTest {

    @Test
    void transportMapper_acceptsUnknownInitializeCapabilityFields() throws Exception {
        HttpTransportConfig config = new HttpTransportConfig(new ProtocolRouteRegistry());
        ObjectMapper appMapper = new ObjectMapper();
        HttpServletStreamableServerTransportProvider transport = config.mcpStreamableTransport(
                appMapper,
                new McpGatewayContextExtractor());

        String initializeParams = """
                {
                  "protocolVersion":"2025-06-18",
                  "capabilities":{
                    "elicitation":{"Form":{},"URL":{}},
                    "roots":{"listChanged":true}
                  },
                  "clientInfo":{
                    "name":"antigravity-client (via mcp-remote 0.1.37)",
                    "version":"v1.0.0"
                  }
                }
                """;

        assertThrows(
                UnrecognizedPropertyException.class,
                () -> appMapper.readValue(initializeParams, McpSchema.InitializeRequest.class));

        ObjectMapper transportMapper = extractTransportMapper(transport);
        assertDoesNotThrow(
                () -> transportMapper.readValue(initializeParams, McpSchema.InitializeRequest.class));
    }

    private static ObjectMapper extractTransportMapper(HttpServletStreamableServerTransportProvider transport)
            throws NoSuchFieldException, IllegalAccessException {
        Field mapperField = HttpServletStreamableServerTransportProvider.class.getDeclaredField("objectMapper");
        mapperField.setAccessible(true);
        return (ObjectMapper) mapperField.get(transport);
    }
}
