package com.ws.wsAgenticSecurityGateway.orchestration.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.ws.wsAgenticSecurityGateway.orchestration.model.CapabilityResult;
import com.ws.wsAgenticSecurityGateway.orchestration.model.CapabilityType;
import com.ws.wsAgenticSecurityGateway.orchestration.model.Hop;
import com.ws.wsAgenticSecurityGateway.orchestration.model.RequestContext;
import com.ws.wsAgenticSecurityGateway.protocol.a2a.outbound.A2aAgentDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link A2aAdapter} — the outbound A2A leg. Proves the adapter dispatches a SKILL hop to the
 * resolved downstream agent as a spec-compliant {@code message/send}, attaches the per-hop OBO token as the
 * outbound bearer, and maps the downstream spec {@code Message}/{@code Task} back to a neutral
 * {@link CapabilityResult}. A real in-process HTTP server stands in for the agent, so the exact wire the
 * gateway emits is asserted.
 */
class A2aAdapterTest {

    private final A2aAgentDirectory directory = mock(A2aAgentDirectory.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private A2aAdapter adapter;
    private HttpServer server;
    private String baseUrl;

    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();
    private volatile String cannedResponse = "{}";
    private volatile int cannedStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        adapter = new A2aAdapter(directory, mapper);
        ReflectionTestUtils.setField(adapter, "timeoutSeconds", 5L);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = cannedResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(cannedStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        OboTokenHolder.clear();
    }

    private Hop skillHop() {
        Hop hop = new Hop(CapabilityType.SKILL, "billing.get_quote",
                Map.of("input", "quote for order 7"), null, RequestContext.empty());
        hop.resolve("billing-agent", "get_quote");
        return hop;
    }

    @Test
    void protocol_isA2A() {
        assertThat(adapter.protocol()).isEqualTo("A2A");
    }

    @Test
    void mcpCapabilities_areUnsupported() {
        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> adapter.callTool(null, null, "c", now, 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> adapter.getPrompt(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> adapter.readResource(null, "c", now, 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void isTargetConnected_delegatesToDirectory() {
        when(directory.isKnown("billing-agent")).thenReturn(true);
        assertThat(adapter.isTargetConnected("billing-agent")).isTrue();
        assertThat(adapter.isTargetConnected("unknown")).isFalse();
    }

    @Test
    void invokeSkill_sendsSpecMessageSend_attachesOboBearer_andMapsMessageResult() throws Exception {
        when(directory.resolve("billing-agent")).thenReturn(Optional.of(baseUrl));
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"kind\":\"message\",\"role\":\"agent\","
                + "\"messageId\":\"m1\",\"parts\":[{\"kind\":\"text\",\"text\":\"42 USD\"}]}}";

        OboTokenHolder.set("obo-jwt-123");
        CapabilityResult result = adapter.invokeSkill(skillHop(),
                mapper.createObjectNode().put("input", "quote for order 7"), "corr-1", LocalDateTime.now(), 1);

        assertThat(result.error()).isFalse();
        assertThat(result.summary()).isEqualTo("42 USD");

        // OBO attached as the outbound bearer.
        assertThat(capturedAuth.get()).isEqualTo("Bearer obo-jwt-123");

        // Spec-compliant JSON-RPC on the wire: method, role, skill metadata, and the input.
        JsonNode sent = mapper.readTree(capturedBody.get());
        assertThat(sent.path("method").asText()).isEqualTo("message/send");
        JsonNode message = sent.path("params").path("message");
        assertThat(message.path("role").asText()).isEqualTo("user");
        assertThat(message.path("kind").asText()).isEqualTo("message");
        assertThat(message.path("metadata").path("skillId").asText()).isEqualTo("get_quote");
        assertThat(message.path("parts").get(0).path("kind").asText()).isEqualTo("text");
        assertThat(message.path("parts").get(0).path("text").asText()).isEqualTo("quote for order 7");
    }

    @Test
    void invokeSkill_mapsTaskResult() throws Exception {
        when(directory.resolve("billing-agent")).thenReturn(Optional.of(baseUrl));
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"kind\":\"task\",\"id\":\"t1\","
                + "\"status\":{\"state\":\"completed\",\"message\":{\"role\":\"agent\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"refund done\"}]}}}}";

        CapabilityResult result = adapter.invokeSkill(skillHop(),
                mapper.createObjectNode().put("input", "x"), "c", LocalDateTime.now(), 1);

        assertThat(result.error()).isFalse();
        assertThat(result.summary()).isEqualTo("refund done");
    }

    @Test
    void invokeSkill_noObo_sendsWithoutAuthHeader() throws Exception {
        when(directory.resolve("billing-agent")).thenReturn(Optional.of(baseUrl));
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"kind\":\"message\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"ok\"}]}}";

        CapabilityResult result = adapter.invokeSkill(skillHop(),
                mapper.createObjectNode().put("input", "x"), "c", LocalDateTime.now(), 1);

        assertThat(result.summary()).isEqualTo("ok");
        assertThat(capturedAuth.get()).isNull();
    }

    @Test
    void invokeSkill_downstreamJsonRpcError_throws() {
        when(directory.resolve("billing-agent")).thenReturn(Optional.of(baseUrl));
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";

        assertThatThrownBy(() -> adapter.invokeSkill(skillHop(),
                mapper.createObjectNode().put("input", "x"), "c", LocalDateTime.now(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Method not found");
    }
}
