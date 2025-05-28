package com.ws.mcpAgenticAIMgmt.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.FieldDefaults;
import net.minidev.json.annotate.JsonIgnore;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PepRequest {
    @NotNull
    String enterpriseName;
    @JsonIgnore
    String enterpriseId;
    @NotNull
    String requestId; // Unique Id sent by the PEP

    @NotNull
    Subject subject;
    @NotNull
    Resource resource;
    Action action;

    @NotNull
    Environment environmentContext;


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Subject {
        @NotNull
        private String agentId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Resource {
        @NotNull
        private String resourceName;
    }


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        @NotEmpty
        private List<String> actions = new ArrayList<>();
    }


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Environment {
        private String currentTime;
        private String timeZone;
        private Integer dataSensitivityLevel;
        private Integer agentRiskScore;
    }

}
