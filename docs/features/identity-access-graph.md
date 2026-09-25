# Identity Access Graph — Feature Spec

**What it is:** the transformation of the current Identity Graph (an audit‑replay visualization)
into an **Identity Access Graph** — a single‑page tool that lets an admin see who *can* and *did*
reach what, spot the risk, and fix it in place.

**Status:** design approved · multi‑stage build · **read‑model only, no new data capture.**

---

## 1. Objective

Today the Identity Graph *replays* observed audit activity (who acted through whom, what they
invoked, allow/deny). The redesign makes it a **problem‑solver**: it overlays *entitlements*
(from policy) on *activity* (from audit), lights up risk, and drives remediation — all on one page.

## 2. The questions it answers for an admin

1. **Who can reach what?** — permitted reach *(Entitled — from policy)*
2. **Who actually accessed what, and when?** — real activity *(Observed — from audit, time‑windowed)*
3. **Where's the risk?** — the **Gap** between #1 and #2, plus sensitive reach, unrooted chains, probing
4. **What do I fix?** — one click to right‑size or revoke

Clean 1:1 with the model: **#1/#2 = the two overlays · #3 = the Gap · #4 = find→fix.**

## 3. Core model

- **Principals ("who"):** Human · NHI (workload) · Agent. *(Agents are also targets in A2A.)*
- **Targets ("what"):** agents‑as‑callees · MCP servers · capabilities (tool / resource / prompt) ·
  A2A skills · data (via sensitivity).
- **Two overlays over data you already have:**
  - **Observed** — from the audit log (existing `acts‑through` human→agent and `invoked`
    agent→tool edges; extend with agent→agent A2A).
  - **Entitled** — from Cedar policy: each principal's *permitted* reach, derived by evaluating
    policy against the resource catalog.
  - **Gap** — `Entitled − Observed` = over‑privilege; blocked `Observed` = probing.
- **Risk** — every node/edge carries a transparent risk band from: data sensitivity (DLP) +
  unrooted chain + deny‑rate + reach breadth/fan‑out.

## 4. Backend design (build on the existing `IdentityGraph` / `TraceGraph` read‑models)

- **Observed builder (exists):** keep the audit aggregations (`acts‑through`, `invoked` allow/deny);
  add agent→agent (A2A) edges.
- **Entitled builder (new):** per principal, compute permitted reach by evaluating Cedar policies
  against the resource catalog (`mcp_tool` + `mcp_resource` + `mcp_prompt` + A2A skills + agents),
  reusing the policy read‑model (principal_kind / principal_id, `agentId` filter).
  *→ the heaviest new piece: policy evaluation across (identity × resource).*
- **Risk scoring (new):** per node/edge — sensitivity from `gateway_response_classification`,
  unrooted (no human root on the act‑chain), deny‑rate from PDP rows, breadth/fan‑out. Explainable
  bands, same style as the CISO dashboard.
- **Blast‑radius (new):** for a selected node, the reachable set upstream/downstream over the
  chosen overlay.
- **Find→Fix (new):** each risk finding emits an action seed for the policy assistant (reuse the
  CISO priority‑action `assistantSeed` / deep‑link pattern).
- **API:** extend `/audit/graph` with `mode=observed|entitled|gap` + filters (identity type,
  since/hours, sensitivity, risk) + a node blast‑radius call. DAG drill‑down reuses the existing
  per‑trace `TraceGraph`.

## 5. Frontend design (one page — extend the existing React‑Flow graph)

- **Overlay toggle:** Observed / Entitled / Gap
- **Filters:** identity type (Human / NHI / Agent) · time (observed side) · sensitivity · risk band
- **Risk heatmap:** node/edge color by risk; badges on flagged problems (over‑privilege, sensitive
  reach, unrooted, probing)
- **Click = blast radius:** select a node → highlight up/downstream reach + a side panel (its reach,
  risk, accountability / human root, one‑click fixes)
- **Find→Fix:** side‑panel actions seed the policy assistant (like CISO priority actions)
- **DAG drill‑down:** click a trace/node → the existing per‑trace DAG opens as a detail panel/modal

## 6. Build stages

- **Stage 1 — Observed++ (fast, from existing data):** Human/NHI/Agent typing + agent→agent edges,
  type/time filters, risk heatmap (sensitivity + deny‑rate + unrooted), click‑blast‑radius side
  panel, DAG drill‑down. High value, **no policy evaluation** required.
- **Stage 2 — Entitled + Gap:** the policy‑derived "can reach" overlay + the Gap (over‑privilege)
  view. The heavier backbone.
- **Stage 3 — Find→Fix + recommendations:** one‑click right‑size/revoke seeds, least‑privilege
  recommendations, probing/anomaly flags.

## 7. Constraints & honesty

- Read‑model only — no new telemetry; built from audit + PDP + DLP + policy you already store.
- Entitled computation is genuine work (policy evaluation across identity × resource) — the main
  new engineering.
- Transparent, explainable risk (no black‑box scores), consistent with the CISO dashboard.

## 8. Out of scope (for now)

- New data capture · cross‑tenant views · historical "as‑of" entitlement replay (JIT time‑bound
  reach is a later refinement).
