package com.ws.mcpAgenticAIMgmt.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;

public class JsonConverter {

    public static String convertToJson(Object requestBody) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            return mapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            System.out.println("Error in convertToJson: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
