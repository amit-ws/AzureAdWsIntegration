package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import com.ws.wsAgenticSecurityGateway.postprocessor.entity.DataTagRuleEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire shape of an admin egress-classifier rule — used both as the create/update request body and the read view.
 * Tenant is never carried here; it comes from the admin request context.
 */
public record DataTagRuleDto(
        UUID id,
        String name,
        String ruleType,
        String builtinMatcher,
        String pattern,
        String dataCategory,
        String sensitivity,
        String contextKey,
        Double confidence,
        Boolean enabled,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static DataTagRuleDto from(DataTagRuleEntity e) {
        return new DataTagRuleDto(
                e.getId(),
                e.getName(),
                e.getRuleType() == null ? null : e.getRuleType().name(),
                e.getBuiltinMatcher(),
                e.getPattern(),
                e.getDataCategory(),
                e.getSensitivity(),
                e.getContextKey(),
                e.getConfidence(),
                e.isEnabled(),
                e.getDescription(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
