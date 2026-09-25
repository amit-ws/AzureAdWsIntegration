# Missing Feature — Autonomous Multi-Agent NHI Governance

**Status:** Not built (deferred). Agent-side scaffolding exists (opt-in); the gateway side is the pending work.
**Why deferred:** it grew from a "3-line" tweak into a real feature touching identity rooting, NHI discovery,
and token classification. Captured here so it can be built carefully, not rushed.

## Objective

Run the multi-agent financial workflow **autonomously** (a machine trigger, no human), as a faithful mirror
of the human demo, such that:
1. Every action roots at **`rootType="nhi"`** — no human appears anywhere in the chain.
2. **Every agent's NHI is discovered** (registered PENDING → approve), not just the initiator's.
3. A Cedar policy governs it (`context.rootType == "nhi"`), which is the concrete answer to Netskope **Q7**
   ("distinguish agent-acting-for-a-human (OBO) from agent-acting-autonomously, and govern them differently").

## The gap (verified in code)

- **NHI discovery + NHI-rooting only happen on the `/mcp` plane.** `HttpMcpAuditFilter` (~:590-613) calls
  `AgentRegistryService.discoverNhi(...)` on `initialize` when the token is `AUTOMATED_AGENT`, binds `nhiId`
  to the session, and `ActChainBuilder` roots at `Principal.nhi(nhiId)` because `nhiId != null`.
- **The `/a2a` plane is session-less.** `ActChainBuilder.fromTransportContext` reads `nhiId` via
  `registry.getNhiIdForSession(sessionId)` (ActChainBuilder.java:79) → null on A2A → the NHI branch
  (`:91-92`) is skipped → an autonomous token drops to the **weak/inferred human** branch (`:93-94`,
  `Principal.human(..., verified=false)`). So an autonomous A2A hop **mislabels the service-account token
  as an unverified human** — wrong for autonomy.
- **Each agent's own NHI credential already rides on every hop as `X-Agent-Assertion`** (the RFC-8693 actor
  credential, verified by `AgentAssertionVerifier` → `VerifiedAgent(workloadId, roles, groups)`), but the
  gateway **never registers an NHI from it** — discovery only looks at the primary `Authorization` token.

## Two build options

### Option A — pragmatic (≈5 files, low risk)
Each agent presents its **own** NHI token as the primary `Authorization` (the opt-in agent change already
scaffolded), and the gateway gains **one** fix: on `/a2a`, discover the caller's NHI from its primary token
and form an NHI root (mirror `/mcp`).
- Files: `A2aInboundController` (discover NHI from primary token, mirror HttpMcpAuditFilter:590-613; handle
  BLOCKED), `A2aRequestContextFactory` (stamp the discovered `nhiId`), `RequestAttributeKeys` (add `NHI_ID`),
  `HopOrchestrator.identityContext` (lift `NHI_ID`), `ActChainBuilder` (prefer context `NHI_ID` over the
  session lookup for the root).
- ✅ Every agent's NHI discovered, every hop `rootType=nhi`, no human.
- ⚠️ Each action roots at **its own** NHI — no single propagated `initiator → advisor → market-data → tool`
  lineage (agents don't forward the OBO in this model).

### Option B — faithful chain (≈8 files, subtler)
Agents **forward the OBO** (lineage preserved), the gateway **discovers each agent's NHI from its verified
`X-Agent-Assertion`** on every hop (MCP + A2A), and the A2A NHI-root fix from Option A applies.
- Extra work vs A:
  - Extend `VerifiedAgent` to expose the assertion's `sub` + `iss` (needed by `discoverNhi(idpSubject,...)`).
  - Register the actor NHI from the assertion at both boundaries (or once in the spine after threading the
    verified assertion identity into `RequestContext`).
  - **Classification subtlety (must fix):** a gateway-minted OBO for an NHI-rooted chain carries an RFC-8693
    `act` claim, so `TokenClassificationService` classifies it `HUMAN_DELEGATED` (SIGNAL_2). On the `/mcp`
    leg that would make the existing primary-token discovery run `discoverHumanUser(...)` on the NHI root's
    `sub` — **mis-registering the root NHI as a human**. Classification (and/or the discovery gate) must key
    off the **act_chain root type**, not just the presence of an `act` claim.
- ✅ Full propagated lineage **and** every agent's NHI discovered — the true mirror of the human demo.
- ⚠️ Touches token classification; needs careful verification.

**Recommendation:** Option A for a demo (correct + low-risk). Option B is the ideal and a strong Netskope
capability ("we govern autonomous A2A chains, not just human OBO"), worth a dedicated, verified effort.

## Agent-side scaffolding (already in `a2a-sample-agents`, opt-in — off by default)

- `agent_identity.py` — `AGENT_AUTONOMOUS` switch + `primary_authorization()` (own NHI token as primary
  when autonomous). This is the **Option A** agent behavior; for Option B it would be reverted.
- `agent_brain.py`, `mcp_tools.py` — call `primary_authorization()` on each hop.
- `market_data.py`, `fundamentals.py` — declared a `news.sentiment` A2A edge (deeper DAG).
- `run_autonomous.py` — the machine trigger (mints a client-credentials token → POSTs `advisor.analyze`).
- `AUTONOMOUS_DEMO.md` — run guide + the honest caveats.
- KC: each agent already has a confidential service-account (client_credentials) client
  (`advisor`/`market-data`/`fundamentals`/`news`, secret `<name>-secret`, realm `ws-gateway` @ :8180),
  seeded by `seed-kc-agent-clients.sh`.

## Policy (Q7) — once the gateway side lands
```
@id("financial-agents-autonomous")
permit(principal in AgentGroup::"financial-agents", action, resource)
when { context.rootType == "nhi" && context.actChainDepth <= 5 };

@id("financial-agents-human-obo")
permit(principal in AgentGroup::"financial-agents", action, resource)
when { context.rootVerified == true && context.rootType == "human" };
```
Note: there is **no `"unknown"` root type** — an unresolved root is emitted as `rootType="human"` with
`rootVerified=false`, so "a real human" must check **both** `rootVerified == true && rootType == "human"`.

## Acceptance criteria
- Trigger `run_autonomous.py` with agents in autonomous mode → workflow runs end-to-end.
- Each agent's NHI appears PENDING in the registry; after approval the run is permitted.
- `gateway_audit_log`: `nhi_id` populated, `rootType=nhi` on every governed hop (Option B: also a single
  connected act_chain from the initiator through each agent to the tool).
- The `financial-agents-autonomous` policy governs the run; the human-OBO / forbid variants demonstrate Q7.
