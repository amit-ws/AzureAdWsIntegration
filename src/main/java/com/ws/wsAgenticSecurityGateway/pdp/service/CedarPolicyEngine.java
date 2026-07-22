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

    private static final Pattern RESOURCE_EQ = Pattern.compile("resource\\s*==\\s*(Tool|Prompt|Resource)::\"([^\"]+)\"");

    private static final Pattern PRINCIPAL_IN_GROUP = Pattern.compile("principal\\s+in\\s+AgentGroup::\"([^\"]+)\"");

    private static final Pattern RESOURCE_IN_SERVER = Pattern.compile("resource\\s+in\\s+Server::\"([^\"]+)\"");

    private static final Pattern COND_PRINCIPAL_EQ = Pattern.compile("principal\\s*==\\s*Agent::\"([^\"]+)\"");

    private static final Pattern COND_RESOURCE_EQ = Pattern.compile("resource\\s*==\\s*(Tool|Prompt|Resource)::\"([^\"]+)\"");

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

    private volatile List<ParsedPolicy> currentPolicies = Collections.emptyList();

    private final ReadWriteLock policyLock = new ReentrantReadWriteLock();

    private volatile boolean policiesLoaded = false;

    public CedarPolicyEngine() {
 log.info("Cedar Policy Engine initialized (pure-Java evaluator — dynamic ABAC)");
    }

    public int reloadPolicies(List<GatewayPolicyEntity> policies) {
        if (policies == null || policies.isEmpty()) {
            policyLock.writeLock().lock();
            try {
                currentPolicies = Collections.emptyList();
                policiesLoaded = false;
 log.info("Cedar: No policies to load — all requests will be DENIED (default-deny, no permits)");
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
 log.info("Cedar PARSED: name='{}', id='{}', effect={}, principalType={}, principalId='{}', actionId='{}', resourceType={}, resourceId='{}', whenConds={}, unlessConds={}",
                            pp.name, pp.id, pp.effect, pp.principalType, pp.principalEntityId,
                            pp.actionId, pp.resourceType, pp.resourceEntityId,
                            pp.whenConditions.size(), pp.unlessConditions.size());
                } else {
 log.warn("Cedar: Skipped unparseable policy: {} | text='{}'",
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

 log.info("Cedar: Loaded {} policies successfully (parsed {} of {})",
                    parsed.size(), parsed.size(), policies.size());
            return parsed.size();

        } catch (Exception e) {
 log.error("Cedar: Failed to parse policies: {}", e.getMessage(), e);
            return -1;
        }
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

    public boolean hasPolicies() {
        return policiesLoaded;
    }

    public PolicyEvaluationResult evaluate(PolicyEvaluationRequest request) {
        long startTime = System.currentTimeMillis();

        if (!policiesLoaded || currentPolicies.isEmpty()) {
 log.info("Cedar: agent={}, action={}, resource={} → DENY (no policies configured)",
                    request.getAgentName(), request.getAction(), request.getResourceName());
            long duration = System.currentTimeMillis() - startTime;
            return PolicyEvaluationResult.noPolicies(duration);
        }

        policyLock.readLock().lock();
        try {
            EvalContext ctx = buildEvalContext(request);

 log.info("Cedar EVAL: principalType='{}', principalId='{}', action='{}', resourceType='{}', resourceId='{}', policiesCount={}",
                    ctx.principalType, ctx.principalId, ctx.action, ctx.resourceType, ctx.resourceId, currentPolicies.size());

            Set<String> matchedPermitPolicies = new LinkedHashSet<>();
            Set<String> matchedForbidPolicies = new LinkedHashSet<>();

            for (ParsedPolicy policy : currentPolicies) {
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
 log.error("Cedar evaluation error: {}", e.getMessage(), e);

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
        if (resourceEqMatch.find()) {
            pp.resourceType = resourceEqMatch.group(1);
            pp.resourceEntityId = resourceEqMatch.group(2);
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
            Object status = ctx.principalAttrs.get("approvalStatus");
            return status != null && cond.field.equals(String.valueOf(status));
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
