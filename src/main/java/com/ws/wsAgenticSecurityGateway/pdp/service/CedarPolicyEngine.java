package com.ws.wsAgenticSecurityGateway.pdp.service;

import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationRequest;
import com.ws.wsAgenticSecurityGateway.pdp.dto.PolicyEvaluationResult;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure-Java Cedar Policy Decision Point engine.
 *
 * <p>Evaluates Cedar policies written in standard Cedar syntax using a lightweight
 * Java parser. No native libraries (JNI/Rust) required — runs on any platform.
 *
 * <h3>Fully Dynamic ABAC Engine</h3>
 * <p>All attributes are stored in dynamic maps — no hardcoded fields.
 * Any attribute name works with any operator, for any entity source.
 * Add custom attributes via {@link PolicyEvaluationRequest#getCustomAttributes()}
 * without code changes.
 *
 * <h3>Supported Cedar Syntax</h3>
 * <ul>
 *   <li>{@code permit(...)} and {@code forbid(...)} effects</li>
 *   <li>{@code @id("policy-id")} annotations</li>
 *   <li><b>Principal constraints:</b>
 *       {@code principal is Agent} (type),
 *       {@code principal == Agent::"name"} (exact entity)</li>
 *   <li><b>Action constraints:</b>
 *       {@code action == Action::"name"} (single),
 *       {@code action in [Action::"a", Action::"b"]} (set)</li>
 *   <li><b>Resource constraints:</b>
 *       {@code resource is Tool} (type),
 *       {@code resource == Tool::"name"} (exact entity),
 *       {@code resource} (any, no constraint)</li>
 *   <li><b>Guard clauses:</b>
 *       {@code when { condition }} and {@code unless { condition }}</li>
 *   <li><b>Hierarchy membership:</b>
 *       {@code principal in AgentGroup::"status"},
 *       {@code resource in Server::"name"}</li>
 *   <li><b>Attribute operators (work with context.X, principal.X, resource.X):</b>
 *       {@code ==} equality, {@code !=} inequality,
 *       {@code >}, {@code <}, {@code >=}, {@code <=} numeric comparison,
 *       {@code like} wildcard matching ({@code *} = any),
 *       {@code .contains()} substring check</li>
 *   <li><b>Combined conditions:</b>
 *       {@code &&} (AND) within when/unless blocks</li>
 *   <li><b>Extensible attributes:</b> Any attribute name works — add custom
 *       attributes via {@link PolicyEvaluationRequest#getCustomAttributes()}</li>
 * </ul>
 *
 * <h3>Built-in Attributes</h3>
 * <table>
 *   <tr><th>Source</th><th>Attribute</th><th>Type</th></tr>
 *   <tr><td>principal</td><td>name, version, approvalStatus, sessionId</td><td>String</td></tr>
 *   <tr><td>resource</td><td>name, serverName, originalName, type</td><td>String</td></tr>
 *   <tr><td>context</td><td>hour (long), dayOfWeek, businessHours (bool),
 *       sourceIp, serverName, resourceName, correlationId, argumentsFlat</td><td>mixed</td></tr>
 * </table>
 *
 * <h3>Evaluation Semantics (Cedar-compliant)</h3>
 * <ol>
 *   <li>If ANY {@code forbid} policy matches → DENY (short-circuit, forbid overrides permit)</li>
 *   <li>If at least one {@code permit} policy matches → ALLOW</li>
 *   <li>If no policy matches → DENY (default-deny)</li>
 * </ol>
 *
 * <h3>Thread Safety</h3>
 * The engine is thread-safe. Parsed policies are replaced atomically behind a ReadWriteLock.
 */
@Component
@Slf4j
public class CedarPolicyEngine {

    // ════════════════════════════════════════════════════════════════════
    //  INTERNAL POLICY REPRESENTATION
    // ════════════════════════════════════════════════════════════════════

    /**
     * A parsed Cedar policy — extracted from Cedar syntax into a structured form.
     */
    private static class ParsedPolicy {
        String id;                  // @id("...") annotation
        String name;                // DB policy name (for logging)
        String effect;              // "permit" or "forbid"

        // ── Head clause: principal ─────────────────────────────────
        String principalType;       // "Agent" (from `principal is Agent`)
        String principalEntityId;   // "claude-desktop" (from `principal == Agent::"claude-desktop"`)

        // ── Head clause: action ────────────────────────────────────
        String actionId;            // e.g. "toolCall" (from `action == Action::"toolCall"`)
        List<String> actionIds;     // (from `action in [Action::"a", ...]`)

        // ── Head clause: resource ──────────────────────────────────
        String resourceType;        // "Tool", "Prompt", "Resource" (from `resource is Tool`)
        String resourceEntityId;    // "github_create_issue" (from `resource == Tool::"github_create_issue"`)

        // ── Guard clauses (when/unless) ────────────────────────────
        List<Condition> whenConditions = new ArrayList<>();
        List<Condition> unlessConditions = new ArrayList<>();
    }

    /**
     * A single condition within a when/unless block.
     * Uses Source + Operator for fully generic attribute evaluation.
     * Combined conditions (&&) produce multiple Condition objects — all must be satisfied (AND semantics).
     */
    private static class Condition {
        /** Which entity's attribute map to look up. */
        enum Source {
            CONTEXT,        // context.X → contextAttrs
            PRINCIPAL,      // principal.X → principalAttrs
            RESOURCE        // resource.X → resourceAttrs
        }

        /** The comparison operation to perform. */
        enum Operator {
            EQ,             // == (type-agnostic via String.valueOf)
            NEQ,            // != (type-agnostic via String.valueOf)
            GT,             // > (numeric)
            LT,             // < (numeric)
            GTE,            // >= (numeric)
            LTE,            // <= (numeric)
            LIKE,           // like "*pattern*" (wildcard)
            CONTAINS,       // .contains("value") (substring)
            IN_GROUP,       // principal in AgentGroup::"X" (hierarchy — structural)
            IN_SERVER,      // resource in Server::"X" (hierarchy — structural)
            EQ_ENTITY       // principal == Agent::"X" or resource == Tool::"X" (structural)
        }

        Source source;          // which attribute map
        Operator operator;      // comparison type
        String field;           // attribute name (or entity id for structural ops)
        String value;           // comparison value (null for IN_GROUP/IN_SERVER)
        String entityType;      // for EQ_ENTITY on resource: "Tool", "Prompt", "Resource"
    }

    // ════════════════════════════════════════════════════════════════════
    //  REGEX PATTERNS for Cedar syntax parsing
    // ════════════════════════════════════════════════════════════════════

    // ── Structural ──────────────────────────────────────────────────────

    /** @id("policy-id") */
    private static final Pattern ID_ANNOTATION = Pattern.compile("@id\\(\"([^\"]+)\"\\)");

    /** when { ... } */
    private static final Pattern WHEN_BLOCK = Pattern.compile("(?s)when\\s*\\{(.+?)\\}");

    /** unless { ... } */
    private static final Pattern UNLESS_BLOCK = Pattern.compile("(?s)unless\\s*\\{(.+?)\\}");

    // ── Head clause: principal ──────────────────────────────────────────

    /** principal is Type */
    private static final Pattern PRINCIPAL_IS = Pattern.compile("principal\\s+is\\s+(\\w+)");

    /** principal == Agent::"name" */
    private static final Pattern PRINCIPAL_EQ = Pattern.compile("principal\\s*==\\s*Agent::\"([^\"]+)\"");

    // ── Head clause: action ────────────────────────────────────────────

    /** action == Action::"name" */
    private static final Pattern ACTION_EQ = Pattern.compile("action\\s*==\\s*Action::\"([^\"]+)\"");

    /** action in [Action::"a", Action::"b", ...] */
    private static final Pattern ACTION_IN = Pattern.compile("action\\s+in\\s+\\[([^\\]]+)\\]");
    private static final Pattern ACTION_ITEM = Pattern.compile("Action::\"([^\"]+)\"");

    // ── Head clause: resource ──────────────────────────────────────────

    /** resource is Type */
    private static final Pattern RESOURCE_IS = Pattern.compile("resource\\s+is\\s+(\\w+)");

    /** resource == Tool::"name" / Prompt::"name" / Resource::"name" */
    private static final Pattern RESOURCE_EQ = Pattern.compile("resource\\s*==\\s*(Tool|Prompt|Resource)::\"([^\"]+)\"");

    // ── Condition: structural (Cedar-specific hierarchy/entity checks) ──

    /** principal in AgentGroup::"X" */
    private static final Pattern PRINCIPAL_IN_GROUP = Pattern.compile("principal\\s+in\\s+AgentGroup::\"([^\"]+)\"");

    /** resource in Server::"X" */
    private static final Pattern RESOURCE_IN_SERVER = Pattern.compile("resource\\s+in\\s+Server::\"([^\"]+)\"");

    /** principal == Agent::"X" (in condition blocks) */
    private static final Pattern COND_PRINCIPAL_EQ = Pattern.compile("principal\\s*==\\s*Agent::\"([^\"]+)\"");

    /** resource == Tool/Prompt/Resource::"X" (in condition blocks) */
    private static final Pattern COND_RESOURCE_EQ = Pattern.compile("resource\\s*==\\s*(Tool|Prompt|Resource)::\"([^\"]+)\"");

    // ── Condition: generic attribute patterns (context|principal|resource) ──
    //    These work with ANY attribute name from ANY entity source.
    //    Order matters: check more specific operators first.

    /** source.field >= N */
    private static final Pattern ATTR_GTE = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*>=\\s*(-?\\d+)");

    /** source.field <= N */
    private static final Pattern ATTR_LTE = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*<=\\s*(-?\\d+)");

    /** source.field > N (negative lookahead excludes >=) */
    private static final Pattern ATTR_GT = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*>(?!=)\\s*(-?\\d+)");

    /** source.field < N (negative lookahead excludes <=) */
    private static final Pattern ATTR_LT = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*<(?!=)\\s*(-?\\d+)");

    /** source.field like "*pattern*" */
    private static final Pattern ATTR_LIKE = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s+like\\s+\"([^\"]+)\"");

    /** source.field.contains("value") */
    private static final Pattern ATTR_CONTAINS = Pattern.compile("(context|principal|resource)\\.(\\w+)\\.contains\\(\"([^\"]+)\"\\)");

    /** source.field != true/false */
    private static final Pattern ATTR_NEQ_BOOL = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*!=\\s*(true|false)");

    /** source.field != "string" */
    private static final Pattern ATTR_NEQ_STRING = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*!=\\s*\"([^\"]+)\"");

    /** source.field != 123 */
    private static final Pattern ATTR_NEQ_LONG = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*!=\\s*(-?\\d+)");

    /** source.field == true/false */
    private static final Pattern ATTR_EQ_BOOL = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*==\\s*(true|false)");

    /** source.field == "string" */
    private static final Pattern ATTR_EQ_STRING = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*==\\s*\"([^\"]+)\"");

    /** source.field == 123 */
    private static final Pattern ATTR_EQ_LONG = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*==\\s*(-?\\d+)");

    // ════════════════════════════════════════════════════════════════════
    //  STATE
    // ════════════════════════════════════════════════════════════════════

    /** Current active parsed policies — replaced atomically on reload. */
    private volatile List<ParsedPolicy> currentPolicies = Collections.emptyList();

    /** Lock for atomic policy reload (write) vs. concurrent evaluation (read). */
    private final ReadWriteLock policyLock = new ReentrantReadWriteLock();

    /** Whether any policies are currently loaded. */
    private volatile boolean policiesLoaded = false;

    public CedarPolicyEngine() {
        log.info("🏛️  Cedar Policy Engine initialized (pure-Java evaluator — dynamic ABAC)");
    }

    // ════════════════════════════════════════════════════════════════════
    //  POLICY MANAGEMENT
    // ════════════════════════════════════════════════════════════════════

    /**
     * Reload all active policies from the provided list of entities.
     * Parses them into an internal representation for fast evaluation.
     *
     * @param policies list of enabled policy entities from DB
     * @return number of policies loaded, or -1 on parse error
     */
    public int reloadPolicies(List<GatewayPolicyEntity> policies) {
        if (policies == null || policies.isEmpty()) {
            policyLock.writeLock().lock();
            try {
                currentPolicies = Collections.emptyList();
                policiesLoaded = false;
                log.info("🏛️  Cedar: No policies to load — all requests will be DENIED (default-deny, no permits)");
                return 0;
            } finally {
                policyLock.writeLock().unlock();
            }
        }

        try {
            List<ParsedPolicy> parsed = new ArrayList<>();
            for (GatewayPolicyEntity entity : policies) {
                ParsedPolicy pp = parsePolicy(entity.getPolicyText(), entity.getPolicyName());
                if (pp != null) {
                    parsed.add(pp);
                    log.info("🏛️  Cedar PARSED: name='{}', id='{}', effect={}, principalType={}, principalId='{}', actionId='{}', resourceType={}, resourceId='{}', whenConds={}, unlessConds={}",
                            pp.name, pp.id, pp.effect, pp.principalType, pp.principalEntityId,
                            pp.actionId, pp.resourceType, pp.resourceEntityId,
                            pp.whenConditions.size(), pp.unlessConditions.size());
                } else {
                    log.warn("🏛️  Cedar: Skipped unparseable policy: {} | text='{}'",
                            entity.getPolicyName(), entity.getPolicyText());
                }
            }

            policyLock.writeLock().lock();
            try {
                currentPolicies = Collections.unmodifiableList(parsed);
                policiesLoaded = !parsed.isEmpty();
            } finally {
                policyLock.writeLock().unlock();
            }

            log.info("🏛️  Cedar: Loaded {} policies successfully (parsed {} of {})",
                    parsed.size(), parsed.size(), policies.size());
            return parsed.size();

        } catch (Exception e) {
            log.error("🏛️  Cedar: Failed to parse policies: {}", e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Validate a single Cedar policy text (syntax check only).
     *
     * @param policyText the Cedar policy code to validate
     * @return null if valid, error message if invalid
     */
    public String validatePolicy(String policyText) {
        if (policyText == null || policyText.isBlank()) {
            return "Policy text is empty";
        }
        try {
            ParsedPolicy pp = parsePolicy(policyText, "validation");
            if (pp == null) {
                return "Could not parse policy: expected 'permit(...)' or 'forbid(...)' statement";
            }
            if (pp.effect == null) {
                return "Missing effect: policy must start with 'permit' or 'forbid'";
            }
            return null; // valid
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    public boolean hasPolicies() {
        return policiesLoaded;
    }

    // ════════════════════════════════════════════════════════════════════
    //  EVALUATION
    // ════════════════════════════════════════════════════════════════════

    /**
     * Evaluate a policy evaluation request against all loaded Cedar policies.
     *
     * <p>Cedar evaluation semantics with forbid short-circuit:
     * <ol>
     *   <li>If ANY forbid policy matches → DENY immediately (short-circuit)</li>
     *   <li>If at least one permit policy matches → ALLOW</li>
     *   <li>If no policy matches → DENY (default-deny)</li>
     * </ol>
     *
     * <p>If no policies are loaded, returns ALLOW (no-policy mode).
     *
     * @param request the structured evaluation request
     * @return evaluation result with decision, matched policies, and timing
     */
    public PolicyEvaluationResult evaluate(PolicyEvaluationRequest request) {
        long startTime = System.currentTimeMillis();

        // ── No-policy mode: deny everything (no permits exist) ─────────
        if (!policiesLoaded || currentPolicies.isEmpty()) {
            log.info("🏛️  Cedar: agent={}, action={}, resource={} → DENY (no policies configured)",
                    request.getAgentName(), request.getAction(), request.getResourceName());
            long duration = System.currentTimeMillis() - startTime;
            return PolicyEvaluationResult.noPolicies(duration);
        }

        policyLock.readLock().lock();
        try {
            // Build evaluation context from request
            EvalContext ctx = buildEvalContext(request);

            log.info("🏛️  Cedar EVAL: principalType='{}', principalId='{}', action='{}', resourceType='{}', resourceId='{}', policiesCount={}",
                    ctx.principalType, ctx.principalId, ctx.action, ctx.resourceType, ctx.resourceId, currentPolicies.size());

            // Track matching policies
            Set<String> matchedPermitPolicies = new LinkedHashSet<>();
            Set<String> matchedForbidPolicies = new LinkedHashSet<>();

            for (ParsedPolicy policy : currentPolicies) {
                log.info("🏛️  Cedar MATCH CHECK: policy='{}' effect={} | principal: policy='{}' vs ctx='{}' ({}), action: policy='{}' vs ctx='{}' ({}), resource: policy='{}'/'{}'  vs ctx='{}'/'{}'  ({})",
                        policy.id != null ? policy.id : policy.name, policy.effect,
                        policy.principalEntityId, ctx.principalId,
                        java.util.Objects.equals(policy.principalEntityId, ctx.principalId) || policy.principalEntityId == null,
                        policy.actionId, ctx.action,
                        java.util.Objects.equals(policy.actionId, ctx.action) || policy.actionId == null,
                        policy.resourceType, policy.resourceEntityId, ctx.resourceType, ctx.resourceId,
                        (java.util.Objects.equals(policy.resourceType, ctx.resourceType) || policy.resourceType == null)
                                && (java.util.Objects.equals(policy.resourceEntityId, ctx.resourceId) || policy.resourceEntityId == null));
                if (matches(policy, ctx)) {
                    String policyRef = policy.id != null ? policy.id : policy.name;

                    if ("forbid".equals(policy.effect)) {
                        matchedForbidPolicies.add(policyRef);
                        // ── Forbid short-circuit: result is guaranteed DENY ──
                        long duration = System.currentTimeMillis() - startTime;
                        log.info("🏛️  Cedar: agent={}, action={}, resource={} → DENY ({}ms, forbid={}, short-circuit)",
                                request.getAgentName(), request.getAction(),
                                request.getResourceName(), duration, matchedForbidPolicies);
                        return PolicyEvaluationResult.deny(matchedForbidPolicies, duration);
                    } else {
                        matchedPermitPolicies.add(policyRef);
                    }
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            // 2. If any permit matches → ALLOW
            if (!matchedPermitPolicies.isEmpty()) {
                log.info("🏛️  Cedar: agent={}, action={}, resource={} → ALLOW ({}ms, permit={})",
                        request.getAgentName(), request.getAction(),
                        request.getResourceName(), duration, matchedPermitPolicies);
                return PolicyEvaluationResult.allow(matchedPermitPolicies, duration);
            }

            // 3. No matches → default DENY
            // ── Diagnostic: detect forbid-unless anti-pattern ──────────
            String diagnostic = null;
            for (ParsedPolicy policy : currentPolicies) {
                if ("forbid".equals(policy.effect)
                        && !policy.unlessConditions.isEmpty()
                        && matchesIgnoringUnless(policy, ctx)) {
                    String policyRef = policy.id != null ? policy.id : policy.name;
                    diagnostic = String.format(
                            "Policy '%s' is a forbid-with-unless that exempted this request, "
                                    + "but no permit policy exists to actually allow it. The unless clause "
                                    + "only removes a forbid — it does not grant access. Consider adding a "
                                    + "permit for the allowed resource instead.",
                            policyRef);
                    log.warn("🏛️  Cedar DIAGNOSTIC: {}", diagnostic);
                    break;
                }
            }

            log.info("🏛️  Cedar: agent={}, action={}, resource={} → DENY ({}ms, no matching policy)",
                    request.getAgentName(), request.getAction(),
                    request.getResourceName(), duration);

            if (diagnostic != null) {
                return PolicyEvaluationResult.builder()
                        .decision("DENY")
                        .matchedPolicies(Set.of())
                        .reason("No matching permit policy (default deny)")
                        .evaluationDurationMs(duration)
                        .diagnostics(diagnostic)
                        .build();
            }
            return PolicyEvaluationResult.deny(Set.of(), duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("🏛️  Cedar evaluation error: {}", e.getMessage(), e);

            // On evaluation error, fail-open (ALLOW) — don't block the gateway
            return PolicyEvaluationResult.builder()
                    .decision("ALLOW")
                    .matchedPolicies(Set.of())
                    .reason("Policy evaluation error (fail-open): " + e.getMessage())
                    .evaluationDurationMs(duration)
                    .hasErrors(true)
                    .diagnostics(e.getMessage())
                    .build();

        } finally {
            policyLock.readLock().unlock();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  CEDAR POLICY PARSING
    // ════════════════════════════════════════════════════════════════════

    /**
     * Parse a single Cedar policy text into our internal representation.
     * Returns null if the policy text cannot be parsed.
     */
    private ParsedPolicy parsePolicy(String policyText, String name) {
        if (policyText == null || policyText.isBlank()) return null;

        // Strip comments (// ...) — keep the text clean for regex
        String clean = policyText.replaceAll("//[^\n]*", "").trim();

        ParsedPolicy pp = new ParsedPolicy();
        pp.name = name;

        // ── @id annotation ───────────────────────────────────────────
        Matcher idMatch = ID_ANNOTATION.matcher(clean);
        if (idMatch.find()) {
            pp.id = idMatch.group(1);
        }

        // ── Effect: permit or forbid ─────────────────────────────────
        String lowerClean = clean.toLowerCase();
        int permitIdx = lowerClean.indexOf("permit");
        int forbidIdx = lowerClean.indexOf("forbid");

        if (permitIdx < 0 && forbidIdx < 0) return null;

        if (forbidIdx >= 0 && (permitIdx < 0 || forbidIdx < permitIdx)) {
            pp.effect = "forbid";
        } else {
            pp.effect = "permit";
        }

        // ── Head clause: extract the (...) content ───────────────────
        int effectStart = "forbid".equals(pp.effect) ? forbidIdx : permitIdx;
        int parenStart = clean.indexOf('(', effectStart);
        if (parenStart < 0) return null;

        int parenEnd = findMatchingParen(clean, parenStart);
        if (parenEnd < 0) return null;

        String headClause = clean.substring(parenStart + 1, parenEnd);

        // ── Parse head clause: principal ──────────────────────────────
        Matcher principalEqMatch = PRINCIPAL_EQ.matcher(headClause);
        if (principalEqMatch.find()) {
            pp.principalType = "Agent";
            pp.principalEntityId = principalEqMatch.group(1);
        } else {
            Matcher principalIsMatch = PRINCIPAL_IS.matcher(headClause);
            if (principalIsMatch.find()) {
                pp.principalType = principalIsMatch.group(1);
            }
        }

        // ── Parse head clause: action ────────────────────────────────
        Matcher actionEqMatch = ACTION_EQ.matcher(headClause);
        if (actionEqMatch.find()) {
            pp.actionId = actionEqMatch.group(1);
        }

        Matcher actionInMatch = ACTION_IN.matcher(headClause);
        if (actionInMatch.find()) {
            pp.actionIds = new ArrayList<>();
            Matcher itemMatch = ACTION_ITEM.matcher(actionInMatch.group(1));
            while (itemMatch.find()) {
                pp.actionIds.add(itemMatch.group(1));
            }
        }

        // ── Parse head clause: resource ──────────────────────────────
        Matcher resourceEqMatch = RESOURCE_EQ.matcher(headClause);
        if (resourceEqMatch.find()) {
            pp.resourceType = resourceEqMatch.group(1);
            pp.resourceEntityId = resourceEqMatch.group(2);
        } else {
            Matcher resourceIsMatch = RESOURCE_IS.matcher(headClause);
            if (resourceIsMatch.find()) {
                pp.resourceType = resourceIsMatch.group(1);
            }
        }

        // ── when/unless clauses ──────────────────────────────────────
        String afterHead = clean.substring(parenEnd + 1);

        Matcher whenMatch = WHEN_BLOCK.matcher(afterHead);
        if (whenMatch.find()) {
            pp.whenConditions = parseConditions(whenMatch.group(1));
        }

        Matcher unlessMatch = UNLESS_BLOCK.matcher(afterHead);
        if (unlessMatch.find()) {
            pp.unlessConditions = parseConditions(unlessMatch.group(1));
        }

        return pp;
    }

    /**
     * Parse conditions from a when/unless block body.
     * Splits on {@code &&} and parses each fragment independently (AND semantics).
     */
    private List<Condition> parseConditions(String body) {
        List<Condition> conditions = new ArrayList<>();
        String[] fragments = body.split("&&");

        for (String fragment : fragments) {
            String frag = fragment.trim();
            if (frag.isEmpty()) continue;

            Condition c = parseSingleCondition(frag);
            if (c != null) {
                conditions.add(c);
            } else {
                log.debug("🏛️  Cedar: Unrecognized condition fragment: {}", frag);
            }
        }

        return conditions;
    }

    /**
     * Parse a single condition fragment into a Condition object.
     *
     * <p>Checks structural patterns first (hierarchy, entity equality),
     * then generic attribute patterns (any source + any operator).
     */
    private Condition parseSingleCondition(String fragment) {
        Matcher m;

        // ════════════════════════════════════════════════════════════
        // 1. STRUCTURAL PATTERNS (Cedar-specific, not attribute-based)
        // ════════════════════════════════════════════════════════════

        m = PRINCIPAL_IN_GROUP.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.source = Condition.Source.PRINCIPAL;
            c.operator = Condition.Operator.IN_GROUP;
            c.field = m.group(1);
            return c;
        }

        m = RESOURCE_IN_SERVER.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.source = Condition.Source.RESOURCE;
            c.operator = Condition.Operator.IN_SERVER;
            c.field = m.group(1);
            return c;
        }

        m = COND_PRINCIPAL_EQ.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.source = Condition.Source.PRINCIPAL;
            c.operator = Condition.Operator.EQ_ENTITY;
            c.field = m.group(1);
            return c;
        }

        m = COND_RESOURCE_EQ.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.source = Condition.Source.RESOURCE;
            c.operator = Condition.Operator.EQ_ENTITY;
            c.entityType = m.group(1);
            c.field = m.group(2);
            return c;
        }

        // ════════════════════════════════════════════════════════════
        // 2. GENERIC ATTRIBUTE PATTERNS (any source, any operator)
        //    Order: most specific operators first to avoid false matches
        // ════════════════════════════════════════════════════════════

        // ── like (before other operators) ────────────────────────────
        m = ATTR_LIKE.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.LIKE);
        }

        // ── .contains() ─────────────────────────────────────────────
        m = ATTR_CONTAINS.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.CONTAINS);
        }

        // ── >= and <= (before > and <) ──────────────────────────────
        m = ATTR_GTE.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.GTE);
        }

        m = ATTR_LTE.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.LTE);
        }

        // ── > and < ─────────────────────────────────────────────────
        m = ATTR_GT.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.GT);
        }

        m = ATTR_LT.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.LT);
        }

        // ── != (before ==) ──────────────────────────────────────────
        m = ATTR_NEQ_BOOL.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.NEQ);
        }

        m = ATTR_NEQ_STRING.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.NEQ);
        }

        m = ATTR_NEQ_LONG.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.NEQ);
        }

        // ── == ──────────────────────────────────────────────────────
        m = ATTR_EQ_BOOL.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.EQ);
        }

        m = ATTR_EQ_STRING.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.EQ);
        }

        m = ATTR_EQ_LONG.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.EQ);
        }

        return null; // unrecognized condition
    }

    /**
     * Build an attribute Condition from a generic pattern match.
     */
    private Condition buildAttrCondition(String sourceStr, String field, String value, Condition.Operator operator) {
        Condition c = new Condition();
        c.source = parseSource(sourceStr);
        c.operator = operator;
        c.field = field;
        c.value = value;
        return c;
    }

    /**
     * Parse source string ("context", "principal", "resource") to enum.
     */
    private Condition.Source parseSource(String source) {
        return switch (source.toLowerCase()) {
            case "principal" -> Condition.Source.PRINCIPAL;
            case "resource" -> Condition.Source.RESOURCE;
            default -> Condition.Source.CONTEXT;
        };
    }

    /**
     * Find the matching closing parenthesis for the opening paren at position pos.
     */
    private int findMatchingParen(String s, int pos) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = pos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
                continue;
            }
            if (inQuote) continue;
            if (c == '(') depth++;
            if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ════════════════════════════════════════════════════════════════════
    //  EVALUATION CONTEXT (dynamic attribute maps)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Evaluation context — all request data stored in dynamic attribute maps.
     *
     * <p>Structural fields ({@code principalType}, {@code principalId}, etc.) are
     * kept for head clause matching. All condition evaluation uses the maps.
     */
    private static class EvalContext {
        // ── Structural (for head clause matching only) ──────────────
        String principalType;       // "Agent"
        String principalId;         // agent name
        String action;              // "toolCall", "promptGet", "resourceRead"
        String resourceType;        // "Tool", "Prompt", "Resource"
        String resourceId;          // public namespaced name

        // ── Dynamic attribute maps (for condition evaluation) ───────
        Map<String, Object> principalAttrs = new HashMap<>();
        Map<String, Object> resourceAttrs = new HashMap<>();
        Map<String, Object> contextAttrs = new HashMap<>();
    }

    /**
     * Build evaluation context from a PolicyEvaluationRequest.
     *
     * <p>Populates dynamic attribute maps with all known attributes.
     * Custom attributes from the request are merged into contextAttrs.
     * Future attributes can be added without engine code changes.
     */
    private EvalContext buildEvalContext(PolicyEvaluationRequest request) {
        EvalContext ctx = new EvalContext();

        // ── Structural fields (head clause matching) ─────────────────
        ctx.principalType = "Agent";
        ctx.principalId = request.getAgentName();
        ctx.action = request.getAction();
        ctx.resourceType = request.getResourceType() != null ? request.getResourceType() : "Tool";
        ctx.resourceId = request.getResourceName();

        // ── Principal attributes ─────────────────────────────────────
        putIfNotNull(ctx.principalAttrs, "name", request.getAgentName());
        putIfNotNull(ctx.principalAttrs, "version", request.getAgentVersion());
        putIfNotNull(ctx.principalAttrs, "approvalStatus", request.getAgentApprovalStatus());
        putIfNotNull(ctx.principalAttrs, "sessionId", request.getAgentSessionId());

        // ── Resource attributes ──────────────────────────────────────
        putIfNotNull(ctx.resourceAttrs, "name", request.getResourceName());
        putIfNotNull(ctx.resourceAttrs, "serverName", request.getServerName());
        putIfNotNull(ctx.resourceAttrs, "originalName", request.getOriginalName());
        putIfNotNull(ctx.resourceAttrs, "type", request.getResourceType());

        // ── Context attributes: environment ──────────────────────────
        LocalDateTime now = LocalDateTime.now();
        ctx.contextAttrs.put("businessHours", isBusinessHours(now));
        ctx.contextAttrs.put("hour", (long) now.getHour());
        ctx.contextAttrs.put("minute", (long) now.getMinute());
        ctx.contextAttrs.put("dayOfWeek", now.getDayOfWeek().name());
        ctx.contextAttrs.put("month", now.getMonth().name());
        ctx.contextAttrs.put("year", (long) now.getYear());

        // ── Context attributes: request data ─────────────────────────
        putIfNotNull(ctx.contextAttrs, "sourceIp", request.getSourceIp());
        putIfNotNull(ctx.contextAttrs, "serverName", request.getServerName());
        putIfNotNull(ctx.contextAttrs, "resourceName", request.getResourceName());
        putIfNotNull(ctx.contextAttrs, "correlationId", request.getCorrelationId());
        ctx.contextAttrs.put("argumentsFlat", flattenArguments(request.getArguments()));

        // ── Custom attributes (extensibility point) ──────────────────
        if (request.getCustomAttributes() != null) {
            ctx.contextAttrs.putAll(request.getCustomAttributes());
        }

        return ctx;
    }

    /**
     * Flatten tool arguments map into a single searchable string.
     * Format: "key1=value1 key2=value2 ..."
     */
    private String flattenArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        return arguments.entrySet().stream()
                .map(e -> e.getKey() + "=" + (e.getValue() != null ? e.getValue().toString() : "null"))
                .collect(Collectors.joining(" "));
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  POLICY MATCHING
    // ════════════════════════════════════════════════════════════════════

    /**
     * Check if a parsed policy matches the given evaluation context.
     *
     * <p>A policy matches if ALL of these hold:
     * <ol>
     *   <li>Head clause matches (principal type/entity, action, resource type/entity)</li>
     *   <li>All {@code when} conditions evaluate to TRUE</li>
     *   <li>All {@code unless} conditions evaluate to FALSE</li>
     * </ol>
     */
    private boolean matches(ParsedPolicy policy, EvalContext ctx) {
        // ── Head clause: principal ───────────────────────────────────
        if (policy.principalType != null && !policy.principalType.equalsIgnoreCase(ctx.principalType)) {
            return false;
        }
        if (policy.principalEntityId != null && !policy.principalEntityId.equals(ctx.principalId)) {
            return false;
        }

        // ── Head clause: action ─────────────────────────────────────
        if (policy.actionId != null && !policy.actionId.equalsIgnoreCase(ctx.action)) {
            return false;
        }
        if (policy.actionIds != null && !policy.actionIds.isEmpty()
                && policy.actionIds.stream().noneMatch(a -> a.equalsIgnoreCase(ctx.action))) {
            return false;
        }

        // ── Head clause: resource ───────────────────────────────────
        if (policy.resourceType != null && !policy.resourceType.equalsIgnoreCase(ctx.resourceType)) {
            return false;
        }
        if (policy.resourceEntityId != null && !policy.resourceEntityId.equals(ctx.resourceId)) {
            return false;
        }

        // ── When: ALL must be TRUE ──────────────────────────────────
        for (Condition cond : policy.whenConditions) {
            if (!evaluateCondition(cond, ctx)) {
                return false;
            }
        }

        // ── Unless: if ANY is TRUE, policy does NOT apply ───────────
        for (Condition cond : policy.unlessConditions) {
            if (evaluateCondition(cond, ctx)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if a policy's head clause and when-conditions match, ignoring unless conditions.
     * Used for diagnostics to detect the "forbid-unless without permit" anti-pattern.
     */
    private boolean matchesIgnoringUnless(ParsedPolicy policy, EvalContext ctx) {
        if (policy.principalType != null && !policy.principalType.equalsIgnoreCase(ctx.principalType)) return false;
        if (policy.principalEntityId != null && !policy.principalEntityId.equals(ctx.principalId)) return false;
        if (policy.actionId != null && !policy.actionId.equalsIgnoreCase(ctx.action)) return false;
        if (policy.actionIds != null && !policy.actionIds.isEmpty()
                && policy.actionIds.stream().noneMatch(a -> a.equalsIgnoreCase(ctx.action))) return false;
        if (policy.resourceType != null && !policy.resourceType.equalsIgnoreCase(ctx.resourceType)) return false;
        if (policy.resourceEntityId != null && !policy.resourceEntityId.equals(ctx.resourceId)) return false;
        for (Condition cond : policy.whenConditions) {
            if (!evaluateCondition(cond, ctx)) return false;
        }
        // Deliberately skip unless conditions
        return true;
    }

    /**
     * Check for semantic warnings in a policy (valid syntax but likely incorrect intent).
     * Returns null if no warnings, or a warning message.
     */
    public String getSemanticWarnings(String policyText) {
        if (policyText == null || policyText.isBlank()) return null;

        ParsedPolicy pp = parsePolicy(policyText, "semantic-check");
        if (pp == null) return null;

        if ("forbid".equals(pp.effect) && !pp.unlessConditions.isEmpty()) {
            return "This is a forbid-with-unless policy. The 'unless' clause only exempts "
                    + "requests from being forbidden — it does NOT grant access. In the gateway's "
                    + "default-deny system, exempted requests with no matching permit policy will "
                    + "still be DENIED. If your intent is to allow only specific resources, use a "
                    + "'permit' policy instead.";
        }

        return null;
    }

    /**
     * Extract entity references from Cedar policy text for audit enrichment.
     *
     * <p>Parses the head clause and condition blocks to identify the agent,
     * capability, action, and server referenced in the policy.
     *
     * @return map with keys: agentName, capabilityName, capabilityType, actionName, serverName
     *         (values are null when not referenced)
     */
    public Map<String, String> extractPolicyReferences(String policyText) {
        Map<String, String> refs = new LinkedHashMap<>();
        if (policyText == null || policyText.isBlank()) return refs;

        String clean = policyText.replaceAll("//[^\n]*", "").trim();

        // Agent name from principal == Agent::"name"
        Matcher m = PRINCIPAL_EQ.matcher(clean);
        if (m.find()) {
            refs.put("agentName", m.group(1));
        }

        // Capability from resource == Tool/Prompt/Resource::"name"
        m = RESOURCE_EQ.matcher(clean);
        if (m.find()) {
            refs.put("capabilityType", m.group(1));
            refs.put("capabilityName", m.group(2));
        }

        // Action from action == Action::"name"
        m = ACTION_EQ.matcher(clean);
        if (m.find()) {
            refs.put("actionName", m.group(1));
        }

        // Server from resource in Server::"name"
        m = RESOURCE_IN_SERVER.matcher(clean);
        if (m.find()) {
            refs.put("serverName", m.group(1));
        }

        // Fallback: infer server from capability namespace prefix (e.g. Github_get_me → Github)
        if (!refs.containsKey("serverName") && refs.containsKey("capabilityName")) {
            String cap = refs.get("capabilityName");
            int sep = cap.indexOf('_');
            if (sep > 0) {
                refs.put("serverName", cap.substring(0, sep));
            }
        }

        return refs;
    }

    /**
     * Evaluate a single condition against the evaluation context.
     *
     * <p>Structural operators (IN_GROUP, IN_SERVER, EQ_ENTITY) use specific
     * attribute lookups. All other operators use the generic attribute map
     * resolved by {@link Condition.Source}.
     */
    private boolean evaluateCondition(Condition cond, EvalContext ctx) {

        // ── Structural operators (Cedar-specific) ───────────────────
        if (cond.operator == Condition.Operator.IN_GROUP) {
            // principal in AgentGroup::"APPROVED" → check approvalStatus
            Object status = ctx.principalAttrs.get("approvalStatus");
            return status != null && cond.field.equals(String.valueOf(status));
        }

        if (cond.operator == Condition.Operator.IN_SERVER) {
            // resource in Server::"github" → check serverName
            Object server = ctx.resourceAttrs.get("serverName");
            return server != null && cond.field.equals(String.valueOf(server));
        }

        if (cond.operator == Condition.Operator.EQ_ENTITY) {
            if (cond.source == Condition.Source.PRINCIPAL) {
                // principal == Agent::"claude-desktop"
                return cond.field.equals(ctx.principalId);
            }
            if (cond.source == Condition.Source.RESOURCE) {
                // resource == Tool::"github_create_issue"
                boolean typeMatch = cond.entityType == null || cond.entityType.equals(ctx.resourceType);
                return typeMatch && cond.field.equals(ctx.resourceId);
            }
            return false;
        }

        // ── Generic attribute operators (dynamic map lookup) ────────
        Map<String, Object> attrs = resolveAttrMap(cond.source, ctx);
        Object val = attrs.get(cond.field);
        if (val == null) return false;

        return switch (cond.operator) {
            case EQ -> String.valueOf(val).equals(cond.value);
            case NEQ -> !String.valueOf(val).equals(cond.value);
            case GT -> {
                Long num = toLong(val);
                yield num != null && num > Long.parseLong(cond.value);
            }
            case LT -> {
                Long num = toLong(val);
                yield num != null && num < Long.parseLong(cond.value);
            }
            case GTE -> {
                Long num = toLong(val);
                yield num != null && num >= Long.parseLong(cond.value);
            }
            case LTE -> {
                Long num = toLong(val);
                yield num != null && num <= Long.parseLong(cond.value);
            }
            case LIKE -> matchesWildcard(String.valueOf(val), cond.value);
            case CONTAINS -> String.valueOf(val).contains(cond.value);
            // Structural operators already handled above
            default -> false;
        };
    }

    /**
     * Resolve the attribute map for a given source.
     */
    private Map<String, Object> resolveAttrMap(Condition.Source source, EvalContext ctx) {
        return switch (source) {
            case PRINCIPAL -> ctx.principalAttrs;
            case RESOURCE -> ctx.resourceAttrs;
            case CONTEXT -> ctx.contextAttrs;
        };
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Convert any numeric Object to Long.
     */
    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Long l) return l;
        if (val instanceof Integer i) return (long) i;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(val));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Cedar-style wildcard matching.
     * {@code *} matches any sequence of characters (including empty).
     *
     * @param text the text to match against
     * @param pattern the Cedar-style wildcard pattern
     * @return true if the text matches the pattern
     */
    private boolean matchesWildcard(String text, String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if ("\\[]{}()^$.|+?".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        regex.append("$");

        try {
            return text.matches(regex.toString());
        } catch (Exception e) {
            log.debug("🏛️  Cedar: Wildcard pattern match failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if current time is within business hours (Mon-Fri, 8am-6pm).
     */
    private boolean isBusinessHours(LocalDateTime dateTime) {
        DayOfWeek day = dateTime.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = dateTime.toLocalTime();
        return !time.isBefore(LocalTime.of(8, 0)) && time.isBefore(LocalTime.of(18, 0));
    }
}
