# CISO Dashboard — Stage 0 Data Contracts (verified)

Every number a CISO API shows must map to a **real column + a verified derivation**. This is the
output of Stage 0: what's proven present in the data, and the exact source/filter each later stage
must use. Verified against the live `ws_agentic_security` schema (dev).

## Verified facts (locked)

- **Decision values are `ALLOW` / `DENY`** — on `pdp_audit_log` rows where `event_type =
  'PDP_DECISION_RENDERED'` (373 ALLOW, 19 DENY). `PDP_EVALUATION_REQUESTED` rows carry **no** decision.
  ⚠️ **`PERMIT`/`FORBID` is the policy *authoring* vocabulary** (`gateway_policy.effect`) — never use it
  to filter runtime decisions. Contract: `WHERE event_type='PDP_DECISION_RENDERED' AND pdp_decision IN ('ALLOW','DENY')`.
- **`pdp_audit_log` is the activity goldmine:** `pdp_subject`, `pdp_action`, `pdp_resource` are **100%
  populated** (784/784). Clean `subject → action → resource → decision`, no Cedar parsing.
  Actions seen: `toolCall`, `skillInvocation`.
- **`gateway_audit_log`:** 1,866 rows / 28 event types; `agent_name` 95%, `human_user_id` 90%,
  `correlation_id` 55%, **`trace_id` only ~9%** (present on traced-request rows, absent on
  auth/session/config events).

## Per-stage contracts (Track 1)

| Stage | Real source + derivation | Gap to build in-stage |
|---|---|---|
| **1 — Policy read-model** | `gateway_policy`: source = `principal_id`/`principal_kind` (13/20, 20/20); target = `resource == X::"name"` inside Cedar `policy_text` (**10/20 specific, 10 ANY/broad**); `effect` (PERMIT/FORBID); `enabled` (3/20) | Add derived `resource_kind` / `resource_id` (parse `resource ==`; mark broad rules `ANY`). Normalize nothing on decisions — they're already `ALLOW`/`DENY` in the ledger. |
| **2 — Policy Activity** | `pdp_audit_log` (`PDP_DECISION_RENDERED`, ALLOW/DENY) joined to `gateway_policy` on `pdp_policy_id`. **113/392 decisions carry a `pdp_policy_id`; 8 of 20 policies ever fired → 12 dead; 279 decisions unattributed** (a real coverage insight, not a bug) | none (pure read) |
| **3 — Blast-Radius** | **Potential reach** = `gateway_policy` principal → resource (Stage-1 read-model). **Actual reach** = `pdp_audit_log` `pdp_subject → pdp_resource` (100% clean). Overlay `agent_capability_profile_rule` (is the capability even exposed). Color enabled vs latent (disabled). Handle `ANY`/global forbid. | depends on Stage 1 read-model |
| **4 — SOC 2 export** | `gateway_audit_log` (access events, 90% human-attributed) + `pdp_audit_log` (decisions) + OBO receipts + identities. Map to CC-series controls. | report templating only |
| **5 — Agent Passport** | **Primary = `pdp_audit_log`** (`pdp_subject` = the agent, 100%). Outbound = subject is the agent; inbound ("done in its name") = agent appears as a non-leaf **actor in the OBO `act_chain`** (STS_TOKEN_MINTED `response_payload`). Timeline from `gateway_audit_log`. | ⚠️ **stitch by `correlation_id` (55%) as the backbone; `trace_id` is the umbrella but only ~9% coverage — do NOT rely on it alone.** act_chain parsing for the inbound direction. |
| **6 — Ownership + Human Accountability** | Human accountability = `gateway_audit_log.human_user_id` (90%) + identities. Orphan = agent with no owner. | **`gateway_agent` has NO owner/team/purpose/lifecycle columns — must ADD them.** Owner is net-new data (seed via UI/API). |
| **7 — Posture** | Aggregates of Stages 2–6 (dead policies, denies, orphans, latent reach). Every tile traces to a Stage-2..6 source. | none (composition) |
| **8 — Point-in-time** | *"What could X access / what policies were in effect on date Y."* | **No history/snapshot/version table exists** — only `updated_at` (last change). Must BUILD a config-snapshot/versioning mechanism; without it, point-in-time is not truthfully answerable. |

## Track 2 (gated on the post-processor)

| Stage | Source | Status |
|---|---|---|
| 9 — Post-processor V1 | new `data_tag` / `data_tag_rule` / `capability_data_fingerprint` | writes the data |
| 10 — Sensitive-Data Exposure | `data_tag` ledger | **no data until Stage 9 runs — cards stay empty, never mocked** |
| 11 — GDPR / EU AI Act export | `data_tag` + audit | gated on Stage 9 |

## Honest gaps this pass surfaced (net-new data model)

1. **Stage 1** — `resource_kind`/`resource_id` read-model on `gateway_policy` (target lives in Cedar text).
2. **Stage 6** — owner/team/purpose/lifecycle on `gateway_agent` (doesn't exist).
3. **Stage 8** — config-snapshot/versioning (no history table exists).
4. **Track 2** — the `data_tag` tables (post-processor).

Everything else (Policy Activity, Blast-Radius actual-reach, SOC 2, Passport, Human Accountability,
Posture) is backed by **existing, verified real data** — buildable now.
