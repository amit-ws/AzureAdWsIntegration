# Why a hop can show a decision but **no token** (the "empty box")

**Audience:** operators, auditors, customers looking at the governance trail / delegation chain.

## Background

Every request through the WhiteSwan Agentic Auth Gateway becomes a **delegation trail** — a
series of **hops**, one per step (human → console → advisor → specialist → tool). A healthy hop
mints a short-lived **OBO (on-behalf-of) token** and shows its details: *actor, target, scope,
TTL, jti, act_chain*.

## The key: the order of operations in a hop

Each hop runs this pipeline (`HopOrchestrator`):

```
capability check → registry lookup → PDP decision → CONNECTIVITY GATE → MINT OBO → broker the call → result
```

The **mint is near the end**. Two gates sit *before* it:

1. **PDP** — allow / deny (policy).
2. **Connectivity gate** — is the target server actually connected?

If either gate stops the request, **the mint never runs → no token → the hop box is empty.**
The box still shows *ALLOW* or *DENY* (that comes from the PDP event, which happened); the token
details are blank because no token was created.

## An empty box has exactly two causes

| Empty box shows | What happened | Why no token |
|---|---|---|
| **DENY** | The PDP blocked the call by policy | By design — the gateway never issues a delegation credential for a call the policy forbids. |
| **ALLOW** | The PDP allowed it, but the **downstream server was not connected / unavailable** (error `-33002`, `SERVER_UNAVAILABLE`) | The call was rejected at the connectivity gate, *before* the mint. It was authorized but **undeliverable**, so nothing was delegated. |

## Why this is correct — not a gap

The gateway is **fail-closed**: it mints a scoped, expiring delegation token **only** when it is
actually going to deliver the call to a reachable target. It never issues a credential for a call
that was **denied** or **couldn't be delivered**. That is a security property — no token is ever
created for a call that didn't happen, so there is nothing to leak, replay, or over-scope.

## Precise meaning of "empty"

"Empty" means **"rejected before the mint step."** The moment a token **is** minted, the hop always
carries the token **and** an outcome — even a downstream failure like a rate-limit shows up as a
*populated* hop, not an empty one.

> **Empty ⇒ pre-mint rejection, always.**

## In the dashboard

Each empty hop now spells out the exact cause on the **View Trace** card:

- *"No token minted — the PDP denied this call (policy …), so the gateway never issued a delegation token."*
- *"No token minted — Server 'X' is not connected — rejected at the connectivity gate before the mint step, so no token was issued."*

So an operator (or auditor) can tell at a glance whether an empty hop was **policy** (intended) or
**connectivity** (fix the connection and re-run).
