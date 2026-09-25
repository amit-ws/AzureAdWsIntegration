# Stage 2 — Enforce & Govern (single-hop MCP)

**Goal:** turn the act_chain lineage that Stage 1 *records* into lineage the gateway *enforces*, and close the
request-time governance gaps — so single-hop MCP is not just audited but governed.

**Prereq done:** Stage 1 (STS keys + JWKS, act_chain, JIT scope, fail-closed OBO mint, act_chain → Cedar
context + audit). Verified live 2026-07-22.

---

## Locked decisions

- **LD-1 — PDP fails closed.** A crash in `CedarPolicyEngine.evaluate()` now returns **DENY** (was ALLOW),
  with a clear `reason`/`diagnostics`. A broken gate must never become a bypass. *(Done —
  `CedarPolicyEngine.java` catch block.)*
- **LD-2 — Every decision carries a reason.** Already true across all `PolicyEvaluationResult` paths; the
  fail-closed path now does too, and lands in `pdp_reason`.
- **LD-3 — Lineage guardrails use only attributes that already flow.** Ship `deny-unverified-root` and
  `deny-unverified-actor` (expressible today via `context.rootVerified` / `context.actorVerified`). The
  "require human root for **writes**" rule is **deferred** — it needs a per-tool read/write signal that does
  not exist yet (`CapabilityDescriptor` has no annotations; MCP `readOnlyHint`/`destructiveHint` aren't
  captured). See 2a.3.
- **LD-4 — Activate defaults by seed-on-startup.** An idempotent seeder upserts the baseline lineage policies
  per tenant (`source=DEFAULT`), so the guardrail exists by default rather than depending on an admin. Uses
  the existing `(policy_name, ws_tenant_name)` uniqueness for idempotency.
- **LD-5 — Governance = deprovision lifecycle + defense-in-depth.** BLOCKED/PENDING agents are *already*
  refused at request time by `HttpMcpAuditFilter`. Stage 2 adds the missing **DEPROVISIONED** lifecycle
  (the `status` field is write-once `ACTIVE` today) + a hot-path read of it, **and** a governance pre-check
  in the orchestrator so enforcement doesn't depend solely on the servlet filter being in the path.

## Known limitations (documented, not fixed in 2a)

- **Cedar engine is a single global policy list, not tenant-partitioned** (`CedarPolicyEngine.currentPolicies`).
  Multi-tenant policy bleed + last-writer-wins reload pre-exist Stage 2. Correct for a single-tenant demo;
  candidate for a dedicated fix (make the engine `Map<tenant, policies>` or reload-per-tenant before eval).
- **Enforcement state is per-JVM / non-distributed** (`blockedSessionIds`, `agentStatusById`,
  `BlockedSessionEvent`). Multi-replica cross-instance revocation is a 2.5/ops concern.
- **New tenants** get seeded defaults on next restart (no tenant-creation hook into the pdp module yet).

---

## Milestones

### 2a.1 — Lineage gating (Cedar)  ✅ done (green: CedarPolicyEngineTest + PolicyServiceTest)
- **Prove:** `CedarPolicyEngineTest` — `deny-unverified-root` / `deny-unverified-actor` forbids gate correctly
  (unverified → DENY, verified → permit passes; absent lineage documented).
- **Activate:** `PolicyService.seedDefaultLineagePolicies(tenant)` + a startup seeder + repo
  `findDistinctWsTenantName()`; idempotent upsert, `source=DEFAULT`, `enabled=true`, then `reloadEngine`.
- **Prove seeder:** `PolicyServiceTest` — upsert once, skip when already present.

### 2a.2 — Governance enforcement  ✅ done (green: AgentRegistryServiceTest)
- **Deprovision lifecycle:** `AgentRegistryService.deprovisionAgent(id)` sets `status=DEPROVISIONED` + a
  by-id lifecycle-status cache; `getAgentLifecycleStatusForSession(sessionId)` + repo
  `findAgentLifecycleStatusBySessionId`.
- **Filter enforcement:** refuse `DEPROVISIONED` (all methods) in the agent gate of `HttpMcpAuditFilter`.
- **Defense-in-depth:** governance pre-check in `HopOrchestrator` (refuse blocked/deprovisioned before
  PDP/mint), covering any path that bypasses the servlet filter.
- **Prove:** unit tests for the new lifecycle + a characterization test for the deny paths.

### 2a.3 — (deferred) write-scoped lineage
- Capture MCP tool annotations (`readOnlyHint`/`destructiveHint`) → `CapabilityDescriptor` →
  `PolicyEvaluationRequest.operationType` → Cedar `resource.operationType`, enabling
  `forbid ... when { resource.operationType == "write" && context.rootType != "human" }`.

### 2b — Dashboard V1
- Read/action APIs in this gateway (over existing audit/registry/policy data) + demo pages in
  `ws-gateway-dashboard`. No new frontend app.

### 2.5 — Hardening tail
- STS key rotation lifecycle, OBO revocation check, policy-authoring UX, distributed enforcement state,
  tenant-partitioned policy engine.

---

## Workflow
Code + verify to green (build + tests); **the user reviews and commits** — no commits from the agent. Do not
touch the plaintext secrets in `application.yml`.
