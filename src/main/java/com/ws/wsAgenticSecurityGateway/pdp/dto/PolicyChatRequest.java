package com.ws.wsAgenticSecurityGateway.pdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for the chatbot policy creation endpoint.
 * Admin sends a natural-language description; LLM generates Cedar policy.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PolicyChatRequest {
    /** Natural-language description of the desired policy. */
    private String prompt;
}
