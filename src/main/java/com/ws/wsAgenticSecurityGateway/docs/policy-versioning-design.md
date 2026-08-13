# Policy Versioning — design sketch (future feature)

**Status:** proposed / not built. Captured while building CISO Stage 8 (point-in-time), which today reconstructs
*observed* governance from the decision ledger because declared-policy history is not retained.

## Problem

`gateway_policy` rows are edited **in place**. On every change the previous `policy_text` (and effect/enabled/
scope) is overwritten. We keep only `created_at` and `updated_at` (last change). So we **cannot** answer:

- *"What was the exact enabled policy set — and the exact text of policy P — on 2026-07-28?"*
- *"What changed in policy P between two dates, and who changed it?"*
- *"Roll policy P back to how it read last Tuesday."*

The decision ledger (`pdp_audit_log`) tells us what was **enforced/observed**; it does not tell us what was
**declared** but never exercised. Versioning closes that gap.

## Goal

Retain the **full, immutable history of every policy change** so the declared policy state as of any past instant
is exactly reconstructable, with change accountability (who/when/what) and diff/rollback.

## Approach — append-only version table

On every create / update / enable / disable / delete of a `gateway_policy`, write one immutable snapshot row.

**`gateway_policy_version`** (sketch):

| column | notes |
|---|---|
| `id` (uuid, pk) | version row id |
| `policy_id` (uuid) | FK → the logical `gateway_policy.id` |
| `cedar_policy_id` | stable business id |
| `version_no` (int) | monotonic per policy (1,2,3…) |
| `change_type` | CREATE / UPDATE / ENABLE / DISABLE / DELETE |
| `effect, enabled, policy_text, description, priority, source, principal_kind, principal_id, tags` | **full snapshot** of the versioned fields at this version |
| `changed_by` | the authenticated admin/human who made the change |
| `valid_from` (timestamp) | when this version became effective |
| `valid_to` (timestamp, null = current) | when it was superseded — set on the next change |
| `ws_tenant_name` | tenant scope |

Point-in-time query becomes: *the version of each policy where `valid_from <= T AND (valid_to IS NULL OR valid_to > T)`*.

## Write path

Hook into `PolicyService` create/update/toggle/delete: after persisting the live policy, insert the version row
(set the prior current version's `valid_to`). `change_type` + `changed_by` are captured there.

**Option — Hibernate Envers:** annotating the entity `@Audited` gives revision history + a revisions table almost
for free (mature, well-trodden). Trade-off: less control over `change_type`/`changed_by` semantics and query shape
than a purpose-built table. Recommend the hand-rolled table for the explicit change metadata; Envers if speed matters.

## What it unlocks

- **True config point-in-time** — exact enabled policy set + text as of date T (complements Stage 8's observed view).
- **Policy diff** — version N vs M: what text/scope/effect changed.
- **Change accountability** — who changed which policy when (SOC 2 / EU AI Act love this; feeds Stage 4 compliance).
- **Rollback** — restore a prior version as the new current.

## How it complements Stage 8 (now)

| Question | Answered by |
|---|---|
| What did the gateway **actually enforce/observe** on date T? | Decision ledger (Stage 8, available now) |
| What was the exact **declared policy config** on date T? | **This feature** (not yet built) |

Together = complete point-in-time (declared **and** observed). Until versioning ships, Stage 8 is observed-only and
says so in its response.

## Caveats / effort

- Adds one table + write-path hooks in `PolicyService` (a mutation surface — small, tenant-scoped, admin-only).
- **Cannot recover history that predates turn-on** — it starts capturing from the day it ships. (This is exactly
  why the lost pre-existing history can only be approximated from the ledger today.)
- Local dev: drop/recreate is fine; no data migration needed.
