package com.ws.mcpAgenticAIMgmt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

public class PdpRequestV2 {

    private String enterpriseName;

    private PdpRequestV2.Subject subject; // Identifying the Requester (agent)
    private PdpRequestV2.Resource resource;
    private PdpRequestV2.Action action;
    private PdpRequestV2.Environment environment;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Subject {
        private String id; // e.g., AI Agent ID like "BillingBot_123"
        private String type; // e.g., "ai_agent"
        private Map<String, Object> attributes; // e.g., {"department": "finance"}
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Resource {
        private String id; // e.g., "customer_record_xyz"
        private String type; // e.g., "database_table", "api_endpoint"
        private Map<String, Object> attributes; // e.g., {"sensitivity": "high"}
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        private String id; // e.g., "read", "update_subscription"
        private Map<String, Object> attributes; // e.g., {"fields_to_update": ["status"]}
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Environment {
        private Map<String, Object> attributes; // For any other contextual data
    }
}
