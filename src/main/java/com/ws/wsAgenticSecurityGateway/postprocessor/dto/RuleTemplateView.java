package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import com.ws.wsAgenticSecurityGateway.postprocessor.model.RuleTemplate;

import java.util.List;

/** One installable rule template as shown in the admin template browser, plus whether this tenant already has it. */
public record RuleTemplateView(
        String templateId,
        String industry,
        String regulation,
        String name,
        String description,
        String matchType,
        String pattern,
        List<String> keywords,
        List<String> dataCategories,
        String sensitivity,
        boolean installed) {

    public static RuleTemplateView from(RuleTemplate t, boolean installed) {
        return new RuleTemplateView(
                t.templateId(), t.industry(), t.regulation(), t.name(), t.description(),
                t.matchType(), t.pattern(), t.keywords(), t.dataCategories(), t.sensitivity(), installed);
    }
}
