package com.ws.wsAgenticSecurityGateway.postprocessor.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shipped industry template catalog. Because these templates are code-owned and installed verbatim as
 * live detection rules, this test is the safety net: every regex must compile, ids must be unique, and each template
 * must carry a valid sensitivity + at least one category. A malformed template would silently fail to classify.
 */
class RuleTemplateCatalogTest {

    private static final Set<String> VALID_SENSITIVITY =
            Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    @Test
    void catalogIsNonEmptyAndGrouped() {
        assertThat(RuleTemplateCatalog.all()).isNotEmpty();
        assertThat(RuleTemplateCatalog.industries())
                .contains("Healthcare", "Finance", "General / GDPR", "Government", "Legal");
    }

    @Test
    void everyTemplateIdIsUnique() {
        Set<String> seen = new HashSet<>();
        for (RuleTemplate t : RuleTemplateCatalog.all()) {
            assertThat(t.templateId()).isNotBlank();
            assertThat(seen.add(t.templateId()))
                    .as("duplicate template id: %s", t.templateId()).isTrue();
        }
    }

    @Test
    void everyTemplateIsWellFormed() {
        for (RuleTemplate t : RuleTemplateCatalog.all()) {
            assertThat(t.name()).as("name for %s", t.templateId()).isNotBlank();
            assertThat(t.description()).as("description for %s", t.templateId()).isNotBlank();
            assertThat(t.industry()).as("industry for %s", t.templateId()).isNotBlank();
            assertThat(t.dataCategories()).as("categories for %s", t.templateId()).isNotEmpty();
            assertThat(VALID_SENSITIVITY)
                    .as("sensitivity for %s", t.templateId()).contains(t.sensitivity());
            assertThat(t.matchType()).isIn("REGEX", "KEYWORDS");
        }
    }

    @Test
    void regexTemplatesCompileAndKeywordTemplatesAreNonEmpty() {
        for (RuleTemplate t : RuleTemplateCatalog.all()) {
            if ("REGEX".equals(t.matchType())) {
                assertThat(t.pattern()).as("pattern for %s", t.templateId()).isNotBlank();
                // Must compile — this is exactly how the engine loads it.
                Pattern.compile(t.pattern());
            } else {
                assertThat(t.keywords()).as("keywords for %s", t.templateId()).isNotEmpty();
            }
        }
    }

    @Test
    void byIdAndByIndustryResolve() {
        RuleTemplate first = RuleTemplateCatalog.all().get(0);
        assertThat(RuleTemplateCatalog.byId(first.templateId())).contains(first);
        assertThat(RuleTemplateCatalog.byId("does.not.exist")).isEmpty();

        List<RuleTemplate> healthcare = RuleTemplateCatalog.byIndustry("Healthcare");
        assertThat(healthcare).isNotEmpty().allMatch(t -> t.industry().equals("Healthcare"));
    }
}
