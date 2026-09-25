package com.ws.wsAgenticSecurityGateway.postprocessor.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shipped, hand-vetted library of industry rule templates. This is a code-owned catalog (not tenant data and
 * not AI-generated) so the rules are reviewable and stable across tenants. Every template targets an identifier the
 * built-in recognizers do NOT already cover (SSN, cards, IBAN, emails, IPs, secrets are built in) — so installing a
 * pack never duplicates a built-in detector.
 *
 * <p>Patterns are deliberately <em>label-anchored</em> (they require the field name — "MRN", "NPI", "routing",
 * "passport", ...) rather than trying to match a bare number. That keeps false positives low: a template is a safe
 * starting point an admin can install with confidence and then tune, not an aggressive net.
 */
public final class RuleTemplateCatalog {

    private RuleTemplateCatalog() {
    }

    // ── Healthcare / HIPAA — Protected Health Information identifiers ────────────────────────────────
    private static final List<RuleTemplate> HEALTHCARE = List.of(
            regex("hipaa.mrn", "Healthcare", "HIPAA", "Medical record number (MRN)",
                    "Flags medical record numbers written with an 'MRN' label, e.g. \"MRN: 00821345\".",
                    "(?i)\\bMRN\\s*[:#]?\\s*\\d{6,10}\\b", List.of("PHI"), "RESTRICTED"),
            regex("hipaa.npi", "Healthcare", "HIPAA", "National Provider Identifier (NPI)",
                    "Flags 10-digit US National Provider Identifiers labelled 'NPI'.",
                    "(?i)\\bNPI\\s*[:#]?\\s*\\d{10}\\b", List.of("PHI"), "CONFIDENTIAL"),
            regex("hipaa.icd10", "Healthcare", "HIPAA", "ICD-10 diagnosis code",
                    "Flags ICD-10 diagnosis codes prefixed with 'ICD-10', e.g. \"ICD-10 E11.9\".",
                    "(?i)\\bICD-?10\\b[\\s:#]{0,4}[A-TV-Z][0-9][0-9A-Z](?:\\.[0-9A-Z]{1,4})?\\b",
                    List.of("PHI"), "CONFIDENTIAL"),
            regex("hipaa.dea", "Healthcare", "HIPAA", "DEA registration number",
                    "Flags DEA prescriber registration numbers labelled 'DEA' (2 letters + 7 digits).",
                    "(?i)\\bDEA\\s*[:#]?\\s*[A-Z]{2}\\d{7}\\b", List.of("PHI"), "RESTRICTED"));

    // ── Finance / Banking — GLBA-relevant identifiers not covered by the card/IBAN built-ins ─────────
    private static final List<RuleTemplate> FINANCE = List.of(
            regex("fin.aba_routing", "Finance", "Banking", "Bank routing number (ABA)",
                    "Flags 9-digit US bank routing numbers labelled 'routing' or 'ABA'.",
                    "(?i)\\b(?:ABA|routing)(?:\\s*(?:number|no\\.?|#))?\\s*[:#]?\\s*\\d{9}\\b",
                    List.of("FINANCIAL"), "CONFIDENTIAL"),
            regex("fin.swift_bic", "Finance", "Banking", "SWIFT / BIC code",
                    "Flags SWIFT/BIC bank identifier codes labelled 'SWIFT' or 'BIC'.",
                    "(?i)\\b(?:SWIFT|BIC)\\s*[:#]?\\s*[A-Z]{6}[A-Z0-9]{2}(?:[A-Z0-9]{3})?\\b",
                    List.of("FINANCIAL"), "CONFIDENTIAL"),
            regex("fin.bank_account", "Finance", "Banking", "Bank account number",
                    "Flags bank account numbers labelled 'account' or 'acct' (8–17 digits).",
                    "(?i)\\b(?:account|acct)(?:\\s*(?:number|no\\.?|#))?\\s*[:#]?\\s*\\d{8,17}\\b",
                    List.of("FINANCIAL"), "RESTRICTED"));

