package com.ws.wsAgenticSecurityGateway.postprocessor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.Sensitivity;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.DataTagRuleDto;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.DetectorInfo;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.RuleChatRequest;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.RuleChatResponse;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.GatewayResponseClassificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The natural-language rule assistant for the egress post-processor — the "describe it and AI drafts the rule" half,
 * matching the Policy assistant so the admin experience is identical. Grounded in the tenant's REAL data (its
 * classified categories, the producers whose responses flow through, and where sensitive data has actually appeared)
 * so its suggestions and drafts reflect what this tenant handles — never invented generic examples. Drafting only;
 * the existing rule CRUD saves after the admin approves.
 *
 * <p>Mirrors {@code PolicyLlmService}: raw Anthropic call, {@code claude-haiku-4-5}, same API key.
 */
@Service
@Slf4j
public class RuleAssistantService {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-haiku-4-5";
    private static final int MAX_TOKENS = 1500;
    private static final long SUGGEST_TTL_MS = 300_000; // 5 min — starter suggestions rarely change

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final PostProcessorRuleService ruleService;
    private final GatewayResponseClassificationRepository classificationRepo;

    /** Reuses the gateway's single Anthropic key (same property the policy/capability assistants use). */
    @Value("${ws.gateway.pdp.anthropic-api-key:}")
    private String anthropicApiKey;

    private final Map<String, CachedSuggestions> suggestionCache = new ConcurrentHashMap<>();

