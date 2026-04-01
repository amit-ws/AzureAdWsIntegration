package com.ws.wsAgenticSecurityGateway.pdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyChatResponse {

    private boolean success;

    private String policyText;

    private String suggestedName;

    private String description;

    private String effect;

    private String explanation;

    private String error;

    private String source;

    private boolean conversationComplete;

    private String followUpQuestion;

    private String validationError;

    private String semanticWarning;

    public static PolicyChatResponse error(String message) {
        return PolicyChatResponse.builder()
                .success(false)
                .error(message)
                .conversationComplete(true)
                .build();
    }
}
