package com.ws.mcpAgenticAIMgmt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Builder;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class OpaEvaluationInput {
    private Input input;

    @Data
    @Builder
    public static class Input {
        private String ruleName;
        private Target target;
        private ContextualData context;

        @Data
        @Builder
        public static class Target {
            private String agentId;
            private String resourceType;
            private String resource;
            private List<String> actions;
        }

        @Data
        @Builder
        public static class ContextualData {
            private String timeZone;
            private String currentTime;
            private Integer dataSensitivity;
            private Integer agentRiskScore;
        }
    }
}