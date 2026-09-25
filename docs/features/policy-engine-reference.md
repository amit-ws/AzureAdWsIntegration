# WhiteSwan Agentic Gateway — Policy Engine Reference

A short reference to how the gateway's authorization policies work and how granular they can be. Every
protected call (tool, prompt, resource, or agent-to-agent skill) is evaluated against these policies
**before** it executes.

## The model

- **Cedar-based**, default-deny. If no policy explicitly permits a request, it is denied.
- **Per-call evaluation** — every governed hop is checked at runtime, not just at connect time.
- **Per-tenant isolation** — one tenant's policies can never decide another tenant's request.
- **Two authoring paths** — write Cedar directly, or describe the control in plain English and the
  built-in assistant drafts the Cedar (grounded in the tenant's real tools/servers so it can't reference
  things that don't exist). Nothing activates without admin review.

## Policy shape

```cedar
@id("policy-name")
permit(   // or forbid — a matching forbid always wins
    principal <constraint>,
    action   <constraint>,
    resource <constraint>
)
when   { <conditions, all AND-ed> }
unless { <conditions> };   // optional
```

A single `when` block is AND-only; express OR by writing **separate permit rules** (the policy set is the
OR). A matching `forbid` short-circuits to DENY.

## What a policy can gate on

| Dimension | Attributes / forms | Example |
|---|---|---|
| **Agent (principal)** | `principal in AgentGroup::"<group>"` (real IdP group), `principal.roles.contains("<role>")`, `principal.realmRoles`, `principal.clientRoles`, `principal.approvalStatus`, `principal.version`, `principal == Agent::"<name>"` | `principal in AgentGroup::"finance"` |
| **Action** | `action`, `action == Action::"toolCall"`, `action in [Action::"toolCall", Action::"promptGet"]` — actions: `toolCall`, `promptGet`, `resourceRead` (agent-to-agent skill calls are governed by the same model) | `action == Action::"toolCall"` |
| **Resource** | `resource == Tool::"<name>"` (or `Prompt::`/`Resource::`), `resource in Server::"<server>"`, `resource in [Tool::"a", Tool::"b"]`, `resource.type`, `resource.name`, `resource.serverName` | `resource in Server::"market-data"` |
| **Delegation chain** | `context.rootType` (`"human"` / `"nhi"` / `"agent"`), `context.rootVerified` (bool), `context.rootId`, `context.actorType`, `context.actorId`, `context.actorVerified`, `context.actChainDepth` | `context.rootType == "human" && context.rootVerified == true` |
| **Time** | `context.businessHours` (bool), `context.hour` (0–23), `context.minute`, `context.dayOfWeek` (`MONDAY`…), `context.month`, `context.year` | `context.businessHours == true` |
| **Request** | `context.sourceIp`, `context.serverName`, `context.resourceName`, `context.argumentsFlat` (the request arguments) | `context.argumentsFlat like "*admin*"` |
| **Custom** | any attribute fed into the request context — `context.<name>` (e.g. a risk score) | `context.riskScore <= 70` |

**Operators:** `==`, `!=`, `like "…"` (with `*` wildcard), `.contains("…")`, `<=`, `>=`, `<`, `>`,
`in AgentGroup::"…"`, `in Server::"…"`, `in [ … ]`.

## Examples — simple to granular

**1. Simple — a group of agents may use a server's tools:**
```cedar
@id("financial-agents-alphavantage")
permit(principal in AgentGroup::"financial-agents", action == Action::"toolCall", resource in Server::"alphavantage");
```

**2. Moderate — only on behalf of a verified human, in business hours:**
```cedar
@id("financial-human-hours")
permit(principal in AgentGroup::"financial-agents", action == Action::"toolCall", resource in Server::"alphavantage")
when {
    context.rootType == "human" && context.rootVerified == true
    && context.businessHours == true
};
```

**3. Granular — explicit tool + skill allow-lists (replacing wildcards) for a verified-human desk:**

A wildcard like `resource.name like "alphavantage_*"` grants *every* Alpha Vantage function (~50 of them).
Pin it to only the tools the workflow uses, on behalf of a verified human — the granularity is in the
explicit allow-list, not in extra gates:
```cedar
// PURPOSE: a financial-desk agent may pull ONLY the five market-analysis tools this workflow
// uses — live quote, price history, earnings, balance sheet, news — and only when a verified human is
// behind the call. Anything else (any other Alpha Vantage function, any other server) is denied by default.
@id("financial-agents-analysis-tools")
permit(
    principal in AgentGroup::"financial-agents",           // WHO:  the calling agent is on the financial desk
    action == Action::"toolCall",                          // WHAT: it's a tool call (not a skill/prompt/resource)
    resource in [                                          // WHICH: only these five market-analysis tools
        Tool::"alphavantage_GLOBAL_QUOTE",
        Tool::"alphavantage_TIME_SERIES_DAILY",
        Tool::"alphavantage_EARNINGS",
        Tool::"alphavantage_BALANCE_SHEET",
        Tool::"alphavantage_NEWS_SENTIMENT"
    ]
)
when {
    context.rootType == "human"                            // WHO'S BEHIND IT: a human, not an autonomous machine
    && context.rootVerified == true                        //   ...cryptographically verified (the delegating human)
};
```
Same idea for the agent-to-agent skills — an explicit desk allow-list plus the same identity gates:
```cedar
// PURPOSE: a financial-desk agent may invoke ONLY the four research-desk skills (the advisor
// and its three specialists) — not any other skill — and only on behalf of a verified human.
@id("financial-agents-desk-skills")
permit(
    principal in AgentGroup::"financial-agents",           // WHO:   the calling agent is on the financial desk
    action,                                                // WHAT:  any action (the skill allow-list below scopes it)
    resource in [                                          // WHICH: only these four research-desk skills
        Skill::"advisor.analyze",
        Skill::"market-data.quote",
        Skill::"fundamentals.earnings",
        Skill::"news.sentiment"
    ]
)
when {
    context.rootType == "human"                            // WHO'S BEHIND IT: a human, not an autonomous machine
    && context.rootVerified == true                        //   ...cryptographically verified (the delegating human)
};
```

**Tighter still — when the environment supplies the signals.** Two more gates give a stricter posture:
`context.actorVerified == true` (the *calling agent on this hop* proved its own identity, not just the human
behind it) and `principal.approvalStatus == "APPROVED"` (an admin has approved the agent). These are real
least-privilege, but they only hold when **every hop carries a verified agent assertion** and the agents are
approved — turn them on once the deployment emits those signals end-to-end. This is the thesis in practice:
the engine already supports the gate; how tight you go is set by the identity each request actually carries.

**4. Guardrail (forbid) — block a tool for autonomous (non-human) actions:**
```cedar
@id("no-autonomous-github")
forbid(principal in AgentGroup::"financial-agents", action, resource in Server::"github")
when { context.rootType == "nhi" }
unless { context.rootType == "human" && context.rootVerified == true };
```

## How granular can it get?

The four dimensions — **who** (agent identity / roles / group), **what** (action + specific resource),
**who originated it** (the verified delegation chain), and **conditions** (time, IP, arguments, custom
risk) — compose freely, so a policy can be as coarse as one line or as tight as a single tool for a
single role, during business hours, on behalf of a verified human, through a bounded chain.

The important point: **the engine is not the limit on granularity — the data is.** Every dimension above
is already supported; how tight a policy can be is set by how much identity and context each request
carries. So as an environment's data gets richer — more roles and groups from the IdP, risk signals,
custom business attributes — even tighter controls drop in with no engine change. The composition is
already there.

- Because it is **default-deny**, adding scope makes a policy *stricter*, never accidentally broader.
- Note: there is no `"unknown"` root type — an unresolved root is emitted as `rootType="human"` with
  `rootVerified=false`, so "a real human" is `rootVerified == true && rootType == "human"`.
