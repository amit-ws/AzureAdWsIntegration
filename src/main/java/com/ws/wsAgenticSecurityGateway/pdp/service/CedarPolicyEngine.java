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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
public class CedarPolicyEngine {

    private static class ParsedPolicy {
        String id;
        String name;
        String effect;

        String principalType;
        String principalEntityId;

        String actionId;
        List<String> actionIds;

        String resourceType;
        String resourceEntityId;
        List<String> resourceEntityIds;   // resource in [ X::"a", X::"b" ] — any listed resource is allowed

        List<Condition> whenConditions = new ArrayList<>();
        List<Condition> unlessConditions = new ArrayList<>();
    }

    private static class Condition {
        enum Source {
            CONTEXT,
            PRINCIPAL,
            RESOURCE
        }

        enum Operator {
            EQ,
            NEQ,
            GT,
            LT,
            GTE,
            LTE,
            LIKE,
            CONTAINS,
            IN_GROUP,
            IN_SERVER,
            EQ_ENTITY
        }

        Source source;
        Operator operator;
        String field;
        String value;
        String entityType;
    }

    private static final Pattern ID_ANNOTATION = Pattern.compile("@id\\(\"([^\"]+)\"\\)");

    private static final Pattern WHEN_BLOCK = Pattern.compile("(?s)when\\s*\\{(.+?)\\}");

    private static final Pattern UNLESS_BLOCK = Pattern.compile("(?s)unless\\s*\\{(.+?)\\}");

    private static final Pattern PRINCIPAL_IS = Pattern.compile("principal\\s+is\\s+(\\w+)");

    private static final Pattern PRINCIPAL_EQ = Pattern.compile("principal\\s*==\\s*Agent::\"([^\"]+)\"");

    private static final Pattern ACTION_EQ = Pattern.compile("action\\s*==\\s*Action::\"([^\"]+)\"");

    private static final Pattern ACTION_IN = Pattern.compile("action\\s+in\\s+\\[([^\\]]+)\\]");
    private static final Pattern ACTION_ITEM = Pattern.compile("Action::\"([^\"]+)\"");

    private static final Pattern RESOURCE_IS = Pattern.compile("resource\\s+is\\s+(\\w+)");

    private static final Pattern RESOURCE_EQ = Pattern.compile("resource\\s*==\\s*(Tool|Prompt|Resource|Skill)::\"([^\"]+)\"");

    // resource in [ Type::"a", Type::"b", ... ] — a set of allowed resources (any resource entity type).
    private static final Pattern RESOURCE_IN_SET = Pattern.compile("resource\\s+in\\s+\\[([^\\]]+)\\]");
    private static final Pattern RESOURCE_ITEM = Pattern.compile("(Tool|Prompt|Resource|Skill)::\"([^\"]+)\"");

    private static final Pattern PRINCIPAL_IN_GROUP = Pattern.compile("principal\\s+in\\s+AgentGroup::\"([^\"]+)\"");

    private static final Pattern RESOURCE_IN_SERVER = Pattern.compile("resource\\s+in\\s+Server::\"([^\"]+)\"");

    private static final Pattern COND_PRINCIPAL_EQ = Pattern.compile("principal\\s*==\\s*Agent::\"([^\"]+)\"");

    private static final Pattern COND_RESOURCE_EQ = Pattern.compile("resource\\s*==\\s*(Tool|Prompt|Resource|Skill)::\"([^\"]+)\"");

    private static final Pattern ATTR_GTE = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*>=\\s*(-?\\d+)");

    private static final Pattern ATTR_LTE = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*<=\\s*(-?\\d+)");

    private static final Pattern ATTR_GT = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*>(?!=)\\s*(-?\\d+)");

    private static final Pattern ATTR_LT = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*<(?!=)\\s*(-?\\d+)");

