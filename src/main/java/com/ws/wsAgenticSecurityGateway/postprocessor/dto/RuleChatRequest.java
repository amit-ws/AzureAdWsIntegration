package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Wire shape for the post-processor rule assistant. Either a one-shot {@code prompt} or a multi-turn conversation
 * ({@code messages}) — mirrors the policy assistant so the FE flow is identical.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleChatRequest {

    private String prompt;

    private List<ChatMessage> messages;

    /**
     * When set, the assistant is EDITING this existing rule rather than drafting a new one: it starts from the rule
     * below, applies the admin's requested change, and returns the FULL updated draft. Null = create-new mode.
     */
    private DataTagRuleDto currentRule;

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

    /** The latest user turn in a conversation, or the single prompt. */
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
