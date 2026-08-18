package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import com.ws.wsAgenticSecurityGateway.postprocessor.entity.DataTagRuleEntity;
import com.ws.wsAgenticSecurityGateway.postprocessor.model.RuleTemplate;
import com.ws.wsAgenticSecurityGateway.postprocessor.model.RuleTemplateCatalog;

import java.time.LocalDateTime;
import java.util.List;
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
        String matchType,
        String pattern,
        List<String> keywords,
        List<String> dataCategories,
        String sensitivity,
        String contextKey,
        Double confidence,
        Boolean enabled,
        String description,
        String sourceTemplateId,
        /** When this rule came from a template pack: that pack's industry (e.g. "Finance") — for the UI badge. */
        String sourceTemplateIndustry,
        /** When this rule came from a template pack: the template's canonical title — for the UI badge tooltip. */
        String sourceTemplateName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static DataTagRuleDto from(DataTagRuleEntity e) {
        RuleTemplate tmpl = e.getSourceTemplateId() == null
                ? null
                : RuleTemplateCatalog.byId(e.getSourceTemplateId()).orElse(null);
        return new DataTagRuleDto(
                e.getId(),
                e.getName(),
                e.getRuleType() == null ? null : e.getRuleType().name(),
                e.getBuiltinMatcher(),
                e.getMatchType(),
                e.getPattern(),
                e.getKeywords(),
                e.getDataCategories(),
                e.getSensitivity(),
                e.getContextKey(),
                e.getConfidence(),
                e.isEnabled(),
                e.getDescription(),
                e.getSourceTemplateId(),
                tmpl == null ? null : tmpl.industry(),
                tmpl == null ? null : tmpl.name(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
