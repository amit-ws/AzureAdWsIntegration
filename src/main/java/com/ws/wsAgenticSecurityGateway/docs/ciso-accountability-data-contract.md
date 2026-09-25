# CISO Stage 6 — Human Accountability (data contract)

**What it answers:** *For every agent, which verified human is answerable for what it did — and where nobody is?*
Derived entirely from the **OBO delegation lineage (`actChain`)** the PDP already records on every governed
evaluation. No owner-of-record is invented — that is a future platform-sync concern. Read-only, tenant-scoped.

## Source of truth

`pdp_audit_log` rows where `event_type = 'PDP_EVALUATION_REQUESTED'` carry `pdp_context -> 'actChain'`: a JSON array
`[root, …hops, actingAgent]`.

- **root** = `actChain[0]` — a `{type:"human", id:<idp_subject>, username, verified}` for OBO calls.
- **acting agent** = `pdp_subject` (the chain's last hop).
- **depth** = `jsonb_array_length(actChain)`.

Enrichment: root `id` == `gateway_human_users.idp_subject` → full name / email / status.
Parsing reuses `sts/model/Principal.fromClaim(...)` (the same model the PDP builds the chain from).

## Rules (locked)

| Rule | Definition |
|---|---|
| **Accountable** | `root.type == HUMAN` **and** `root.verified == true`. |
| **Unrooted** | non-human root, or an unverified/unknown human root (`Principal.unknownRoot`). Excluded from answerable totals — this *is* the "no verified human" signal. |
| **DIRECT** | human drove the agent itself — chain depth ≤ 2 (`human → agent`). |
| **DELEGATED** | agent reached through other agents — chain depth ≥ 3 (`human → … → agent`). |
| **Invariant** | `direct + delegated + unrooted == governedRequests`. |

Owner-of-record (from platform sync at onboarding) is intentionally **not** shown — accountability already
resolves the answerable human. Policy ownership uses the real `gateway_policy.created_by`.

## Endpoints

```
GET /api/admin/ciso/accountability                 → AccountabilityReport (tenant roll-up)
GET /api/admin/ciso/accountability/agent?agentId=X → AgentAccountability  (per-agent detail)
```

- `AccountabilityReport`: `summary` (registered/acting agents, agentsWith/WithoutHumanRoot, distinctHumans,
  governed/direct/delegated/unrooted), `agents[]` (per acting agent), `humans[]` (reverse: agents a human drives,
  direct vs delegated), `policyOwnership` (`byOwner` from `created_by`, `unowned`), `notes`.
- `AgentAccountability`: counts + `accountableHumans[]` (enriched, with direct/delegated split), `primaryHuman`,
  `delegationPaths[]` (distinct `[human → … → agent]` lineages + how often), `notes`.

## Verified live numbers (tenant `amitdev.local`, 2026-08-13)

Every governed evaluation (392) carries a verified human root except the 3 `unknown` (unverified) → unrooted.

| Agent | governed | direct | delegated | max depth | root human |
|---|---|---|---|---|---|
| advisor | 136 | 0 | 136 | 3 | amit-prakash |
| claude-desktop | 73 | 73 | 0 | 2 | amit-prakash |
| market-data | 56 | 0 | 56 | 4 | amit-prakash |
| fundamentals | 41 | 0 | 41 | 4 | amit-prakash |
| news | 33 | 0 | 33 | 4 | amit-prakash |
| agent-console | 31 | 31 | 0 | 2 | amit-prakash |
| Anthropic/ClaudeAI | 9 | 9 | 0 | 2 | amit-prakash |
| billing | 7 | 0 | 7 | 3 | amit-prakash |
| stateless-terminal | 3 | 3 | 0 | 2 | amit-prakash |
| unknown | 3 | — | — | 1 | *(unverified → unrooted)* |

**Tenant totals:** governed **392**, direct **116**, delegated **273**, unrooted **3**, acting agents **10**,
distinct verified humans **1** (Amit Prakash / amit@ws.com). Policy owners (tenant-scoped to `amitdev.local`):
`admin` 16, `system` 2, unowned 0 (18 total; the 2 baseline lineage guardrails live under the `default` tenant).

**Live-verified end-to-end (HTTP 200):** both endpoints return exactly these numbers with `X-WS-Tenant: amitdev.local`.
`market-data` detail shows two distinct 4-hop lineages — `amit-prakash → agent-console → advisor → market-data` (34)
and `amit-prakash → claude-desktop → advisor → market-data` (22) — every request traced to the verified human.

## Test curls (after gateway restart)

```bash
curl -s "http://localhost:9492/api/admin/ciso/accountability" | jq '.summary, .humans'
curl -s "http://localhost:9492/api/admin/ciso/accountability/agent?agentId=market-data" | jq '.delegationPaths, .primaryHuman'
```

## Known limits / future

- **Owner-of-record** → future platform-sync/onboarding module (Korea-AI-style agent import). Not faked here.
- **Autonomous agents** (no human root by design) don't exist in the gateway yet; when they do, they surface as
  "unrooted" and will rely on the synced owner as their accountability anchor.
- Scan capped at 20k most-recent evaluations (far above current ledger size); summary notes if hit.
