package com.ws.wsAgenticSecurityGateway.protocol.a2a.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link A2aRoleWire}: the A2A {@code role} enum is translated between the spec form
 * ({@code user}/{@code agent}) and the a2a-java SDK form ({@code ROLE_USER}/{@code ROLE_AGENT}) in both
 * directions, at any nesting depth, without disturbing any other field.
 */
class A2aRoleWireTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toSdk_mapsSpecRoleToProtobufEnumName() {
        assertThat(A2aRoleWire.toSdk("{\"role\":\"user\"}")).contains("\"role\":\"ROLE_USER\"");
        assertThat(A2aRoleWire.toSdk("{\"role\":\"agent\"}")).contains("\"role\":\"ROLE_AGENT\"");
    }

    @Test
    void toSpec_mapsProtobufEnumNameToSpecRole() {
        assertThat(A2aRoleWire.toSpec("{\"role\":\"ROLE_USER\"}")).contains("\"role\":\"user\"");
        assertThat(A2aRoleWire.toSpec("{\"role\":\"ROLE_AGENT\"}")).contains("\"role\":\"agent\"");
        assertThat(A2aRoleWire.toSpec("{\"role\":\"ROLE_UNSPECIFIED\"}")).contains("\"role\":\"user\"");
    }

    @Test
    void rewrites_roleNestedInsideMessageSendParams() throws Exception {
        String wire = "{\"jsonrpc\":\"2.0\",\"method\":\"message/send\",\"params\":{\"message\":"
                + "{\"kind\":\"message\",\"role\":\"user\",\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}]}}}";
        JsonNode out = mapper.readTree(A2aRoleWire.toSdk(wire));
        assertThat(out.at("/params/message/role").asText()).isEqualTo("ROLE_USER");
    }

    @Test
    void rewrites_roleInsideArrays() throws Exception {
        String wire = "{\"messages\":[{\"role\":\"user\"},{\"role\":\"agent\"}]}";
        JsonNode out = mapper.readTree(A2aRoleWire.toSdk(wire));
        assertThat(out.at("/messages/0/role").asText()).isEqualTo("ROLE_USER");
        assertThat(out.at("/messages/1/role").asText()).isEqualTo("ROLE_AGENT");
    }

    @Test
    void leaves_nonRoleFieldsUntouched_evenWhenTheyContainRoleWords() throws Exception {
        String wire = "{\"role\":\"user\",\"text\":\"the user is an agent\",\"name\":\"user\"}";
        JsonNode out = mapper.readTree(A2aRoleWire.toSdk(wire));
        assertThat(out.get("role").asText()).isEqualTo("ROLE_USER"); // only the role field flips
        assertThat(out.get("text").asText()).isEqualTo("the user is an agent");
        assertThat(out.get("name").asText()).isEqualTo("user");
    }

    @Test
    void isRoundTrip() {
        String spec = "{\"role\":\"agent\",\"parts\":[{\"kind\":\"text\",\"text\":\"x\"}]}";
        assertThat(A2aRoleWire.toSpec(A2aRoleWire.toSdk(spec))).isEqualTo(A2aRoleWire.toSpec(spec));
        assertThat(A2aRoleWire.toSpec(A2aRoleWire.toSdk(spec))).contains("\"role\":\"agent\"");
    }

    @Test
    void passesThrough_nonJsonAndBlankAndUnknownRole() {
        assertThat(A2aRoleWire.toSdk("not json at all")).isEqualTo("not json at all");
        assertThat(A2aRoleWire.toSdk("")).isEmpty();
        assertThat(A2aRoleWire.toSdk((String) null)).isNull();
        assertThat(A2aRoleWire.toSdk("{\"role\":\"system\"}")).contains("\"role\":\"system\""); // unmapped → unchanged
    }
}
