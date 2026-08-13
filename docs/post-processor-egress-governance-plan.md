# Post-Processor Layer — Egress Governance (design & rollout plan)

## What it is

The gateway today is an **ingress** control plane: on the request path it answers *"is this agent
allowed to **call** this?"* (capability → PDP → connectivity → mint OBO → broker). The post-processor
is the **egress** control plane — the response-path half — answering *"is this **data** allowed to
**reach** this agent?"* The request path sees only **intent** (tool + args); the response path sees
the **actual data**, which is where DLP, compliance, and data-egress control live.

**Where it hooks:** in `HopOrchestrator`, right after `adapter.callTool(...)` returns and before the
result goes back to the caller. It runs on **every hop**, which is what makes it work across the A2A DAG.

## Guiding posture (A) — "raw between trusted hops, enforce at the exit"

- **Between agents:** observe → classify/tag → **propagate tags up the trace** — **no mutation**. Raw
  flows, so intermediate agent logic never breaks. This is **async / off the hot path** (≈ 0 latency).
- **At the terminal egress** (the last gateway-governed hop to the consumer app, e.g. `advisor → console`):
  a **synchronous** scan of the full outbound payload + fold in whatever async provenance arrived, then
  enforce.
- **Redaction is `(data tags) × (recipient entitlement)`**, never data alone: if an agent needs raw data
  and is entitled, it flows raw; data is withheld only from a recipient not entitled to it.

## Engineering positions

- **Inline vs async split:** enforcement/transform is synchronous (only at the enforce boundary);
  tagging/learning/audit-enrichment is async.
- **Fault policy:** fail-closed for security processors (block on classifier error for high-risk), fail-open
  for intelligence processors (never break a working call for a tagging failure).
- **Tiered classification:** cheap deterministic detectors inline (regex/checksums/entropy — can block);
  LLM/ML classifier async + sampled (tagging only, never in the blocking path).
- **Sensitivity is value-driven, name-agnostic** — a field named `id_number` holding an SSN is still tagged
  PII by its value. Schema/declaration governs *need + permission*, not *sensitivity*.
- **Big payloads:** cheap checks first (size/type often decide on metadata alone; large payload = exfil
  signal) → fast deterministic scan (ms/MB) → LLM async+sampled → early-exit on block → cache known-safe
  tools → stream-tee for pass-through.
- **Structured (MCP) vs free-text (A2A):** MCP tool I/O has schema → field-level precision possible.
  A2A skills are `text/plain` today → content-classification + text-redaction only (field-level
  tokenization on A2A would need structured skill outputs — future).
- **Ordering:** **post-process → then audit.** Persist classified metadata + the redacted/tokenized
  payload, never the raw value.

## Rollout

### V1 — Observe & Learn (no enforcement)
- Classify every hop's response; attach data-category + sensitivity tags.
- Propagate tags up the existing trace/correlation/DAG plumbing; surface as a sensitivity overlay in the
  journey/View-DAG.
- Learn: per-tool / per-agent data-category **fingerprints** + egress baselines + drift — **metadata only,
  never raw values**. Feeds tool auto-tagging back into the capability registry.
- All async → no added latency. Ships the visibility + compliance + moat story at zero risk.
- **Also fixes the existing honeypot:** today `gateway_audit_log.response_payload` stores raw tool results
  (verified). Route audit writes through the classifier so raw sensitive payloads are no longer persisted.
  (Can also land as a small standalone hardening ahead of V1.)

### V2 — Policy & Enforce (at the last leg)
- Admin authors **egress policies** — reuse the **Cedar PDP** with a *response context* (tags/size/category
  as attributes). One engine, two evaluation points: request-PDP + response-PDP.
- **Egress entitlement** = per `(recipient × data-category)` → `allow-raw | tokenize | drop`. The mirror of
  request-side capability profiles. Safe default when unknown = don't send raw.
- Enforce synchronously at the terminal egress. **Actions:** block, redact/mask, **tokenize**
  (format-preserving, reversible via a gateway-held token↔real map), truncate, neutralize injection.
- **Ship in shadow mode by default** (dry-run: evaluate policies, record "would-block", don't act); admin
  flips to **live** per policy once trusted.

### Phase-3 — Request-side arg inspection ("pre-processor" twin) — DEFERRED
- Inspect **outbound call args** for sensitive data egressing to an authorized-but-unentitled sink
  (e.g. an agent putting PII into a `send_email` arg). Same classifier + PDP, applied on the request leg.
- **Trigger:** the first time an agent gains a **write / send / external-post** capability. Read-only agent
  tool sets don't need it — V1+V2 are complete for them.

## Coverage vs the "core capabilities" list

| Capability | Where |
|---|---|
| Sensor — classification, sensitivity, volume | **V1** |
| Prompt-injection **detection** | V1 · **neutralization** = V2 (actuator) |
| Decision — response-side policy (Cedar) | **V2** |
| Actuator — block / redact / tokenize / truncate | **V2** |
| Feedback loop — tool auto-tag, drift, feeds request path | **V1** |
| Record — metadata-only audit + honeypot fix | **V1** |

**Deferred (named, not oversold):** request-side arg exfil (Phase-3), streaming responses (buffered only
for now), field-level tokenization on A2A (needs structured skill outputs), active injection-neutralization
(V2).

## Honest boundaries

- The gateway governs **boundary-crossings**, not in-agent processing (LLM synthesis inside an agent is
  off-gateway). Every gateway hop is covered; the agent's internal use of data is not.
- Free-text LLM output: **derived** leakage is caught via **provenance** (conservative — may over-block).
- **Trust assumption:** intermediates = the tenant's own agents. A third-party intermediate needs per-edge
  enforcement on that edge.
