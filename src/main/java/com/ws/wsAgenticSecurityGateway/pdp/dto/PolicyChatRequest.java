package com.ws.wsAgenticSecurityGateway.pdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyChatRequest {

    private String prompt;

    private List<ChatMessage> messages;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChatMessage {
        private String role;
        private String content;
    }

    public boolean isMultiTurn() {
        return messages != null && !messages.isEmpty();
    }

    public String getEffectivePrompt() {
        if (isMultiTurn()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(messages.get(i).getRole())) {
                    return messages.get(i).getContent();
                }
            }
        }
        return prompt;
    }
}