    private record CachedSuggestions(List<String> list, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    public RuleAssistantService(ObjectMapper objectMapper,
                                PostProcessorRuleService ruleService,
                                GatewayResponseClassificationRepository classificationRepo) {
        this.objectMapper = objectMapper;
        this.ruleService = ruleService;
        this.classificationRepo = classificationRepo;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isLlmAvailable() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }

    // ── Chat (describe → follow-up or draft) ──────────────────────────────────

    public RuleChatResponse chat(RuleChatRequest request) {
        String prompt = request == null ? null : request.getEffectivePrompt();
        if (prompt == null || prompt.isBlank()) {
            return RuleChatResponse.error(
                    "Describe the rule you want — for example, \"flag internal ticket ids like ACME-1234 as internal\".");
        }
        if (!isLlmAvailable()) {
            return RuleChatResponse.error("The AI assistant is not configured. You can still create rules manually.");
        }
        long start = System.currentTimeMillis();
        try {
            List<Map<String, String>> messages = request.isMultiTurn()
                    ? request.getMessages().stream()
                        .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                        .collect(Collectors.toList())
                    : List.of(Map.of("role", "user", "content", request.getPrompt()));
            RuleChatResponse response = parseDraft(anthropicText(buildSystemPrompt(), messages));
            log.info("Rule assistant: prompt='{}' → complete={}, hasDraft={}, hasFollowUp={} ({}ms)",
                    abbreviate(prompt), response.isConversationComplete(),
                    response.getMatchType() != null, response.getFollowUpQuestion() != null,
                    System.currentTimeMillis() - start);
            return response;
        } catch (Exception e) {
            log.error("Rule assistant call failed: {}", e.getMessage(), e);
            return RuleChatResponse.error("The AI assistant call failed. Please try again, or create the rule manually.");
        }
    }

    // ── Starter suggestions, grounded in the tenant's actual data ─────────────

    public List<String> suggestions() {
        String tenant = TenantContext.get();
        if (!isLlmAvailable()) {
            return fallbackSuggestions();
        }
        CachedSuggestions cached = tenant == null ? null : suggestionCache.get(tenant);
        if (cached != null && !cached.expired()) {
            return cached.list();
        }
        try {
            List<String> list = callSuggestions();
            if (tenant != null) {
                suggestionCache.put(tenant, new CachedSuggestions(list, System.currentTimeMillis() + SUGGEST_TTL_MS));
            }
            return list;
        } catch (Exception e) {
            log.debug("Rule suggestions failed, using fallback: {}", e.getMessage());
            return fallbackSuggestions();
        }
    }

    private List<String> callSuggestions() throws Exception {
        String system = "You help an admin start creating egress data-classification rules for their tenant. "
                + "The rules flag sensitive data in the responses agents/tools return."
                + buildLiveDataSection()
                + "\n\nReturn ONLY a JSON array of exactly 3 short (max ~10 words) example rule-creation prompts THIS "
                + "tenant's admin might realistically type, each grounded in the data above (reference real categories "
                + "or producers where it helps). If the tenant has no data yet, give 3 sensible generic ones. Never use "
                + "fake company names like ACME or Globex. Example: [\"Flag account numbers as restricted\", \"...\", \"...\"]. "
                + "No prose — just the array.";
        String text = anthropicText(system, List.of(Map.of("role", "user", "content", "Suggest 3 starter prompts.")));
        String block = extractJsonBlock(text);
        String json = (block != null && !block.isBlank()) ? block : text.trim();
        JsonNode arr = objectMapper.readTree(json);
        List<String> out = new ArrayList<>();
        if (arr.isArray()) {
            arr.forEach(n -> {
                String s = n.asText("").trim();
                if (!s.isBlank()) {
                    out.add(s);
                }
            });
        }
        return out.isEmpty() ? fallbackSuggestions() : out.subList(0, Math.min(3, out.size()));
    }

    private List<String> fallbackSuggestions() {
        return List.of(
                "Flag internal project codenames as internal",
                "Flag account numbers as restricted",
                "Flag legal or contract terms as confidential");
    }

    // ── Anthropic transport (shared by chat + suggestions) ────────────────────

    private String anthropicText(String system, List<Map<String, String>> messages) throws Exception {
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "max_tokens", MAX_TOKENS,
                "system", system,
                "messages", messages);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", anthropicApiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.error("Anthropic API returned {}: {}", resp.statusCode(), resp.body());
            throw new IllegalStateException("Anthropic HTTP " + resp.statusCode());
        }
        JsonNode content = objectMapper.readTree(resp.body()).path("content");
        if (!content.isArray() || content.isEmpty()) {
            throw new IllegalStateException("Empty response from Anthropic");
        }
        return content.get(0).path("text").asText("");
    }

    private RuleChatResponse parseDraft(String text) {
        try {
            String block = extractJsonBlock(text);
            if (block == null || block.isBlank()) {
                // No JSON draft → the model is asking for more; surface it as a follow-up.
                return RuleChatResponse.builder()
                        .success(true).conversationComplete(false)
                        .followUpQuestion(text.trim()).source("LLM_GENERATED")
                        .build();
            }
            JsonNode draft = objectMapper.readTree(block);
            String matchType = draft.path("matchType").asText("REGEX").trim().toUpperCase(Locale.ROOT);
            if (!matchType.equals("KEYWORDS")) {
                matchType = "REGEX";
            }
            String pattern = textOrNull(draft, "pattern");
            List<String> keywords = stringList(draft, "keywords");
            List<String> categories = stringList(draft, "categories");
            String sensitivity = normalizeSensitivity(draft.path("sensitivity").asText("INTERNAL"));
            String contextKey = textOrNull(draft, "contextKey");
            Double confidence = draft.has("confidence") && !draft.get("confidence").isNull()
                    ? draft.get("confidence").asDouble() : null;
            String name = textOrNull(draft, "name");
            String explanation = textOrNull(draft, "explanation");
            String dataNote = textOrNull(draft, "dataNote");

            String validationError = validateDraft(matchType, pattern, keywords, sensitivity);

            return RuleChatResponse.builder()
                    .success(true).conversationComplete(true).source("LLM_GENERATED")
                    .suggestedName(name).matchType(matchType).pattern(pattern).keywords(keywords)
                    .dataCategories(categories).sensitivity(sensitivity).contextKey(contextKey)
                    .confidence(confidence).explanation(explanation).dataNote(dataNote)
                    .validationError(validationError)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse AI rule draft: {}", e.getMessage());
            return RuleChatResponse.error("Could not read the AI draft. Please try again.");
        }
    }

    /** Server-side sanity check on the draft — the FE shows this as a warning; the admin still reviews before save. */
    private String validateDraft(String matchType, String pattern, List<String> keywords, String sensitivity) {
        if (!Arrays.asList(Sensitivity.RANKS).contains(sensitivity)) {
            return "Sensitivity must be PUBLIC, INTERNAL, CONFIDENTIAL, or RESTRICTED.";
        }
        if ("KEYWORDS".equals(matchType)) {
            if (keywords == null || keywords.isEmpty()) {
                return "A keyword rule needs at least one keyword.";
            }
        } else {
            if (pattern == null || pattern.isBlank()) {
                return "A regex rule needs a pattern.";
            }
            try {
                Pattern.compile(pattern);
            } catch (Exception e) {
                return "The drafted regex is invalid: " + e.getMessage();
            }
        }
        return null;
    }

    // ── System prompt = the rule model + this tenant's live detector/rule/data context ──

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder(STATIC_SCHEMA);
        try {
            sb.append("\n\n## Built-in detectors already active (do NOT recreate these)\n");
            for (DetectorInfo d : ruleService.detectors()) {
                sb.append("- `").append(d.matcher()).append("` — ").append(d.description())
                        .append(" (").append(d.category()).append(", ").append(d.defaultSensitivity()).append(")\n");
            }
            List<DataTagRuleDto> existing = ruleService.list().stream()
                    .filter(r -> "CUSTOM".equals(r.ruleType()))
                    .toList();
            if (!existing.isEmpty()) {
                sb.append("\n## Existing custom rules for this tenant (avoid duplicates)\n");
                for (DataTagRuleDto r : existing) {
                    sb.append("- ").append(r.name())
                            .append(" (").append(r.matchType() == null ? "REGEX" : r.matchType()).append(")\n");
                }
            }
        } catch (Exception e) {
            log.debug("Could not append rule config to assistant prompt: {}", e.getMessage());
        }
        sb.append(buildLiveDataSection());
        return sb.toString();
    }

    /** The tenant's ACTUAL data landscape — what grounds suggestions/drafts in reality instead of generic invention. */
    private String buildLiveDataSection() {
        String tenant = TenantContext.get();
        StringBuilder sb = new StringBuilder(
                "\n\n## What THIS tenant's data actually looks like — ground everything in this; do NOT invent generic "
                + "examples (no \"ACME\", \"Globex\", etc.) unless the admin names them.\n");
        try {
            List<String> cats = classificationRepo.distinctCategories(tenant);
            List<String> producers = classificationRepo.distinctProducers(tenant);
            sb.append("Categories already classified here: ")
                    .append(cats.isEmpty() ? "(none yet)" : String.join(", ", cats)).append("\n");
            sb.append("Producers (servers/agents) whose responses flow through: ")
                    .append(producers.isEmpty() ? "(none yet)" : String.join(", ", producers)).append("\n");

            List<String> sensitive = new ArrayList<>();
            for (Object[] r : classificationRepo.capabilityProfileRows(tenant)) {
                String sensitivity = str(r[4]);
                if (sensitivity != null && Sensitivity.rankOf(sensitivity) > 0) {
                    sensitive.add("- " + str(r[0]) + " " + str(r[1]) + " (" + str(r[2]) + ") → " + sensitivity
                            + " ×" + str(r[5]));
                }
                if (sensitive.size() >= 15) {
                    break;
                }
            }
            if (!sensitive.isEmpty()) {
                sb.append("Where sensitive data has actually appeared (producer capability (type) → sensitivity):\n");
                sensitive.forEach(s -> sb.append(s).append("\n"));
            }
            if (cats.isEmpty() && producers.isEmpty()) {
                sb.append("This tenant has no classified data yet — keep suggestions general and sensible.\n");
            } else {
                sb.append("When the admin is vague, PROPOSE rules grounded in the above (reference real producers/"
                        + "categories); ask a follow-up if you still need specifics.\n");
            }
        } catch (Exception e) {
            log.debug("Could not build live-data section: {}", e.getMessage());
        }
        return sb.toString();
    }

    private static final String STATIC_SCHEMA = """
            You are a data-classification rule assistant for the WhiteSwan gateway's egress post-processor.
            An admin describes, in plain English, what sensitive data they want flagged in the responses that agents
            and tools return. You draft ONE custom detection rule from their description.

            ## A rule has these fields
            - matchType: "REGEX" (a regex pattern) OR "KEYWORDS" (a list of exact words/names).
              Prefer KEYWORDS whenever the admin is really just listing specific words, names, vendors, or codenames —
              it is safer and clearer for non-technical admins. Use REGEX for shapes/patterns (ids like ACME-1234,
              emails, order numbers, structured tokens).
            - pattern: the regex (REGEX only).
            - keywords: array of exact words (KEYWORDS only). Matched whole-word and case-insensitively.
            - categories: one or more short UPPER_SNAKE labels the match is tagged with (e.g. ["PROJECT"],
              ["VENDOR","LEGAL"]). Reuse the tenant's existing categories when they fit.
            - sensitivity: admins speak in the UI's FRIENDLY labels — None, Low, Medium, High — so ACCEPT those and
              apply this mapping: None→PUBLIC, Low→INTERNAL, Medium→CONFIDENTIAL, High→RESTRICTED. In the JSON
              "sensitivity" field output the ENUM (PUBLIC / INTERNAL / CONFIDENTIAL / RESTRICTED); in your questions
              and explanations use the FRIENDLY words. If you ask the admin to pick a sensitivity, offer None / Low /
              Medium / High — never the internal enum. "High" IS valid (it means RESTRICTED); never tell the admin
              their friendly label is invalid.
            - contextKey (optional, REGEX only): one of ssn|card|secret|phone|iban to boost confidence near that word.
            - confidence (optional): a number 0..1.

            ## Do not duplicate built-ins
            The built-in detectors listed below already cover credit cards, SSNs, IBANs, emails, IP addresses, JWTs,
            AWS/Google/GitHub/Slack/Stripe keys, PEM keys/certs, and high-entropy secrets. If the admin asks for
            something a built-in already handles, DO NOT draft a rule — instead reply with a short follow-up that says
            it's already covered by a built-in and asks whether they want something more specific.

            ## Response format — STRICT
            When you have enough to draft a rule, reply with ONLY a fenced ```json block and nothing else:
            ```json
            {
              "name": "short-kebab-name",
              "matchType": "REGEX",
              "pattern": "ACME-\\\\d{4}",
              "keywords": null,
              "categories": ["PROJECT"],
              "sensitivity": "INTERNAL",
              "contextKey": null,
              "confidence": null,
              "explanation": "one plain sentence an admin can understand",
              "dataNote": null
            }
            ```
            For a keyword rule set matchType "KEYWORDS", pattern null, and keywords to the array of words.

            ## Proactive heads-up (a note, NEVER a refusal)
            If the admin's rule references a producer, category, vendor, codename, or specific term that is NOT present
            in "What THIS tenant's data actually looks like" (below), STILL draft the rule — but set "dataNote" to a
            short, friendly heads-up, e.g. "No ACME data seen in your tenant yet — creating this proactively." When the
            reference does match the tenant's real data, leave dataNote null. dataNote is a courtesy note; it must never
            stop you from drafting.

            ## When to ask instead of guess
            If the request is ambiguous or missing something you need (which category? how sensitive? which exact
            words?), DO NOT draft. Reply with ONE short, plain-English follow-up question and NO json block. Never
            guess at sensitivity or categories the admin didn't imply.
            """;

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private static String extractJsonBlock(String text) {
        int start = text.indexOf("```json");
        if (start == -1) {
            start = text.indexOf("```");
        }
        if (start == -1) {
            // A bare JSON array/object with no fence (e.g. suggestions) — return it as-is.
            String trimmed = text.trim();
            return (trimmed.startsWith("[") || trimmed.startsWith("{")) ? trimmed : null;
        }
        start = text.indexOf('\n', start) + 1;
        int end = text.indexOf("```", start);
        if (end == -1) {
            return text.substring(start).trim();
        }
        return text.substring(start, end).trim();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText("").trim();
        return s.isBlank() ? null : s;
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        v.forEach(n -> {
            String s = n.asText("").trim();
            if (!s.isBlank()) {
                out.add(s);
            }
        });
        return out.isEmpty() ? null : out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    /** Map the admin's friendly sensitivity labels to the engine enum (High→RESTRICTED, …); pass enums through. */
    private static String normalizeSensitivity(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "HIGH" -> "RESTRICTED";
            case "MEDIUM" -> "CONFIDENTIAL";
            case "LOW" -> "INTERNAL";
            case "NONE" -> "PUBLIC";
            default -> s.trim().toUpperCase(Locale.ROOT);
        };
    }
}