    // ── General PII / GDPR — identity documents the SSN/email built-ins don't cover ──────────────────
    private static final List<RuleTemplate> GENERAL_PII = List.of(
            regex("gdpr.passport", "General / GDPR", "GDPR", "Passport number",
                    "Flags passport numbers labelled 'passport' (6–9 alphanumeric characters).",
                    "(?i)\\bpassport(?:\\s*(?:number|no\\.?|#))?\\s*[:#]?\\s*[A-Z0-9]{6,9}\\b",
                    List.of("PII"), "RESTRICTED"),
            regex("gdpr.drivers_license", "General / GDPR", "GDPR", "Driver's licence number",
                    "Flags driver's licence numbers labelled 'driver's license' or 'DL'.",
                    "(?i)\\b(?:driver'?s?\\s*licen[sc]e|DL)(?:\\s*(?:number|no\\.?|#))?\\s*[:#]?\\s*[A-Z0-9]{5,15}\\b",
                    List.of("PII"), "CONFIDENTIAL"),
            regex("gdpr.eu_vat", "General / GDPR", "GDPR", "EU VAT number",
                    "Flags EU VAT identification numbers labelled 'VAT'.",
                    "(?i)\\bVAT(?:\\s*(?:number|no\\.?|#|id))?\\s*[:#]?\\s*[A-Z]{2}[A-Z0-9]{2,12}\\b",
                    List.of("PII"), "CONFIDENTIAL"));

    // ── Government / Defense — classification & clearance markings ───────────────────────────────────
    private static final List<RuleTemplate> GOVERNMENT = List.of(
            keywords("gov.classification_markings", "Government", "Classification",
                    "Classification markings",
                    "Flags US government classification and handling markings in a response.",
                    List.of("TOP SECRET", "NOFORN", "CUI", "SCI", "FOUO", "CLASSIFIED"),
                    List.of("CLASSIFICATION"), "RESTRICTED"),
            keywords("gov.clearance", "Government", "Classification",
                    "Security clearance references",
                    "Flags references to personnel security clearances.",
                    List.of("security clearance", "clearance level", "cleared personnel"),
                    List.of("CLASSIFICATION"), "CONFIDENTIAL"));

    // ── Legal — privilege markings & matter identifiers ──────────────────────────────────────────────
    private static final List<RuleTemplate> LEGAL = List.of(
            keywords("legal.privilege", "Legal", "Privilege",
                    "Attorney-client privilege markings",
                    "Flags attorney-client privilege and work-product markings.",
                    List.of("privileged and confidential", "attorney-client privilege",
                            "attorney work product", "work product doctrine"),
                    List.of("LEGAL"), "CONFIDENTIAL"),
            regex("legal.matter_number", "Legal", "Privilege", "Legal matter / case number",
                    "Flags legal matter, case, or docket numbers labelled 'matter', 'case', or 'docket'.",
                    "(?i)\\b(?:matter|case|docket)(?:\\s*(?:number|no\\.?|#|id))?\\s*[:#]?\\s*[A-Z0-9][A-Z0-9-]{3,19}\\b",
                    List.of("LEGAL"), "INTERNAL"));

    /** The full catalog, in display order (packs then templates within a pack). */
    private static final List<RuleTemplate> ALL = concat(HEALTHCARE, FINANCE, GENERAL_PII, GOVERNMENT, LEGAL);

    private static final Map<String, RuleTemplate> BY_ID = index(ALL);

    public static List<RuleTemplate> all() {
        return ALL;
    }

    public static Optional<RuleTemplate> byId(String templateId) {
        return Optional.ofNullable(templateId == null ? null : BY_ID.get(templateId.trim()));
    }

    /** All templates in one industry (in catalog order); empty if the industry is unknown. */
    public static List<RuleTemplate> byIndustry(String industry) {
        return ALL.stream()
                .filter(t -> t.industry().equalsIgnoreCase(industry == null ? "" : industry.trim()))
                .toList();
    }

    /** Industries in display order (deduped, first-seen order). */
    public static List<String> industries() {
        return ALL.stream().map(RuleTemplate::industry).distinct().toList();
    }

    // ── construction helpers ─────────────────────────────────────────────────────────────────────────
    private static RuleTemplate regex(String id, String industry, String regulation, String name,
                                      String description, String pattern, List<String> categories, String sensitivity) {
        return new RuleTemplate(id, industry, regulation, name, description,
                "REGEX", pattern, List.of(), categories, sensitivity, null);
    }

    private static RuleTemplate keywords(String id, String industry, String regulation, String name,
                                         String description, List<String> keywords, List<String> categories,
                                         String sensitivity) {
        return new RuleTemplate(id, industry, regulation, name, description,
                "KEYWORDS", null, keywords, categories, sensitivity, null);
    }

    @SafeVarargs
    private static List<RuleTemplate> concat(List<RuleTemplate>... groups) {
        java.util.List<RuleTemplate> out = new java.util.ArrayList<>();
        for (List<RuleTemplate> g : groups) {
            out.addAll(g);
        }
        return List.copyOf(out);
    }

    private static Map<String, RuleTemplate> index(List<RuleTemplate> all) {
        Map<String, RuleTemplate> m = new LinkedHashMap<>();
        for (RuleTemplate t : all) {
            m.put(t.templateId(), t);
        }
        return Map.copyOf(m);
    }
}
