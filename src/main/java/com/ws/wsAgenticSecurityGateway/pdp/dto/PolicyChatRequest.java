package com.ws.wsAgenticSecurityGateway.pdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for the multi-turn chatbot policy creation endpoint.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>Single-shot</b>: Just set {@code prompt} — generates in one call</li>
 *   <li><b>Multi-turn</b>: Set {@code messages} with conversation history — allows iterative refinement</li>
 * </ul>
 *
 * <p>If {@code messages} is provided, {@code prompt} is ignored.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyChatRequest {

    /** Natural-language description of the desired policy (single-shot mode). */
    private String prompt;

    /**
     * Conversation history for multi-turn chat.
     * Each message has a role ("user" or "assistant") and content.
     * The last message must be from "user".
     *
     * <p>Example:
     * <pre>{@code
     * [
     *   {"role": "user", "content": "Block agent X from calling tools on production"},
     *   {"role": "assistant", "content": "Which server is your production server?"},
     *   {"role": "user", "content": "The server is called 'prod-db'"}
     * ]
     * }</pre>
     */
    private List<ChatMessage> messages;

    /**
     * A single message in the conversation history.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChatMessage {
        /** "user" or "assistant" */
        private String role;
        /** Message content */
        private String content;
    }

    /**
     * Whether this request uses multi-turn conversation mode.
     */
    public boolean isMultiTurn() {
        return messages != null && !messages.isEmpty();
    }

    /**
     * Get the effective prompt — last user message in multi-turn, or prompt in single-shot.
     */
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
