package com.ws.wsAgenticSecurity.audit.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for JSONB columns.
 * Converts {@link JsonNode} &lt;-&gt; {@code String} for PostgreSQL JSONB storage.
 */
@Converter
public class JsonNodeColumnConverter implements AttributeConverter<JsonNode, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(JsonNode jsonNode) {
        if (jsonNode == null) {
            return null;
        }
        return jsonNode.toString();
    }

    @Override
    public JsonNode convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse JSONB column value", e);
        }
    }
}
