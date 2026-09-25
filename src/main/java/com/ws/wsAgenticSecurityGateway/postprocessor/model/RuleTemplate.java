package com.ws.wsAgenticSecurityGateway.postprocessor.model;

import java.util.List;

/**
 * One curated, ready-to-install egress rule shipped as part of an industry template pack (HIPAA, PCI/banking,
 * GDPR, government, legal). Templates are hand-vetted regex/keyword detectors for identifiers the built-in
 * recognizers do NOT already cover — an admin installs one (or a whole pack) with a click, and the installed copy
 * becomes an ordinary editable/deletable CUSTOM rule carrying this template's {@code templateId} as its origin
 * marker (used for de-dup and the "installed" state).
 *
 * <p>Not persisted — the catalog lives in code ({@link RuleTemplateCatalog}); only the installed rule is stored.
 */
public record RuleTemplate(
        /** Stable id, e.g. {@code hipaa.mrn} — the install marker + de-dup key. Never reused across templates. */
        String templateId,
        /** Industry grouping shown in the browser, e.g. {@code Healthcare}. */
        String industry,
        /** The regulation/standard this maps to, shown as a chip, e.g. {@code HIPAA}, {@code GDPR}. */
        String regulation,
        /** Rule name used on install (and shown in the catalog). Unique within the catalog. */
        String name,
        /** Plain-English description of what it flags — written for a non-technical admin. */
        String description,
        /** {@code REGEX} or {@code KEYWORDS}. */
        String matchType,
        /** For {@code REGEX}: the pattern (compiled as-is, so it carries its own {@code (?i)} where needed). */
        String pattern,
        /** For {@code KEYWORDS}: the literal terms, matched whole-word and case-insensitively. */
        List<String> keywords,
        /** Categories the match is tagged with (may reuse built-in category labels so filters unify). */
        List<String> dataCategories,
        /** PUBLIC / INTERNAL / CONFIDENTIAL / RESTRICTED. */
        String sensitivity,
        /** Optional proximity-boost context key ({@code ssn|card|secret|phone|iban}); usually null for templates. */
        String contextKey) {
}