    private static final Pattern ATTR_LIKE = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s+like\\s+\"([^\"]+)\"");

    private static final Pattern ATTR_CONTAINS = Pattern.compile("(context|principal|resource)\\.(\\w+)\\.contains\\(\"([^\"]+)\"\\)");

    private static final Pattern ATTR_NEQ_BOOL = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*!=\\s*(true|false)");

    private static final Pattern ATTR_NEQ_STRING = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*!=\\s*\"([^\"]+)\"");

    private static final Pattern ATTR_NEQ_LONG = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*!=\\s*(-?\\d+)");

    private static final Pattern ATTR_EQ_BOOL = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*==\\s*(true|false)");

    private static final Pattern ATTR_EQ_STRING = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*==\\s*\"([^\"]+)\"");

    private static final Pattern ATTR_EQ_LONG = Pattern.compile("(context|principal|resource)\\.(\\w+)\\s*==\\s*(-?\\d+)");

    /**
     * Per-tenant compiled policy sets — the isolated slots that make the PDP tenant-partitioned. Each value is
     * an immutable snapshot swapped atomically, so evaluation reads a consistent set with no lock.
     */
    private final Map<String, List<ParsedPolicy>> policiesByTenant = new ConcurrentHashMap<>();

    /**
     * Combined fallback set, used ONLY for requests with no resolvable tenant (open mode / pre-auth). Real
     * tenants are isolated in {@link #policiesByTenant}; this preserves prior behavior for the null-tenant edge.
     */
    private volatile List<ParsedPolicy> globalPolicies = Collections.emptyList();

    /** Lazily populates an unseen tenant's slot on first evaluation (and seeds its baseline guardrails). */
    private volatile TenantPolicyLoader tenantLoader;

    /** Supplies a tenant's enabled policy rows on demand; set by {@link PolicyService} after construction. */
    public interface TenantPolicyLoader {
        List<GatewayPolicyEntity> load(String tenant);
    }

    public void setTenantLoader(TenantPolicyLoader loader) {
        this.tenantLoader = loader;
    }

    public CedarPolicyEngine() {
 log.info("Cedar Policy Engine initialized (pure-Java evaluator — dynamic ABAC, tenant-partitioned)");
    }

    /** Backward-compatible: loads into the combined fallback set (null-tenant requests + tests). */
    public int reloadPolicies(List<GatewayPolicyEntity> policies) {
        return reloadGlobal(policies);
    }

    /** Load/replace the combined fallback policy set (used for requests with no resolvable tenant). */
    public int reloadGlobal(List<GatewayPolicyEntity> policies) {
        List<ParsedPolicy> parsed = parse(policies);
        globalPolicies = parsed;
 log.info("Cedar: loaded {} policy(ies) into the combined fallback set", parsed.size());
        return parsed.size();
    }

    /** Load/replace one tenant's isolated policy set (blank tenant → the fallback set). */
    public int reloadTenant(String tenant, List<GatewayPolicyEntity> policies) {
        if (tenant == null || tenant.isBlank()) {
            return reloadGlobal(policies);
        }
        List<ParsedPolicy> parsed = parse(policies);
        policiesByTenant.put(tenant, parsed);
 log.info("Cedar: loaded {} policy(ies) for tenant '{}'", parsed.size(), tenant);
        return parsed.size();
    }

    /** Parse policy rows into the compiled form; returns an immutable (possibly empty) list. */
    private List<ParsedPolicy> parse(List<GatewayPolicyEntity> policies) {
        if (policies == null || policies.isEmpty()) {
            return Collections.emptyList();
        }
        List<ParsedPolicy> parsed = new ArrayList<>();
        for (GatewayPolicyEntity entity : policies) {
            ParsedPolicy pp = parsePolicy(entity.getPolicyText(), entity.getPolicyName());
            if (pp != null) {
                parsed.add(pp);
            } else {
 log.warn("Cedar: Skipped unparseable policy: {} | text='{}'",
                        entity.getPolicyName(), entity.getPolicyText());
            }
        }
        return Collections.unmodifiableList(parsed);
    }

    /**
     * The compiled policy set that applies to {@code tenant}: the tenant's isolated slot (lazily loaded +
     * seeded on first use), or the combined fallback set when the tenant is unresolved.
     */
    private List<ParsedPolicy> resolvePolicies(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return globalPolicies;
        }
        List<ParsedPolicy> pols = policiesByTenant.get(tenant);
        if (pols != null) {
            return pols;
        }
        TenantPolicyLoader loader = this.tenantLoader;
        if (loader == null) {
            return globalPolicies; // loader not wired yet — fall back rather than deny
        }
        return policiesByTenant.computeIfAbsent(tenant, t -> {
            try {
                List<ParsedPolicy> parsed = parse(loader.load(t));
 log.info("Cedar: lazily loaded {} policy(ies) for tenant '{}' on first use", parsed.size(), t);
                return parsed;
            } catch (Exception e) {
 log.error("Cedar: lazy policy load for tenant '{}' failed — treating as no policies (deny): {}",
                        t, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

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
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** True if ANY tenant slot or the fallback set has policies (used for coarse status/stats). */
    public boolean hasPolicies() {
        return !globalPolicies.isEmpty() || policiesByTenant.values().stream().anyMatch(l -> !l.isEmpty());
    }

    /** True if the given tenant has policies loaded (no lazy-load side effect — for stats). */
    public boolean hasPolicies(String tenant) {
        List<ParsedPolicy> p = (tenant == null || tenant.isBlank()) ? globalPolicies : policiesByTenant.get(tenant);
        return p != null && !p.isEmpty();
    }

    /** Backward-compatible: evaluate against the combined fallback set (null tenant). */
    public PolicyEvaluationResult evaluate(PolicyEvaluationRequest request) {
        return evaluate(null, request);
    }

    /** Evaluate the request against {@code tenant}'s isolated policy set (fallback set when tenant is null). */
    public PolicyEvaluationResult evaluate(String tenant, PolicyEvaluationRequest request) {
        return evaluateParsed(resolvePolicies(tenant), tenant, request);
    }

    /**
     * Dry-run the request against an explicit set of policy rows (authoring-time "test a policy") — e.g. the
     * tenant's saved policies plus an unsaved draft. Parses on the fly; does not touch the loaded slots.
     */
    public PolicyEvaluationResult evaluateEntities(List<GatewayPolicyEntity> policies, PolicyEvaluationRequest request) {
        return evaluateParsed(parse(policies), "test", request);
    }

    private PolicyEvaluationResult evaluateParsed(List<ParsedPolicy> policies, String tenant, PolicyEvaluationRequest request) {
        long startTime = System.currentTimeMillis();

        if (policies.isEmpty()) {
 log.info("Cedar: tenant={}, agent={}, action={}, resource={} → DENY (no policies configured)",
                    tenant, request.getAgentName(), request.getAction(), request.getResourceName());
            long duration = System.currentTimeMillis() - startTime;
            return PolicyEvaluationResult.noPolicies(duration);
        }

        try {
            EvalContext ctx = buildEvalContext(request);

 log.info("Cedar EVAL: tenant='{}', principalType='{}', principalId='{}', action='{}', resourceType='{}', resourceId='{}', policiesCount={}",
                    tenant, ctx.principalType, ctx.principalId, ctx.action, ctx.resourceType, ctx.resourceId, policies.size());

            Set<String> matchedPermitPolicies = new LinkedHashSet<>();
            Set<String> matchedForbidPolicies = new LinkedHashSet<>();

            for (ParsedPolicy policy : policies) {
 log.info("Cedar MATCH CHECK: policy='{}' effect={} | principal: policy='{}' vs ctx='{}' ({}), action: policy='{}' vs ctx='{}' ({}), resource: policy='{}'/'{}' vs ctx='{}'/'{}' ({})",
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
                        long duration = System.currentTimeMillis() - startTime;
 log.info("Cedar: agent={}, action={}, resource={} → DENY ({}ms, forbid={}, short-circuit)",
                                request.getAgentName(), request.getAction(),
                                request.getResourceName(), duration, matchedForbidPolicies);
                        return PolicyEvaluationResult.deny(matchedForbidPolicies, duration);
                    } else {
                        matchedPermitPolicies.add(policyRef);
                    }
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            if (!matchedPermitPolicies.isEmpty()) {
 log.info("Cedar: agent={}, action={}, resource={} → ALLOW ({}ms, permit={})",
                        request.getAgentName(), request.getAction(),
                        request.getResourceName(), duration, matchedPermitPolicies);
                return PolicyEvaluationResult.allow(matchedPermitPolicies, duration);
            }

            String diagnostic = null;
            for (ParsedPolicy policy : policies) {
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
 log.warn("Cedar DIAGNOSTIC: {}", diagnostic);
                    break;
                }
            }

 log.info("Cedar: agent={}, action={}, resource={} → DENY ({}ms, no matching policy)",
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
 log.error("Cedar evaluation error — failing CLOSED (request DENIED): {}", e.getMessage(), e);

            // Fail-closed: if the decision engine itself errors, deny the request. A crash in the
            // gate must never become a security bypass (a forbid policy could have been about to
            // match). The error is surfaced loudly via reason/diagnostics/hasErrors + the audit
            // deny, so the bug is found and fixed rather than silently allowing traffic through.
            return PolicyEvaluationResult.builder()
                    .decision("DENY")
                    .matchedPolicies(Set.of())
                    .reason("Policy evaluation error (fail-closed, denied): " + e.getMessage())
                    .evaluationDurationMs(duration)
                    .hasErrors(true)
                    .diagnostics(e.getMessage())
                    .build();
        }
    }

    private ParsedPolicy parsePolicy(String policyText, String name) {
        if (policyText == null || policyText.isBlank()) return null;

        String clean = policyText.replaceAll("//[^\n]*", "").trim();

        ParsedPolicy pp = new ParsedPolicy();
        pp.name = name;

        Matcher idMatch = ID_ANNOTATION.matcher(clean);
        if (idMatch.find()) {
            pp.id = idMatch.group(1);
        }

        String lowerClean = clean.toLowerCase();
        int permitIdx = lowerClean.indexOf("permit");
        int forbidIdx = lowerClean.indexOf("forbid");

        if (permitIdx < 0 && forbidIdx < 0) return null;

        if (forbidIdx >= 0 && (permitIdx < 0 || forbidIdx < permitIdx)) {
            pp.effect = "forbid";
        } else {
            pp.effect = "permit";
        }

        int effectStart = "forbid".equals(pp.effect) ? forbidIdx : permitIdx;
        int parenStart = clean.indexOf('(', effectStart);
        if (parenStart < 0) return null;

        int parenEnd = findMatchingParen(clean, parenStart);
        if (parenEnd < 0) return null;

        String headClause = clean.substring(parenStart + 1, parenEnd);

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

        Matcher resourceEqMatch = RESOURCE_EQ.matcher(headClause);
        Matcher resourceInSetMatch = RESOURCE_IN_SET.matcher(headClause);
        if (resourceEqMatch.find()) {
            pp.resourceType = resourceEqMatch.group(1);
            pp.resourceEntityId = resourceEqMatch.group(2);
        } else if (resourceInSetMatch.find()) {
            // resource in [ Type::"a", Type::"b", ... ] — allowed iff the request resource is one of the listed.
            pp.resourceEntityIds = new ArrayList<>();
            Matcher itemMatch = RESOURCE_ITEM.matcher(resourceInSetMatch.group(1));
            while (itemMatch.find()) {
                if (pp.resourceType == null) {
                    pp.resourceType = itemMatch.group(1);   // homogeneous sets: type from the first entry
                }
                pp.resourceEntityIds.add(itemMatch.group(2));
            }
        } else {
            Matcher resourceIsMatch = RESOURCE_IS.matcher(headClause);
            if (resourceIsMatch.find()) {
                pp.resourceType = resourceIsMatch.group(1);
            }
        }

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
 log.debug("Cedar: Unrecognized condition fragment: {}", frag);
            }
        }

        return conditions;
    }

    private Condition parseSingleCondition(String fragment) {
        Matcher m;

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

        m = ATTR_LIKE.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.LIKE);
        }

        m = ATTR_CONTAINS.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.CONTAINS);
        }

        m = ATTR_GTE.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.GTE);
        }

        m = ATTR_LTE.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.LTE);
        }

        m = ATTR_GT.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.GT);
        }

        m = ATTR_LT.matcher(fragment);
        if (m.find()) {
            return buildAttrCondition(m.group(1), m.group(2), m.group(3), Condition.Operator.LT);
        }

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

        return null;
    }

    private Condition buildAttrCondition(String sourceStr, String field, String value, Condition.Operator operator) {
        Condition c = new Condition();
        c.source = parseSource(sourceStr);
        c.operator = operator;
        c.field = field;
        c.value = value;
        return c;
    }

    private Condition.Source parseSource(String source) {
        return switch (source.toLowerCase()) {
            case "principal" -> Condition.Source.PRINCIPAL;
            case "resource" -> Condition.Source.RESOURCE;
            default -> Condition.Source.CONTEXT;
        };
    }

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

    private static class EvalContext {
        String principalType;
        String principalId;
        String action;
        String resourceType;
        String resourceId;

        Map<String, Object> principalAttrs = new HashMap<>();
        Map<String, Object> resourceAttrs = new HashMap<>();
        Map<String, Object> contextAttrs = new HashMap<>();
        java.util.Set<String> principalGroups = new java.util.HashSet<>();
    }

    private EvalContext buildEvalContext(PolicyEvaluationRequest request) {
        EvalContext ctx = new EvalContext();

        ctx.principalType = "Agent";
        ctx.principalId = request.getAgentName();
        ctx.action = request.getAction();
        ctx.resourceType = request.getResourceType() != null ? request.getResourceType() : "Tool";
        ctx.resourceId = request.getResourceName();

        putIfNotNull(ctx.principalAttrs, "name", request.getAgentName());
        putIfNotNull(ctx.principalAttrs, "version", request.getAgentVersion());
        putIfNotNull(ctx.principalAttrs, "approvalStatus", request.getAgentApprovalStatus());
        putIfNotNull(ctx.principalAttrs, "sessionId", request.getAgentSessionId());

        // User (root) roles from the JWT — exposed so policies can do attribute-based access control on the
        // human/NHI identity, e.g. permit(...) when { principal.roles.contains("finance") }. Space-joined so
        // the substring-based contains/like operators work. (Groups, if the IdP emits them, arrive via
        // customClaims, which are merged into the context below.)
        ctx.principalAttrs.put("roles", joinValues(request.getAgentRoles()));
        ctx.principalAttrs.put("realmRoles", joinValues(request.getRealmRoles()));
        ctx.principalAttrs.put("clientRoles", joinValues(request.getClientRoles()));
        // Group memberships (Keycloak groups, normalized) — the set backs `principal in AgentGroup::"..."`,
        // and the space-joined attr backs `principal.groups.contains("...")` for attribute-style policies.
        ctx.principalAttrs.put("groups", joinValues(request.getAgentGroups()));
        if (request.getAgentGroups() != null) {
            ctx.principalGroups.addAll(request.getAgentGroups());
        }

        putIfNotNull(ctx.resourceAttrs, "name", request.getResourceName());
        putIfNotNull(ctx.resourceAttrs, "serverName", request.getServerName());
        putIfNotNull(ctx.resourceAttrs, "originalName", request.getOriginalName());
        putIfNotNull(ctx.resourceAttrs, "type", request.getResourceType());

        LocalDateTime now = LocalDateTime.now();
        ctx.contextAttrs.put("businessHours", isBusinessHours(now));
        ctx.contextAttrs.put("hour", (long) now.getHour());
        ctx.contextAttrs.put("minute", (long) now.getMinute());
        ctx.contextAttrs.put("dayOfWeek", now.getDayOfWeek().name());
        ctx.contextAttrs.put("month", now.getMonth().name());
        ctx.contextAttrs.put("year", (long) now.getYear());

        putIfNotNull(ctx.contextAttrs, "sourceIp", request.getSourceIp());
        putIfNotNull(ctx.contextAttrs, "serverName", request.getServerName());
        putIfNotNull(ctx.contextAttrs, "resourceName", request.getResourceName());
        putIfNotNull(ctx.contextAttrs, "correlationId", request.getCorrelationId());
        ctx.contextAttrs.put("argumentsFlat", flattenArguments(request.getArguments()));

        // Delegation lineage (act_chain) — exposed as flat context attributes so policies can gate on it,
        // e.g. context.rootVerified == true, context.rootType == "human", context.actChainDepth <= 2.
        java.util.List<java.util.Map<String, Object>> actChain = request.getActChain();
        if (actChain != null && !actChain.isEmpty()) {
            ctx.contextAttrs.put("actChainDepth", (long) actChain.size());
            java.util.Map<String, Object> root = actChain.get(0);
            java.util.Map<String, Object> actor = actChain.get(actChain.size() - 1);
            if (root.get("type") != null) ctx.contextAttrs.put("rootType", String.valueOf(root.get("type")));
            if (root.get("id") != null) ctx.contextAttrs.put("rootId", String.valueOf(root.get("id")));
            ctx.contextAttrs.put("rootVerified", Boolean.TRUE.equals(root.get("verified")));
            if (actor.get("type") != null) ctx.contextAttrs.put("actorType", String.valueOf(actor.get("type")));
            if (actor.get("id") != null) ctx.contextAttrs.put("actorId", String.valueOf(actor.get("id")));
            ctx.contextAttrs.put("actorVerified", Boolean.TRUE.equals(actor.get("verified")));
        }

        if (request.getCustomAttributes() != null) {
            ctx.contextAttrs.putAll(request.getCustomAttributes());
        }

        return ctx;
    }

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

    /** Space-joins a list into one string for the substring-based contains/like operators; "" when empty. */
    private static String joinValues(java.util.List<String> values) {
        return (values == null || values.isEmpty()) ? "" : String.join(" ", values);
    }

    private boolean matches(ParsedPolicy policy, EvalContext ctx) {
        if (policy.principalType != null && !policy.principalType.equalsIgnoreCase(ctx.principalType)) {
            return false;
        }
        if (policy.principalEntityId != null && !policy.principalEntityId.equals(ctx.principalId)) {
            return false;
        }

        if (policy.actionId != null && !policy.actionId.equalsIgnoreCase(ctx.action)) {
            return false;
        }
        if (policy.actionIds != null && !policy.actionIds.isEmpty()
                && policy.actionIds.stream().noneMatch(a -> a.equalsIgnoreCase(ctx.action))) {
            return false;
        }

        if (policy.resourceType != null && !policy.resourceType.equalsIgnoreCase(ctx.resourceType)) {
            return false;
        }
        if (policy.resourceEntityId != null && !policy.resourceEntityId.equals(ctx.resourceId)) {
            return false;
        }
        if (policy.resourceEntityIds != null && !policy.resourceEntityIds.isEmpty()
                && policy.resourceEntityIds.stream().noneMatch(r -> r.equals(ctx.resourceId))) {
            return false;
        }

        for (Condition cond : policy.whenConditions) {
            if (!evaluateCondition(cond, ctx)) {
                return false;
            }
        }

        for (Condition cond : policy.unlessConditions) {
            if (evaluateCondition(cond, ctx)) {
                return false;
            }
        }

        return true;
    }

    private boolean matchesIgnoringUnless(ParsedPolicy policy, EvalContext ctx) {
        if (policy.principalType != null && !policy.principalType.equalsIgnoreCase(ctx.principalType)) return false;
        if (policy.principalEntityId != null && !policy.principalEntityId.equals(ctx.principalId)) return false;
        if (policy.actionId != null && !policy.actionId.equalsIgnoreCase(ctx.action)) return false;
        if (policy.actionIds != null && !policy.actionIds.isEmpty()
                && policy.actionIds.stream().noneMatch(a -> a.equalsIgnoreCase(ctx.action))) return false;
        if (policy.resourceType != null && !policy.resourceType.equalsIgnoreCase(ctx.resourceType)) return false;
        if (policy.resourceEntityId != null && !policy.resourceEntityId.equals(ctx.resourceId)) return false;
        if (policy.resourceEntityIds != null && !policy.resourceEntityIds.isEmpty()
                && policy.resourceEntityIds.stream().noneMatch(r -> r.equals(ctx.resourceId))) return false;
        for (Condition cond : policy.whenConditions) {
            if (!evaluateCondition(cond, ctx)) return false;
        }
        return true;
    }

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

    public Map<String, String> extractPolicyReferences(String policyText) {
        Map<String, String> refs = new LinkedHashMap<>();
        if (policyText == null || policyText.isBlank()) return refs;

        String clean = policyText.replaceAll("//[^\n]*", "").trim();

        Matcher m = PRINCIPAL_EQ.matcher(clean);
        if (m.find()) {
            refs.put("agentName", m.group(1));
        }

        m = RESOURCE_EQ.matcher(clean);
        if (m.find()) {
            refs.put("capabilityType", m.group(1));
            refs.put("capabilityName", m.group(2));
        }

        m = ACTION_EQ.matcher(clean);
        if (m.find()) {
            refs.put("actionName", m.group(1));
        }

        m = RESOURCE_IN_SERVER.matcher(clean);
        if (m.find()) {
            refs.put("serverName", m.group(1));
        }

        if (!refs.containsKey("serverName") && refs.containsKey("capabilityName")) {
            String cap = refs.get("capabilityName");
            int sep = cap.indexOf('_');
            if (sep > 0) {
                refs.put("serverName", cap.substring(0, sep));
            }
        }

        return refs;
    }

    private boolean evaluateCondition(Condition cond, EvalContext ctx) {

        if (cond.operator == Condition.Operator.IN_GROUP) {
            // principal in AgentGroup::"<name>" — true iff the caller is a member of that group.
            return ctx.principalGroups.contains(cond.field);
        }

        if (cond.operator == Condition.Operator.IN_SERVER) {
            Object server = ctx.resourceAttrs.get("serverName");
            return server != null && cond.field.equals(String.valueOf(server));
        }

        if (cond.operator == Condition.Operator.EQ_ENTITY) {
            if (cond.source == Condition.Source.PRINCIPAL) {
                return cond.field.equals(ctx.principalId);
            }
            if (cond.source == Condition.Source.RESOURCE) {
                boolean typeMatch = cond.entityType == null || cond.entityType.equals(ctx.resourceType);
                return typeMatch && cond.field.equals(ctx.resourceId);
            }
            return false;
        }

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
            default -> false;
        };
    }

    private Map<String, Object> resolveAttrMap(Condition.Source source, EvalContext ctx) {
        return switch (source) {
            case PRINCIPAL -> ctx.principalAttrs;
            case RESOURCE -> ctx.resourceAttrs;
            case CONTEXT -> ctx.contextAttrs;
        };
    }

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
 log.debug("Cedar: Wildcard pattern match failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean isBusinessHours(LocalDateTime dateTime) {
        DayOfWeek day = dateTime.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = dateTime.toLocalTime();
        return !time.isBefore(LocalTime.of(8, 0)) && time.isBefore(LocalTime.of(18, 0));
    }
}
