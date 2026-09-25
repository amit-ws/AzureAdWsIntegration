package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The rule assistant's reply. Two shapes (mirrors the policy assistant):
 * <ul>
 *   <li><b>Follow-up</b> — {@code conversationComplete=false} + a {@code followUpQuestion}; no draft yet (it needs
 *       more from the admin, so it asks instead of guessing).</li>
 *   <li><b>Draft</b> — {@code conversationComplete=true} + the rule fields the FE pre-fills the form with; the admin
 *       reviews/tweaks and saves. {@code validationError} is set if the draft didn't pass server-side checks.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleChatResponse {

    private boolean success;

    // ── Draft rule fields (present when conversationComplete) ──
    private String suggestedName;
    private String matchType;            // REGEX or KEYWORDS
    private String pattern;              // for REGEX
    private List<String> keywords;       // for KEYWORDS
    private List<String> dataCategories;
    private String sensitivity;          // PUBLIC / INTERNAL / CONFIDENTIAL / RESTRICTED
    private String contextKey;
    private Double confidence;
    private String explanation;

    /** Non-blocking heads-up, e.g. when the admin references data not seen in this tenant yet. Never blocks Create. */
    private String dataNote;

    // ── Conversation control ──
    private boolean conversationComplete;
    private String followUpQuestion;

    // ── Diagnostics ──
    private String validationError;
    private String error;
    private String source;               // LLM_GENERATED

    public static RuleChatResponse error(String message) {
        return RuleChatResponse.builder()
                .success(false)
                .error(message)
                .conversationComplete(true)
                .build();
    }
}
