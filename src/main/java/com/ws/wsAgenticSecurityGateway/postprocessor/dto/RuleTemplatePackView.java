package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import java.util.List;

/** An industry pack in the template browser — its templates plus how many are already installed for this tenant. */
public record RuleTemplatePackView(
        String industry,
        String regulation,
        List<RuleTemplateView> templates,
        int installedCount) {
}
