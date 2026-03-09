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
 *   <li><b>Entity equality (in conditions):</b>
 *       {@code principal == Agent::"name"},
 *       {@code resource == Tool::"name"}</li>
 *   <li><b>Principal attributes:</b>
 *       {@code principal.version == "1.0"},
 *       {@code principal.approvalStatus == "APPROVED"}</li>
 *   <li><b>Resource attributes:</b>
 *       {@code resource.originalName == "create_issue"},
 *       {@code resource.serverName == "github"}</li>
 *   <li><b>Context equality:</b>
 *       {@code context.field == value} (bool, string, long)</li>
 *   <li><b>Context inequality:</b>
 *       {@code context.field != value} (bool, string)</li>
 *   <li><b>Numeric comparisons:</b>
 *       {@code context.hour > 18}, {@code context.hour >= 8},
 *       {@code context.hour < 6}, {@code context.hour <= 22}</li>
 *   <li><b>Pattern matching:</b>
 *       {@code context.argumentsFlat like "*production*"}</li>
 *   <li><b>String containment:</b>
 *       {@code context.argumentsFlat.contains("secret")}</li>
 *   <li><b>Combined conditions:</b>
 *       {@code &&} (AND) within when/unless blocks</li>
 * </ul>
 *
 * <h3>Entity Model (Cedar types)</h3>
 * <ul>
 *   <li>{@code Agent::"<name>"} — the AI agent (principal)</li>
 *   <li>{@code AgentGroup::"<approval_status>"} — approval group hierarchy</li>
 *   <li>{@code Action::"<action>"} — toolCall, promptGet, resourceRead, etc.</li>
 *   <li>{@code Tool::"<name>"}, {@code Prompt::"<name>"}, {@code Resource::"<name>"}</li>
 *   <li>{@code Server::"<name>"} — enterprise MCP server (resource parent)</li>
 * </ul>
 *
 * <h3>Evaluation Semantics (Cedar-compliant)</h3>
 * <ol>
 *   <li>If ANY {@code forbid} policy matches → DENY (forbid overrides permit)</li>
 *   <li>If at least one {@code permit} policy matches → ALLOW</li>
 *   <li>If no policy matches → DENY (default-deny)</li>
 * </ol>
 *
 * <h3>Forbid Short-Circuit Optimization</h3>
 * <p>Once any {@code forbid} policy matches, evaluation stops immediately
 * because the result is guaranteed DENY regardless of remaining policies.
 * {@code permit} policies continue evaluation to collect all matching IDs for audit.
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
     * Combined conditions (&&) produce multiple Condition objects — all must be satisfied (AND semantics).
     */
    private static class Condition {
        enum Type {
            // ── Hierarchy membership ────────────────────────────────
            PRINCIPAL_IN_GROUP,         // principal in AgentGroup::"X"
            RESOURCE_IN_SERVER,         // resource in Server::"X"

            // ── Entity equality ─────────────────────────────────────
            PRINCIPAL_EQ_ENTITY,        // principal == Agent::"X"
            RESOURCE_EQ_ENTITY,         // resource == Tool::"X" / Prompt::"X" / Resource::"X"

            // ── Principal attributes ────────────────────────────────
            PRINCIPAL_ATTR_EQ_STRING,   // principal.version == "1.0"

            // ── Resource attributes ─────────────────────────────────
            RESOURCE_ATTR_EQ_STRING,    // resource.originalName == "create_issue"

            // ── Context equality ────────────────────────────────────
            CONTEXT_EQ_BOOL,            // context.field == true/false
            CONTEXT_EQ_STRING,          // context.field == "value"
            CONTEXT_EQ_LONG,            // context.field == 123

            // ── Context inequality ──────────────────────────────────
            CONTEXT_NEQ_BOOL,           // context.field != true/false
            CONTEXT_NEQ_STRING,         // context.field != "value"

            // ── Numeric comparisons ─────────────────────────────────
            CONTEXT_GT_LONG,            // context.field > 123
            CONTEXT_LT_LONG,            // context.field < 123
            CONTEXT_GTE_LONG,           // context.field >= 123
            CONTEXT_LTE_LONG,           // context.field <= 123

            // ── Pattern matching ────────────────────────────────────
            CONTEXT_LIKE,               // context.field like "*pattern*"
            CONTEXT_CONTAINS,           // context.field.contains("pattern")
        }

        Type type;
        String field;       // group name, server name, entity id, context field, or attribute name
        String value;       // comparison value (may be null for PRINCIPAL_IN_GROUP)
        String entityType;  // for RESOURCE_EQ_ENTITY: "Tool", "Prompt", "Resource"
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

    // ── Condition patterns (used in when/unless blocks) ────────────────

    /** principal in AgentGroup::"X" */
    private static final Pattern PRINCIPAL_IN_GROUP = Pattern.compile("principal\\s+in\\s+AgentGroup::\"([^\"]+)\"");

    /** resource in Server::"X" */
    private static final Pattern RESOURCE_IN_SERVER = Pattern.compile("resource\\s+in\\s+Server::\"([^\"]+)\"");

    /** principal == Agent::"X" (in condition blocks) */
    private static final Pattern COND_PRINCIPAL_EQ = Pattern.compile("principal\\s*==\\s*Agent::\"([^\"]+)\"");

    /** resource == Tool/Prompt/Resource::"X" (in condition blocks) */
    private static final Pattern COND_RESOURCE_EQ = Pattern.compile("resource\\s*==\\s*(Tool|Prompt|Resource)::\"([^\"]+)\"");

    /** principal.attribute == "value" */
    private static final Pattern PRINCIPAL_ATTR_EQ = Pattern.compile("principal\\.(\\w+)\\s*==\\s*\"([^\"]+)\"");

    /** resource.attribute == "value" */
    private static final Pattern RESOURCE_ATTR_EQ = Pattern.compile("resource\\.(\\w+)\\s*==\\s*\"([^\"]+)\"");

    /** context.field like "*pattern*" */
    private static final Pattern CONTEXT_LIKE = Pattern.compile("context\\.(\\w+)\\s+like\\s+\"([^\"]+)\"");

    /** context.field.contains("value") */
    private static final Pattern CONTEXT_CONTAINS = Pattern.compile("context\\.(\\w+)\\.contains\\(\"([^\"]+)\"\\)");

    /** context.field >= N (must match before > to avoid false match) */
    private static final Pattern CONTEXT_GTE_LONG = Pattern.compile("context\\.(\\w+)\\s*>=\\s*(\\d+)");

    /** context.field <= N (must match before < to avoid false match) */
    private static final Pattern CONTEXT_LTE_LONG = Pattern.compile("context\\.(\\w+)\\s*<=\\s*(\\d+)");

    /** context.field > N (negative lookahead excludes >=) */
    private static final Pattern CONTEXT_GT_LONG = Pattern.compile("context\\.(\\w+)\\s*>(?!=)\\s*(\\d+)");

    /** context.field < N (negative lookahead excludes <=) */
    private static final Pattern CONTEXT_LT_LONG = Pattern.compile("context\\.(\\w+)\\s*<(?!=)\\s*(\\d+)");

    /** context.field != true/false */
    private static final Pattern CONTEXT_NEQ_BOOL = Pattern.compile("context\\.(\\w+)\\s*!=\\s*(true|false)");

    /** context.field != "string" */
    private static final Pattern CONTEXT_NEQ_STRING = Pattern.compile("context\\.(\\w+)\\s*!=\\s*\"([^\"]+)\"");

    /** context.field == true/false */
    private static final Pattern CONTEXT_EQ_BOOL = Pattern.compile("context\\.(\\w+)\\s*==\\s*(true|false)");

    /** context.field == "string" */
    private static final Pattern CONTEXT_EQ_STRING = Pattern.compile("context\\.(\\w+)\\s*==\\s*\"([^\"]+)\"");

    /** context.field == 123 */
    private static final Pattern CONTEXT_EQ_LONG = Pattern.compile("context\\.(\\w+)\\s*==\\s*(\\d+)");

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
        log.info("🏛️  Cedar Policy Engine initialized (pure-Java evaluator — granular AuthZ)");
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
                log.info("🏛️  Cedar: No policies to load — all requests will be ALLOWED (no-policy mode)");
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
                } else {
                    log.warn("🏛️  Cedar: Skipped unparseable policy: {}", entity.getPolicyName());
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
     * <p>Cedar evaluation semantics:
     * <ol>
     *   <li>If ANY forbid policy matches → DENY (forbid overrides permit)</li>
     *   <li>If at least one permit policy matches → ALLOW</li>
     *   <li>If no policy matches → DENY (default-deny)</li>
     * </ol>
     *
     * <p><b>Forbid short-circuit:</b> once a forbid matches, we stop evaluating
     * remaining policies because the result is guaranteed DENY.
     *
     * <p>If no policies are loaded, returns ALLOW (no-policy mode).
     * The gateway should not block by default until the admin explicitly creates policies.
     *
     * @param request the structured evaluation request
     * @return evaluation result with decision, matched policies, and timing
     */
    public PolicyEvaluationResult evaluate(PolicyEvaluationRequest request) {
        long startTime = System.currentTimeMillis();

        // ── No-policy mode: allow everything ──────────────────────────
        if (!policiesLoaded || currentPolicies.isEmpty()) {
            long duration = System.currentTimeMillis() - startTime;
            return PolicyEvaluationResult.noPolicies(duration);
        }

        policyLock.readLock().lock();
        try {
            // Build evaluation context from request
            EvalContext ctx = buildEvalContext(request);

            // Track matching policies
            Set<String> matchedPermitPolicies = new LinkedHashSet<>();
            Set<String> matchedForbidPolicies = new LinkedHashSet<>();

            for (ParsedPolicy policy : currentPolicies) {
                if (matches(policy, ctx)) {
                    String policyRef = policy.id != null ? policy.id : policy.name;

                    if ("forbid".equals(policy.effect)) {
                        matchedForbidPolicies.add(policyRef);
                        // ── Forbid short-circuit: result is guaranteed DENY ──
                        // No need to evaluate remaining policies
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

            // ── Cedar evaluation semantics ────────────────────────────
            // (Forbid already handled above via short-circuit)

            // 2. If any permit matches → ALLOW
            if (!matchedPermitPolicies.isEmpty()) {
                log.info("🏛️  Cedar: agent={}, action={}, resource={} → ALLOW ({}ms, permit={})",
                        request.getAgentName(), request.getAction(),
                        request.getResourceName(), duration, matchedPermitPolicies);
                return PolicyEvaluationResult.allow(matchedPermitPolicies, duration);
            }

            // 3. No matches → default DENY
            log.info("🏛️  Cedar: agent={}, action={}, resource={} → DENY ({}ms, no matching policy)",
                    request.getAgentName(), request.getAction(),
                    request.getResourceName(), duration);
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

        if (permitIdx < 0 && forbidIdx < 0) return null; // not a valid policy

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
        // Check for exact entity equality first: principal == Agent::"X"
        Matcher principalEqMatch = PRINCIPAL_EQ.matcher(headClause);
        if (principalEqMatch.find()) {
            pp.principalType = "Agent";          // implicit type constraint
            pp.principalEntityId = principalEqMatch.group(1);
        } else {
            // Fall back to type constraint: principal is Agent
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
        // Check for exact entity equality first: resource == Tool::"X"
        Matcher resourceEqMatch = RESOURCE_EQ.matcher(headClause);
        if (resourceEqMatch.find()) {
            pp.resourceType = resourceEqMatch.group(1);     // "Tool", "Prompt", "Resource"
            pp.resourceEntityId = resourceEqMatch.group(2);
        } else {
            // Fall back to type constraint: resource is Tool
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
     *
     * <p>Supports combined conditions via {@code &&} operator.
     * Each fragment separated by {@code &&} is parsed independently.
     * All conditions must be satisfied (AND semantics).
     *
     * <p>Example:
     * <pre>{@code
     * principal in AgentGroup::"APPROVED" &&
     * resource in Server::"github" &&
     * context.businessHours == true &&
     * context.argumentsFlat like "*production*"
     * }</pre>
     * Produces 4 conditions, all evaluated with AND semantics.
     */
    private List<Condition> parseConditions(String body) {
        List<Condition> conditions = new ArrayList<>();

        // Split on && to handle combined conditions
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
     * Patterns are checked in order of specificity to avoid false matches.
     */
    private Condition parseSingleCondition(String fragment) {
        Matcher m;

        // ── 1. Hierarchy membership ─────────────────────────────────
        m = PRINCIPAL_IN_GROUP.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.PRINCIPAL_IN_GROUP;
            c.field = m.group(1);
            return c;
        }

        m = RESOURCE_IN_SERVER.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.RESOURCE_IN_SERVER;
            c.field = m.group(1);
            return c;
        }

        // ── 2. Entity equality ──────────────────────────────────────
        m = COND_PRINCIPAL_EQ.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.PRINCIPAL_EQ_ENTITY;
            c.field = m.group(1); // agent name
            return c;
        }

        m = COND_RESOURCE_EQ.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.RESOURCE_EQ_ENTITY;
            c.entityType = m.group(1); // "Tool", "Prompt", "Resource"
            c.field = m.group(2);      // entity name
            return c;
        }

        // ── 3. Principal attributes ─────────────────────────────────
        m = PRINCIPAL_ATTR_EQ.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.PRINCIPAL_ATTR_EQ_STRING;
            c.field = m.group(1); // attribute name (version, approvalStatus)
            c.value = m.group(2);
            return c;
        }

        // ── 4. Resource attributes ──────────────────────────────────
        m = RESOURCE_ATTR_EQ.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.RESOURCE_ATTR_EQ_STRING;
            c.field = m.group(1); // attribute name (originalName, serverName)
            c.value = m.group(2);
            return c;
        }

        // ── 5. Pattern matching (like / contains) ───────────────────
        m = CONTEXT_LIKE.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.CONTEXT_LIKE;
            c.field = m.group(1);
            c.value = m.group(2); // pattern with * wildcards
            return c;
        }

        m = CONTEXT_CONTAINS.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.CONTEXT_CONTAINS;
            c.field = m.group(1);
            c.value = m.group(2);
            return c;
        }

        // ── 6. Numeric comparisons (check >= and <= BEFORE > and <) ─
        m = CONTEXT_GTE_LONG.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.CONTEXT_GTE_LONG;
            c.field = m.group(1);
            c.value = m.group(2);
            return c;
        }

        m = CONTEXT_LTE_LONG.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.CONTEXT_LTE_LONG;
            c.field = m.group(1);
            c.value = m.group(2);
            return c;
        }

        m = CONTEXT_GT_LONG.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.CONTEXT_GT_LONG;
            c.field = m.group(1);
            c.value = m.group(2);
            return c;
        }

        m = CONTEXT_LT_LONG.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.CONTEXT_LT_LONG;
            c.field = m.group(1);
            c.value = m.group(2);
            return c;
        }

        // ── 7. Inequality (check != BEFORE ==) ─────────────────────
        m = CONTEXT_NEQ_BOOL.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.CONTEXT_NEQ_BOOL;
            c.field = m.group(1);
            c.value = m.group(2);
            return c;
        }

        m = CONTEXT_NEQ_STRING.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.CONTEXT_NEQ_STRING;
            c.field = m.group(1);
            c.value = m.group(2);
            return c;
        }

        // ── 8. Equality ────────────────────────────────────────────
        m = CONTEXT_EQ_BOOL.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.CONTEXT_EQ_BOOL;
            c.field = m.group(1);
            c.value = m.group(2);
            return c;
        }

        m = CONTEXT_EQ_STRING.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.CONTEXT_EQ_STRING;
            c.field = m.group(1);
            c.value = m.group(2);
            return c;
        }

        m = CONTEXT_EQ_LONG.matcher(fragment);
        if (m.find()) {
            Condition c = new Condition();
            c.type = Condition.Type.CONTEXT_EQ_LONG;
            c.field = m.group(1);
            c.value = m.group(2);
            return c;
        }

        return null; // unrecognized condition
    }

    /**
     * Find the matching closing parenthesis for the opening paren at position pos.
     */
    private int findMatchingParen(String s, int pos) {
        int depth = 0;
        for (int i = pos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ════════════════════════════════════════════════════════════════════
    //  POLICY EVALUATION (matching)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Evaluation context — flattened request data for matching against policies.
     */
    private static class EvalContext {
        // ── Principal ──────────────────────────────────────────────
        String principalType;       // "Agent"
        String principalId;         // agent name (e.g., "claude-desktop")
        String approvalStatus;      // agent approval status ("APPROVED", "PENDING", "BLOCKED")
        String principalVersion;    // agent version (e.g., "1.0.2")

        // ── Action ─────────────────────────────────────────────────
        String action;              // "toolCall", "promptGet", "resourceRead"

        // ── Resource ───────────────────────────────────────────────
        String resourceType;        // "Tool", "Prompt", "Resource"
        String resourceId;          // public namespaced name (e.g., "github_create_issue")
        String serverName;          // parent server name (e.g., "github")
        String originalName;        // original name on server (e.g., "create_issue")

        // ── Context: environment ───────────────────────────────────
        boolean businessHours;
        int hour;
        String dayOfWeek;
        String sourceIp;
        String correlationId;

        // ── Context: request data ──────────────────────────────────
        String argumentsFlat;       // flattened tool arguments for pattern matching
    }

    /**
     * Build evaluation context from a PolicyEvaluationRequest.
     */
    private EvalContext buildEvalContext(PolicyEvaluationRequest request) {
        EvalContext ctx = new EvalContext();

        // Principal
        ctx.principalType = "Agent";
        ctx.principalId = request.getAgentName();
        ctx.approvalStatus = request.getAgentApprovalStatus();
        ctx.principalVersion = request.getAgentVersion();

        // Action
        ctx.action = request.getAction();

        // Resource
        ctx.resourceType = request.getResourceType() != null ? request.getResourceType() : "Tool";
        ctx.resourceId = request.getResourceName();
        ctx.serverName = request.getServerName();
        ctx.originalName = request.getOriginalName();

        // Environment context
        LocalDateTime now = LocalDateTime.now();
        ctx.businessHours = isBusinessHours(now);
        ctx.hour = now.getHour();
        ctx.dayOfWeek = now.getDayOfWeek().name();
        ctx.sourceIp = request.getSourceIp();
        ctx.correlationId = request.getCorrelationId();

        // Flatten arguments for pattern matching
        ctx.argumentsFlat = flattenArguments(request.getArguments());

        return ctx;
    }

    /**
     * Flatten tool arguments map into a single searchable string.
     * Format: "key1=value1 key2=value2 ..."
     *
     * <p>This enables patterns like:
     * <pre>{@code context.argumentsFlat like "*production*"}</pre>
     * to match any argument containing "production".
     */
    private String flattenArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        return arguments.entrySet().stream()
                .map(e -> e.getKey() + "=" + (e.getValue() != null ? e.getValue().toString() : "null"))
                .collect(Collectors.joining(" "));
    }

    /**
     * Check if a parsed policy matches the given evaluation context.
     *
     * <p>A policy matches if ALL of these hold:
     * <ol>
     *   <li>Head clause matches (principal type/entity, action, resource type/entity)</li>
     *   <li>All {@code when} conditions evaluate to TRUE</li>
     *   <li>All {@code unless} conditions evaluate to FALSE
     *       (unless = "except when" → policy fires unless the condition is true)</li>
     * </ol>
     */
    private boolean matches(ParsedPolicy policy, EvalContext ctx) {
        // ── Head clause: principal ───────────────────────────────────
        if (policy.principalType != null && !policy.principalType.equals(ctx.principalType)) {
            return false;
        }
        // Exact entity equality: principal == Agent::"claude-desktop"
        if (policy.principalEntityId != null && !policy.principalEntityId.equals(ctx.principalId)) {
            return false;
        }

        // ── Head clause: action ─────────────────────────────────────
        if (policy.actionId != null && !policy.actionId.equals(ctx.action)) {
            return false;
        }
        if (policy.actionIds != null && !policy.actionIds.isEmpty() && !policy.actionIds.contains(ctx.action)) {
            return false;
        }

        // ── Head clause: resource ───────────────────────────────────
        if (policy.resourceType != null && !policy.resourceType.equals(ctx.resourceType)) {
            return false;
        }
        // Exact entity equality: resource == Tool::"github_create_issue"
        if (policy.resourceEntityId != null && !policy.resourceEntityId.equals(ctx.resourceId)) {
            return false;
        }

        // ── When conditions: ALL must be TRUE for policy to apply ────
        for (Condition cond : policy.whenConditions) {
            if (!evaluateCondition(cond, ctx)) {
                return false;
            }
        }

        // ── Unless conditions: if ANY is TRUE, policy does NOT apply ─
        // "forbid(...) unless { businessHours }" means:
        //   forbid fires when businessHours is FALSE (not in business hours)
        //   forbid does NOT fire when businessHours is TRUE
        for (Condition cond : policy.unlessConditions) {
            if (evaluateCondition(cond, ctx)) {
                return false; // unless-condition is true → policy does NOT match
            }
        }

        return true;
    }

    /**
     * Evaluate a single condition against the evaluation context.
     */
    private boolean evaluateCondition(Condition cond, EvalContext ctx) {
        return switch (cond.type) {

            // ── Hierarchy membership ────────────────────────────────
            case PRINCIPAL_IN_GROUP -> {
                // principal in AgentGroup::"APPROVED" → check approvalStatus
                yield cond.field.equals(ctx.approvalStatus);
            }
            case RESOURCE_IN_SERVER -> {
                // resource in Server::"github" → check serverName
                yield cond.field.equals(ctx.serverName);
            }

            // ── Entity equality ─────────────────────────────────────
            case PRINCIPAL_EQ_ENTITY -> {
                // principal == Agent::"claude-desktop"
                yield cond.field.equals(ctx.principalId);
            }
            case RESOURCE_EQ_ENTITY -> {
                // resource == Tool::"github_create_issue"
                // Also check entity type matches resource type
                boolean typeMatches = cond.entityType == null || cond.entityType.equals(ctx.resourceType);
                yield typeMatches && cond.field.equals(ctx.resourceId);
            }

            // ── Principal attributes ────────────────────────────────
            case PRINCIPAL_ATTR_EQ_STRING -> {
                yield switch (cond.field) {
                    case "version" -> cond.value.equals(ctx.principalVersion);
                    case "approvalStatus" -> cond.value.equals(ctx.approvalStatus);
                    default -> false;
                };
            }

            // ── Resource attributes ─────────────────────────────────
            case RESOURCE_ATTR_EQ_STRING -> {
                yield switch (cond.field) {
                    case "originalName" -> cond.value.equals(ctx.originalName);
                    case "serverName" -> cond.value.equals(ctx.serverName);
                    default -> false;
                };
            }

            // ── Context equality ────────────────────────────────────
            case CONTEXT_EQ_BOOL -> {
                yield resolveContextBool(cond.field, ctx) != null
                        && String.valueOf(resolveContextBool(cond.field, ctx)).equals(cond.value);
            }
            case CONTEXT_EQ_STRING -> {
                String resolved = resolveContextString(cond.field, ctx);
                yield resolved != null && cond.value.equals(resolved);
            }
            case CONTEXT_EQ_LONG -> {
                Long resolved = resolveContextLong(cond.field, ctx);
                yield resolved != null && String.valueOf(resolved).equals(cond.value);
            }

            // ── Context inequality ──────────────────────────────────
            case CONTEXT_NEQ_BOOL -> {
                Boolean resolved = resolveContextBool(cond.field, ctx);
                yield resolved != null && !String.valueOf(resolved).equals(cond.value);
            }
            case CONTEXT_NEQ_STRING -> {
                String resolved = resolveContextString(cond.field, ctx);
                yield resolved != null && !cond.value.equals(resolved);
            }

            // ── Numeric comparisons ─────────────────────────────────
            case CONTEXT_GT_LONG -> {
                Long resolved = resolveContextLong(cond.field, ctx);
                yield resolved != null && resolved > Long.parseLong(cond.value);
            }
            case CONTEXT_LT_LONG -> {
                Long resolved = resolveContextLong(cond.field, ctx);
                yield resolved != null && resolved < Long.parseLong(cond.value);
            }
            case CONTEXT_GTE_LONG -> {
                Long resolved = resolveContextLong(cond.field, ctx);
                yield resolved != null && resolved >= Long.parseLong(cond.value);
            }
            case CONTEXT_LTE_LONG -> {
                Long resolved = resolveContextLong(cond.field, ctx);
                yield resolved != null && resolved <= Long.parseLong(cond.value);
            }

            // ── Pattern matching ────────────────────────────────────
            case CONTEXT_LIKE -> {
                // Cedar `like` uses * as wildcard (matches any sequence of characters)
                String resolved = resolveContextString(cond.field, ctx);
                yield resolved != null && matchesWildcard(resolved, cond.value);
            }
            case CONTEXT_CONTAINS -> {
                // context.field.contains("value") → substring check
                String resolved = resolveContextString(cond.field, ctx);
                yield resolved != null && resolved.contains(cond.value);
            }
        };
    }

    // ════════════════════════════════════════════════════════════════════
    //  CONTEXT FIELD RESOLUTION
    // ════════════════════════════════════════════════════════════════════

    /**
     * Resolve a context field name to its boolean value.
     */
    private Boolean resolveContextBool(String field, EvalContext ctx) {
        return switch (field) {
            case "businessHours" -> ctx.businessHours;
            default -> null;
        };
    }

    /**
     * Resolve a context field name to its string value.
     */
    private String resolveContextString(String field, EvalContext ctx) {
        return switch (field) {
            case "dayOfWeek" -> ctx.dayOfWeek;
            case "sourceIp" -> ctx.sourceIp;
            case "serverName" -> ctx.serverName;
            case "resourceName" -> ctx.resourceId;
            case "correlationId" -> ctx.correlationId;
            case "argumentsFlat" -> ctx.argumentsFlat;
            default -> null;
        };
    }

    /**
     * Resolve a context field name to its long value.
     */
    private Long resolveContextLong(String field, EvalContext ctx) {
        return switch (field) {
            case "hour" -> (long) ctx.hour;
            default -> null;
        };
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Cedar-style wildcard matching.
     * {@code *} matches any sequence of characters (including empty).
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "*production*"} matches "env=production server=db"</li>
     *   <li>{@code "github_*"} matches "github_create_issue"</li>
     *   <li>{@code "*.json"} matches "config.json"</li>
     * </ul>
     *
     * @param text the text to match against
     * @param pattern the Cedar-style wildcard pattern
     * @return true if the text matches the pattern
     */
    private boolean matchesWildcard(String text, String pattern) {
        // Convert Cedar wildcard pattern to Java regex:
        // 1. Escape all regex special characters except *
        // 2. Replace * with .*
        // 3. Wrap in ^...$ for full match
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
