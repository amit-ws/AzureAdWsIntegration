# TealTiger + Dakera: "storage = evidence, not authority". What it means for WAAG's ledgers, act_chain and the CEO's "memory with the LLM" idea

*Research dossier, WhiteSwan intent-aware authorization track. Compiled 2026-09-26. Every external fact has a URL. Vendor marketing numbers are labelled **[VENDOR CLAIM]**. My own reasoning is labelled **[Judgment]** or **[Inferred]**. WAAG code facts are cited as GG §x (= `docs/others/gateway-grounding.md`) or PB (= `docs/others/Agentic-Gateway-Product-Brief.md`).*

---

## 0. Executive summary (10 lines)

1. **TealTiger** is an open-source (Apache-2.0) in-process governance SDK for LLM apps. It runs deterministic policy with no LLM in the decision path, tracks cost, and emits typed "decision receipts". Its separate TealProof module adds Merkle trees plus RFC 3161 timestamps. **Dakera** is a self-hosted agent-memory server: the engine is proprietary, the SDKs are MIT, and it offers vectors, a knowledge graph and importance-weighted decay.
2. The integration (dakera-py 0.12.1, 2026-06-15) is **three small Python adapter classes**. Cost records go to Dakera memories. TealTiger `Decision` JSON goes to "episodic" memories tagged by correlation_id and policy_id. Delegation is stored as `delegated_from` graph edges between *decision memories*. BFS over those edges is clamped to 5 hops.
3. The principle comes from the TealTiger blog. The store answers two questions: "was this already decided (retry)?" and "what delegation chain was in force?" It never answers "is this new action allowed?" **A stored ALLOW never authorizes a new action.**
4. I read the adapter source. It has **no hashing, no signing and no append-only guarantee**. Dakera memories can be updated and deleted through the API. Decisions are subject to importance-weighted decay, and records below a floor are "eventually pruned". The Dakera store is continuity storage, not an evidence-grade ledger. TealTiger's tamper evidence (TealProof) is a separate pipeline that the Dakera integration does not use.
5. Under Dakera's published decay formula (30-day episodic half-life), a DENY (importance 0.95) outlives an ALLOW (0.80) by only about 7.4 days, whatever the floor. The records that actually caused side effects (ALLOWs) are kept the *least*. **[Inferred arithmetic]**
6. The latency figures are all **[VENDOR CLAIM]**: TealTiger policy evaluation under 5 ms; Dakera p99 recall 28 ms plus 14 ms graph lookup, under 35 ms combined. That would roughly triple WAAG's ~12–13 ms per-hop governance overhead if placed on the hot path.
7. **WAAG already holds the "authority" half more strongly than this pair does.** Our delegation lineage is a gateway-signed, append-only, 120 s OBO `act_chain` that is re-verified at every hop, and the PDP consults no history (GG §5.9, §13f). Dakera's chain, by contrast, is edges written by the app, unsigned and mutable.
8. **WAAG's gap is the "evidence/continuity" half.** Audit writes are async and drop rows under load. There is no hash chain or signature, no retention policy and no policy digest. The PDP timestamp is the write time. The parent→child link (`corr_id`) is carried in the signed token but never persisted or read (GG §9.2, §9.4, §13f; PB H6).
9. **"Memory" in an authorization system** should mean three things: an append-only, tamper-evident *evidence ledger*; typed, freshness-bounded *continuity attributes* such as trajectory, counters and approvals, which may only restrict unless an exact-action signed approval applies; and a signed *intent anchor* carried in the credential. It must **never** mean LLM or vector recall of past verdicts reused as permission, decaying audit, or history that agents can write.
10. **Recommendation:** adopt the principle and TealTiger's contract ideas (params_hash, policy_digest, exact-action approvals, seq + seal). Build them natively in Postgres and the existing per-tenant STS keys. Do **not** put Dakera on the decision path. The interesting Dakera angle is to *govern* agent memory (for example, dakera-mcp) as just another MCP capability behind WAAG.

---

## 1. Sources read

| # | Source | Date | What it gave |
|---|---|---|---|
| S1 | TealTiger blog, "TealTiger × Dakera governance state", https://blogs.tealtiger.ai/governance/integrations/tealtiger-dakera-governance-state/ | 2026-06-16, Naga Satish Chilakamarti | Principle, the 3 classes, importance weights |
| S2 | Dakera blog, https://www.dakera.ai/blog/dakera-tealtiger-integration | 2026-06-16, Dakera AI Team | Architecture diagram, method names, terminal states, 5-hop BFS, license statements |
| S3 | TealTiger repo copy of the blog, `docs/blog/tealtiger-dakera-integration.md` (fetched via GitHub contents API) in https://github.com/agentguard-ai/tealtiger | June 2026 | Exact principle text; credits @rpelevin "from the AG2 governance discussion"; before/after table |
| S4 | TealTiger integration doc, https://github.com/agentguard-ai/tealtiger/blob/main/docs/integrations/dakera.md | — | "Stored decisions inform fresh evaluations, never ambient permission" |
| S5 | **Adapter source code**, https://raw.githubusercontent.com/Dakera-AI/dakera-py/main/src/dakera/integrations/tealtiger.py (581 lines, read in full) | fetched 2026-09-26 | Ground truth for what is actually stored and how |
| S6 | Design discussion, https://github.com/Dakera-AI/dakera-deploy/discussions/169 | 2026-06-14..16 | Participants, latency figures, retention intent (read via WebFetch digest) |
| S7 | TealTiger contracts, `packages/tealtiger-contracts/schemas/{action,decision,approval,execution-receipt,target-capability}.schema.json` in the tealtiger repo | fetched 2026-09-26 | Receipt/approval field-level schemas |
| S8 | TealTiger docs: TealProof (TS + Python), Decision contract, TEEC, Execution Identity, Decision Philosophy, NHI Governance, AG2 integration, https://docs.tealtiger.ai (index: https://docs.tealtiger.ai/llms.txt) | fetched 2026-09-26 | Receipt structure, Merkle/TSA, "probabilistic signals as inputs" |
| S9 | TealTiger v1.3.0 release blog, https://blogs.tealtiger.ai/tealtiger-v1-3-0-autonomous-agent-governance/ | 2026-05-18 | Hash-chain formula, NHI/JIT |
| S10 | CrewAI PR #6030 `GovernanceDecision` contract (TealTiger maintainer's proposal), https://github.com/crewAIInc/crewAI/pull/6030, plus the copy in tealtiger `docs/community/crewai-pr-6030-v3/governance_decision.py` | open as of 2026-09 | intent_ref / idempotency_key / seq / seal design |
| S11 | AG2 PR #3128 (TealTiger extension), https://github.com/ag2ai/ag2/pull/3128 | merged 2026-08-13 | Immutable receipts + forward-linking discussion |
| S12 | Dakera homepage https://dakera.ai, API docs https://dakera.ai/docs/api, integration page https://dakera.ai/integrations/tealtiger, decay blogs https://dakera.ai/blog/how-agent-memory-works (2026-05-06) and https://dakera.ai/blog/temporal-memory-ai-agents (2026-05-08) | fetched 2026-09-26 | Engine, license, decay/pruning, mutability |
| S13 | GitHub REST metadata (api.github.com) for agentguard-ai/tealtiger and the Dakera-AI org; PyPI JSON for `tealtiger` and `dakera` | fetched 2026-09-26 | Licenses, stars, versions, dates |
| S14 | Adjacent: r2r-jev "evidence, not authority" for Jev judgments, https://github.com/Thneoly/r2r-jev; IETF individual draft "Delegation Receipt Protocol", https://datatracker.ietf.org/doc/draft-nelson-agent-delegation-receipts/ (rev -10, 2026-06-13) | — | Same principle applied to model judgments; signed delegation receipts |
| Internal | `sources/03-ceo-idea-and-jev-ceo-chat.md` (read in full), `sources/04-reva-aws-dogwood-post.md` (read in full), `02` (read in full), `01`/`05` (skimmed for history/memory), GG §5.9, §6.8–6.13, §9, §11, §12.4, §13, §14; PB audit rows | — | WhiteSwan context |

---

## 2. What each product is

### 2.1 TealTiger (VERIFIED unless marked)
- **Category.** An in-process, open-source "AI agent security & governance SDK" in TypeScript and Python. It wraps LLM clients (`TealOpenAI`, `TealAnthropic`, …) and agent frameworks, and evaluates policy before the call ([README](https://github.com/agentguard-ai/tealtiger)). The homepage says no hosted service is needed for enforcement ([tealtiger.ai](https://www.tealtiger.ai/)). **It is not a network gateway.** It runs inside the agent process it governs.
- **Design stance.** The decision path is deterministic with no LLM: same input plus same policy gives the same decision (README; [Decision Philosophy](https://docs.tealtiger.ai/about/decision-philosophy.md)). Probabilistic scores (toxicity, ML classifiers) may enter only as **input signals** to a deterministic threshold. This matches the Jev CEO's "the model shouldn't become the authorization authority" (source 03).
- **Modules** (README): TealEngine (policy, FREEZE rules, PLAN_ONLY), TealGuard/TealSecrets, TealMonitor (cost), TealMemory (memory-write governance), TealProof (cryptographic receipts), TealAudit, TealRegistry (MCP definition drift), TealClassifier (local ONNX, "≤20 ms" **[VENDOR CLAIM]**), and others.
- **Decision contract** ([Python Decision](https://docs.tealtiger.ai/api-reference/python/decision.md)): `action` ∈ {ALLOW, DENY, REQUIRE_APPROVAL, REDACT, TRANSFORM, DEGRADE}, `reason_codes`, `risk_score` 0–100, `mode` (ENFORCE/MONITOR/REPORT_ONLY), `policy_id`, `policy_version`, `correlation_id`, `trace_id`, `component_versions`, `metadata`. TEEC v2 adds `nhi_identity`, `workload_identity`, `proof{decision_hash, merkle_root, merkle_proof, prev_hash, anchor_ref}`, `automation_level`, `control_id`, `owasp_category`, `governance_bundle_hash`, `cost_evidence`, and `provenance{source_class, trust_tier, lineage}` ([TEEC](https://docs.tealtiger.ai/concepts/teec.md)).
- **Identity.** Actor, agent and tool identity are policy inputs, and missing identity leads to DENY ([Execution Identity](https://docs.tealtiger.ai/concepts/execution-identity.md)). NHI lifecycle and JIT grants with TTL are described in the [NHI doc](https://docs.tealtiger.ai/concepts/nhi-governance.md). **I found no first-class human→agent→agent on-behalf-of chain in TealTiger itself.** Delegation appears only through the Dakera helper (§3.4) and the proposed CrewAI contract fields `credential_tier` ('human-delegated', etc.) (S10).
- **Open-source status** (S13): hub repo `agentguard-ai/tealtiger` is Apache-2.0 (GitHub API), 33 stars, 46 forks, created 2026-02-05, last push 2026-09-26. The SDK source lives in `tealtiger-python-prod` / `tealtiger-typescript-prod`. PyPI `tealtiger` 1.4.1 (2026-09-12) is Apache-2.0. **Discrepancy:** the TealTiger Dakera blog (S1) says "MIT License". GitHub and PyPI say Apache-2.0, so Apache-2.0 is taken as authoritative.
- **Maturity signals.** Merged as a built-in AG2 extension (PR #3128, merged 2026-08-13, S11). CrewAI contract PR #6030 is still open (S10). The roadmap lists multi-tenancy, RBAC, SSO and SIEM export as *planned* for v1.5 (Q4 2026) (README).

### 2.2 Dakera (VERIFIED unless marked)
- **Category.** A self-hosted "AI agent memory" server: vectors (HNSW/IVF/SPFresh), BM25 hybrid search, built-in ONNX embeddings, a knowledge graph with typed edges, four memory types (episodic, semantic, procedural, working) and automatic decay. It is a single Rust binary with Raft clustering and filesystem/S3 storage ([dakera.ai](https://dakera.ai)). The S2 blog describes RocksDB plus HNSW.
- **Licensing.** Open core. The **memory engine is proprietary** (a self-hosted binary). The SDKs, CLI and MCP server are on GitHub (S2, S12). `dakera-py` has an MIT LICENSE file and PyPI `dakera` 0.12.12 is MIT. GitHub's license detector shows NOASSERTION for several SDK repos (S13). The org was created 2026-03-15. Repos have 0–18 stars.
- **SDK languages:** Python, TypeScript, Go and Rust, plus the CLI and `dakera-mcp` (an MCP server exposing memory as tools) (S13). **There is no Java SDK**, which matters for WAAG (Java 17).
- **Mutability and retention** (S12 API doc and decay blogs):
  - `PUT /v1/memory/update/{id}` edits content, importance or tags. `PATCH …/importance` and `batch_forget` also exist.
  - Decay follows `I(t) = I₀·e^(−λt)`, with a default 30-day half-life for episodic memories.
  - Memories below a minimum importance are "archived to cold storage and eventually pruned".
  - Recall resets decay.
  - There is an audit endpoint (`GET /v1/audit`), but **no hashing, signing or append-only guarantee is documented.**
- **[VENDOR CLAIM]** "88.2% Recall@20 on LoCoMo", "<10 ms P99 query latency, sub-50 ms recall", AES-256-GCM at rest, "no data leaves your infrastructure" (dakera.ai, S2).

---

## 3. The integration architecture

### 3.1 Shape
```
agent code ──► TealTiger (in-process SDK) ──► LLM / tool
                 │  deterministic policy → Decision (ALLOW/DENY/…)
                 │  (the ONLY place a verdict is produced)
                 ▼
     dakera.integrations.tealtiger  (Python adapters, async)
       ├─ DakeraCostStorage      → memories, namespace "governance", importance 0.7
       ├─ DakeraDecisionStore    → episodic memories, importance by verdict
       └─ DakeraDelegationHelper → KG edges  child_decision ─delegated_from→ parent_decision
                 ▼
     Dakera server (proprietary engine: RocksDB/HNSW + KG + decay scheduler)
```
Sources: S1, S2, S5. The discussion (S6) also mentions a `DakeraGovernanceWriter` for TealTiger's `GovernanceIngestionPipeline`. **It is not in the shipped module**, which exports exactly three classes (S5 `__all__`).

### 3.2 Who decides, who stores
- TealTiger decides. Dakera stores (S2).
- The principle (S3, S4), paraphrased: the store answers "has this already reached a terminal state?" (idempotency) and "which delegation chain was in force?" (evidence feeding a new decision). Whether a *new* action is allowed always takes a fresh evaluation of the current action envelope. S1 puts it in five words: "Storage informs; it doesn't permit."
- **Where the principle came from.** S3 credits GitHub user @rpelevin "from the AG2 governance discussion". I could not locate that thread. @rpelevin does appear as a reviewer on the CrewAI governance-contract PR #6030 (S10). An unrelated project, r2r-jev, states the same idea for Jev model judgments: a probabilistic judgment is evidence to be *admitted* by policy before it can change governance state (S14).

### 3.3 What a "decision receipt" contains. Two different things share the name.

**(a) What Dakera actually persists** (S5, `store_receipt`):
- `content` is the serialized TealTiger `Decision` object (`model_dump_json`). That is action, reason_codes, risk_score, mode, policy_id, policy_version, correlation_id, trace_id, component_versions and optional metadata (fields per S8).
- `tags` are `governance`, `decision`, `decision:<action>`, `correlation_id:<id>` and `policy_id:<id>`.
- `importance` is DENY 0.95, REQUIRE_APPROVAL/REDACT 0.90, TRANSFORM/DEGRADE 0.85, ALLOW 0.80.
- `memory_type` is `episodic`. The namespace is the caller-supplied `agent_id`.
- **No hash, signature, sequence number, previous-hash pointer or params digest is added.** The receipt carries no arguments or parameter hash unless the app puts them in `metadata`.

**(b) What TealTiger calls a cryptographic receipt** (TealProof, S8 [TS](https://docs.tealtiger.ai/api-reference/typescript/teal-proof.md) / [Py](https://docs.tealtiger.ai/api-reference/python/teal-proof.md)):
- **Fields.** `receiptId`, `decisionHash` (SHA-256 of the canonical decision), `merkleRoot`, `inclusionProof[]`, `leafIndex`, `timestamp` (RFC 3161 token), `tsa`, `policyVersion`, `engineVersion`, `correlationId`.
- **Chaining.** v1.3 describes each decision hash as SHA-256 over decision, context, timestamp, policy_version and prev_hash (S9).
- **Governance Passport.** Hourly (configurable) sealed Merkle windows whose roots are chained, used to prove "continuous coverage, no gaps".
- **Verification SDK.** Three levels: local Merkle check, TSA timestamp check, full chain.
- **Storage.** local, S3, GCS or Azure Blob. **Dakera is not among the listed TealProof backends.**
- **Not documented:** a digital signature by the governing engine over the receipt. Integrity depends on the hash chain, Merkle inclusion and third-party TSA timestamps. **[Inferred]** TSA anchoring proves a root existed at time T. It does not prove *which engine* produced it. Non-repudiation of origin would need an engine signing key.

**(c) The contract layer TealTiger is standardizing** (S7 schemas, v1.0.0):
- `Action{action_id, agent_id, action_kind, tool_name, params_hash, reversibility_class, timestamp_ms}`: parameters appear only as a digest.
- `Decision{decision_id, action_id, agent_id, action ∈ ALLOW|DENY|REFER, gate_level ∈ AUTO|AUDIT|REFER|BLOCK, decision_source, policy_digest, reason_codes, risk_score, reversibility_class, evaluation_time_ms}`.
- `Approval{approval_id, decision_id, action_hash, policy_digest, approver_id, tenant_id, nonce, issued_at_ms, expires_at_ms, scope = "EXACT_ACTION"}`. Its description says an approval satisfies a gate but does not lower any governance floor. **This is the cleanest formalization of evidence-vs-authority in the whole ecosystem** **[Judgment]**.
- `ExecutionReceipt{receipt_id, decision_id, approval_id?, execution_outcome ∈ executed|blocked|pending|compensated, target_event_id, reconciliation_status}`. Its description calls it "tamper-evident", but the schema has no hash field. That property is presumably delegated to TealProof **[Inferred]**.
- The CrewAI proposal (S10) adds:
  - `intent_ref` = SHA-256(JCS{agent, tool, normalized_scope, intent_digest});
  - `idempotency_key`; duplicates are keyed on (intent_ref, idempotency_key);
  - `intent_digest` for TOCTOU closure, recomputed right before the side effect;
  - `target_state_digest`, `revalidate_if[]`, `credential_scope`, `credential_tier`, `retrieved_policy_refs` ("policy or memory records consulted");
  - run-level `boundary_id` + `seq` + `running_count`, and a terminal `GovernanceSeal{total, final_seq, seal_hash}` for tail-drop detection.

  The proposal openly states a residual: a suffix drop that also suppresses the seal is invisible without an external anchor.

### 3.4 How delegation chains and historical state are persisted and used
- **Delegation** (S5):
  - `link_delegation(child_id, parent_id)` writes a `delegated_from` edge between two **decision memory IDs**, not between principals.
  - `get_delegation_chain(agent_id, decision_id, max_depth=10)` runs BFS and silently clamps to 5 hops.
  - The edges are written by application code that holds a Dakera API key. Nothing verifies that the parent actually delegated to the child.
  - S1 says revocation works by "removing graph edges". **[Judgment]** So the chain is an *audit reconstruction graph*, not a proof of delegation.
- **Cost history** is fully read back and aggregated client-side (`get_summary`, `limit=1000`). This is the one place history clearly feeds a live verdict: a budget exceeded leads to DENY. The before/after table (S3) is explicit that without persistence "cost budgets reset to zero" on restart.
- **Decision history** is read at decision time only through `is_terminal(agent_id, correlation_id)`:
  - it returns True if the first matching memory's action is ALLOW, DENY or TIMED_OUT;
  - REQUIRE_APPROVAL is treated as pending;
  - on a parse error it returns False.

  Also in the S2 audit example: `batch_recall(tags=[governance, decision:deny], date range)` followed by a knowledge-graph query.

### 3.5 How history is used without the store becoming the authority. The rules as I reconstruct them.
1. **Verdicts are never replayed as permission for new actions.** Every new action is evaluated fresh (S1, S3, S4).
2. **History can enter only as an input attribute** (spend-so-far, chain-in-force) to a deterministic policy (S8 Decision Philosophy).
3. **A terminal record short-circuits only the *same* request** (same correlation_id). Its purpose is to stop double execution on retry (S3 table: "Retry after timeout").
4. **Approvals are exact-action, digest-bound, nonce-bearing and expiring,** and they cannot lower floors (S7).

**Tensions I found** **[Judgment / Inferred]**:
- **Rule 3 is a real, if narrow, authority leak.** If `is_terminal` returns True for a stored ALLOW, the caller may skip evaluation for that correlation_id. That is safe only if correlation_id is unforgeable and bound to the exact parameters. In the adapter it is just a tag string, with no params_hash binding (S5). The CrewAI contract fixes this with (intent_ref, idempotency_key), but that is not what the Dakera adapter implements.
- **Decay breaks idempotency.** Once a terminal record is pruned, `is_terminal` returns False and the retry is re-evaluated and possibly re-executed. For authorization that is the safe direction (fresh evaluation). For side-effect idempotency it is the unsafe direction (duplicate refund).
- **Ordering is unspecified.** `limit=1` on a tag filter returns an unspecified memory when several decisions share a correlation_id (REQUIRE_APPROVAL then ALLOW). This is an open question.
- **Availability is not addressed.** Neither blog, the doc nor the discussion says what happens when Dakera is down (S1, S2, S6). The adapter lets client exceptions propagate (S5). **The principle removes the store from the ALLOW path but not from the DENY path.** Budget and history-based restrictions *fail open* if state is missing or reset. That is exactly the failure the integration was built to fix.

### 3.6 Tamper evidence and signing
- **Dakera integration:** none. Records are mutable (`PUT update`, `batch_forget`), decayable and prunable. `DakeraCostStorage.clear()` and `delete_older_than()` issue `batch_forget` on the tags `["governance","cost"]` (S5, S12). Whether multiple tags are ANDed or ORed is not documented. If ORed, and decisions share the namespace, cost cleanup could delete decision receipts **[Inferred risk; open question]**.
- **TealTiger TealProof:** a SHA-256 hash chain, Merkle inclusion proofs, RFC 3161 TSA anchoring on a schedule, and hourly sealed Passport windows (S8, S9). No engine signature is documented. The AG2 PR thread (S11) records the maintainer's stance: keep historical receipts immutable and forward-link new decisions (for example a later freeze) to superseded decision IDs, rather than editing history.
- The discussion itself flagged immutability, fail mode and tamper evidence as unresolved (S6, per the WebFetch digest).

### 3.7 Latency (all [VENDOR CLAIM] unless marked)
| Component | Figure | Source |
|---|---|---|
| TealTiger policy evaluation | "under 5 ms", no LLM | S1, README |
| TealTiger AG2 extension | "Under 2 ms per evaluation" | [AG2 doc](https://docs.tealtiger.ai/integrations/ag2.md) |
| TealClassifier (ONNX) | ≤20 ms | README |
| Dakera `batch_recall` with tag filter | p99 28 ms | S6 (Dakera maintainer, digest) |
| Dakera `knowledge_query` with edge filter | p99 14 ms | S6 |
| Combined governance path | "<35 ms" | S6 |
| Dakera generic | "<10 ms P99 query, sub-50 ms recall" | dakera.ai |
| TSA anchoring | off-path (window seal); TSA timeout default 5 s | S8 config |

**WAAG comparison (VERIFIED, GG §13d).** PDP p50 is 0 ms and pre-PDP build p50 ~1.8 ms. Total governance is ~12–13 ms p50 per hop. Downstream MCP takes ~1.4 s and A2A ~6.9 s. Everything is synchronous and blocking. **[Inferred]** A remote memory lookup of 28–42 ms p99 per hop would be small next to downstream calls, but it would hold a Tomcat or boundedElastic worker longer on every hop of every journey.

### 3.8 Retention arithmetic (why "importance tiers" do not make an audit trail) **[Inferred]**
Take Dakera's formula (30-day half-life for episodic memories) with no access resets. The time to decay from importance I₀ to any floor f is `30·log2(I₀/f)` days. So DENY (0.95) outlives ALLOW (0.80) by `30·log2(0.95/0.80)` ≈ **7.4 days, whatever the floor**. Illustrative floors: 0.1 gives DENY ~97 d vs ALLOW ~90 d; 0.3 gives ~50 d vs ~42 d.

Access resets decay, so records that are *queried* survive and unqueried ones vanish. Retention therefore becomes a function of attention, not of regulation. For incident forensics, the ALLOWs, which actually executed, matter most, yet they are kept the shortest. **Conclusion:** the pair is a continuity cache with audit-flavoured querying. It is not a system of record. TealTiger's own evidence story relies on TealProof plus S3/GCS/Blob with 90-day default Passport retention (S8).

---

## 4. Verified vs claimed: a quick ledger

**Verified (read in code, schema or registry metadata):**
- The three adapter classes and their exact tags and weights. The terminal set {ALLOW, DENY, TIMED_OUT}. The BFS clamp to 5. No hashing or signing in the adapter (S5).
- dakera 0.12.1 was uploaded to PyPI on 2026-06-15. The current version is 0.12.12 (MIT) (S13).
- TealTiger is Apache-2.0 per GitHub and PyPI. PyPI 1.4.1 is dated 2026-09-12 (S13).
- The TealTiger contract schemas (Action, Decision, Approval, ExecutionReceipt, TargetCapability) (S7).
- The AG2 extension PR #3128 merged 2026-08-13. CrewAI PR #6030 is open (S10, S11).
- The Dakera API supports memory update and delete. Decay prunes records below a floor (S12).

**Claimed, not verified:** all latency figures above; Dakera LoCoMo 88.2%; AES-256-GCM at rest; TealProof's "cannot be forged or backdated" (no signature documented); TealTiger compliance mappings (EU AI Act Art. 12, SOC 2 CC7.2, …); "no data leaves your infrastructure".

---

## 5. Mapping to WhiteSwan (WAAG)

### 5.1 WAAG already follows the principle, but only half of it
| Principle element | TealTiger + Dakera | WAAG today (VERIFIED) | Verdict |
|---|---|---|---|
| Fresh deterministic decision every time | Yes (TealTiger engine) | Yes. PDP per hop; PDP "consults no history" (GG §13f) | **Parity.** Note that WAAG's PDP has correctness gaps: ignored head forms widen grants (GG §6.9, §14.1) |
| Delegation lineage | App-written, unsigned, mutable KG edges between decision memories; ≤5 hops | Gateway-minted **signed** OBO `act_chain` (per-tenant RSA-2048), hard invariants (prefixPreserved, appendOnly, subConstant, rootPresent, monotonicRoles), 120 s, re-verified per hop (GG §5.8, §5.9, §5.11) | **WAAG stronger.** Ours is a credential (authority), theirs is a record (evidence) |
| Parent→child decision link | `delegated_from` edge | Carried in the signed OBO as `corr_id` = parent correlationId, **but never read or stored**. TraceGraph infers edges by name (GG §9.4) | **WAAG gap, cheap to close** |
| Idempotency / terminal state | `is_terminal(correlation_id)` | None. correlationId is minted per leg by the gateway, so retries get new ids. `/a2a` has no jti replay check (GG §5.10) | Gap, but design it on (caller, messageId/params_hash), not correlationId |
| Continuity of counters/budgets | Cost records survive restart | Per-agent counters via AGENT_FIELD attributes. In-memory `InFlightRequestRegistry` holds parent text keyed by the child's `corr_id`, but "nothing looks it up" (GG §13f) | Partial |
| Evidence ledger | Dakera memories (mutable, decaying); TealProof separately (hash chain + Merkle + TSA) | Dual ledger. `pdp_audit_log` is the authoritative one (policy id + reason), but: async with drops when the queue is full; timestamp = write time; no trace/session column; no retention; no tamper evidence; no policy versioning; some queries lack a tenant predicate (GG §9.1–9.5, §6.13; PB "Audit: Retention, tamper-evidence, SIEM: PLANNED") | **Both weak in different ways.** WAAG is not mutable-by-design and not decaying, but it is lossy and unsealed |
| Exact-action approvals | `Approval` contract (EXACT_ACTION, action_hash, policy_digest, nonce, expiry) | PDP outputs ALLOW/DENY only; no obligations or step-up (GG §13b). MCP approvalStatus is always UNKNOWN (GG §14.13) | Gap |
| Policy digest per decision | `policy_digest`, `policy_version`, `governance_bundle_hash` | None. Point-in-time replay is "observed, not declared" (GG §9.6, §14.36) | Gap |
| Params digest | `params_hash` (JCS SHA-256) | Full sanitized args in `pdp_context`; full raw MCP args in `CLIENT_TOOL_INVOCATION` (GG §9.3). No digest | Gap (a digest is also better for privacy) |

**[Judgment] Product framing.** TealTiger + Dakera assemble "authority vs evidence" from two products, and the authority side sits inside the agent's own process (trusted-code model). WAAG enforces authority *outside* untrusted agents with signed, per-hop credentials. This matches the gateway-first differentiation versus SDK approaches for trusted agents (team memory: Uber precedent). **WAAG's credible story is "we hold the authority half by construction; we are building the evidence half to audit grade."** We should not claim the evidence half today. PB lists tamper-evident audit (H6) as PLANNED.

### 5.2 The dual ledger, re-read through this lens
- `pdp_audit_log` is already the right *kind* of thing: an authoritative decision ledger that the PDP never reads back. That is evidence, not authority.
- To be evidence-grade it needs four properties. The TealTiger ecosystem names them precisely:
  1. **Completeness.**
     - Today rows can be dropped when the 2000-slot queue is full (GG §9.2).
     - Decision rows (REQUESTED/RENDERED) should become non-droppable: a transactional outbox or a synchronous insert for the decision row only. Measured persist lag is p50 0.45 ms (GG §13f).
     - Add a per-trace `seq` and a trace-end seal (the `GovernanceSeal` idea, S10) so gaps and tail drops are *provable*.
  2. **Integrity.** A per-tenant hash chain: `row_hash = SHA-256(prev_hash ‖ JCS(row))`. Hourly window roots **signed with the tenant's existing STS RSA key** (GG §5.11), which gives WAAG what TealProof lacks: an origin signature. RFC 3161 anchoring is an optional enterprise add-on.
  3. **Attribution of the rule.** Store `policy_set_digest` (a hash over the tenant's enabled `policy_text` set at evaluation time) and engine version. This turns CISO replay from "observed" into "declared" without full policy versioning.
  4. **Correct time and linkage.**
     - Stamp `decided_at` on-thread; `timestamp` today is the async write time (GG §9.1).
     - Add `trace_id`, `session_id` and **`parent_correlation_id` read from the inbound OBO `corr_id`**. This yields a *verified* delegated_from edge, better than Dakera's because it comes from a signed token.
- **Retention is set by tenant policy and regulation (e.g., SOX 7 years), never by importance or access.** No decay semantics anywhere in audit.

### 5.3 The OBO act_chain: credential vs record
- **Keep the act_chain as the *only* lineage source the PDP trusts.** Never rebuild lineage from the store for a decision. A store-reconstructed chain is exactly the "storage as authority" failure. It is also forgeable by anyone with write access to the store.
- **Persist a digest of the act_chain (and the chain itself) in each decision receipt as evidence.** The STS receipt already records act_chain, actor, trace_id, corr_id, jti and scope (GG §9.3).
- The OBO also carries `scope` (the parent's capability id, unread) (GG §13f). Reading parent scope enables *monotonic down-scoping* checks (PB row 10: "parent scope never read"). This is history used at decision time **from a signed credential, not a store**, which is the correct pattern.

### 5.4 A proposed WAAG "governance state" model (four layers)
| Layer | What | Authority? | Read by PDP? | Store | Fail mode |
|---|---|---|---|---|---|
| **L1 Credential** | OBO act_chain, parent corr_id/scope, (future) signed intent anchor, exact-action approval tokens | **Yes**, and only because it is signed, short-lived and gateway-minted | Yes | In the token | Missing or invalid → DENY (existing invariants) |
| **L2 Continuity attributes** | Per-trace trajectory: hop index, capabilities used so far, deny count, cumulative amounts, time since root; per-agent counters | No. **May only restrict or trigger step-up** | Yes, as typed `context.trace.*` attributes | In-memory keyed by trace_id (primary) plus Postgres (recovery) | **Declared per attribute. Restrictive rules fail closed** when state is unavailable or stale (sentinel value) |
| **L3 Evidence ledger** | Decision receipts, hash-chained, sealed, signed | No | **Never** (PDP must not read it) | Postgres `pdp_audit_log` (+ outbox) | Write failure is surfaced, never silent |
| **L4 Learned signals** | Baselines, local-LLM intent labels and confidences | No. Signals only | Yes, as attributes with model id/version | Computed inline or offline; outputs recorded in L3 | Timeout → "unknown" sentinel → policy decides (deny or step-up for sensitive capabilities) |

Seams (VERIFIED): `PolicyContextBuilder.CustomAttributeProvider` has zero implementations and lacks RequestContext and traceId. `HopOrchestrator` has everything in scope between the registry lookup and `buildFor*` (GG §13c). New signals must be string, boolean or long in `context.*`, because the engine has only ==, !=, integer compare, like, contains and && (GG §13b).

---

## 6. What "memory" should and should not mean in an authorization system

The CEO's idea (source 03) is a "light LLM, deployed in the customer environment, to understand intent, plus use memory concept with the LLM". The Jev CEO answered it with "storage = evidence/continuity, not authority". The two ideas collide exactly on the word *memory*.

### 6.1 "Memory" SHOULD mean
1. **An evidence ledger.** An append-only, complete, tamper-evident record of what was asked (digest), by whom (act_chain), under which rules (policy digest), what was decided and why, and what executed. Used for audit, forensics, replay, compliance packs and offline tuning of models and policies.
2. **Continuity state as typed, bounded attributes.** Facts about *this* trace or session and this principal:
   - counts, sums, capabilities used, approvals that occurred, the time window.
   - This is the Dogwood / history-aware authorization idea: "has this happened before, how many times, did approval occur first" (source 04).
   - These attributes are computed by the gateway from what the gateway itself observed, never supplied by the agent. Each has a scope (trace, session, agent), a freshness bound and a declared fail mode.
3. **An intent anchor.** The root task's purpose, captured once at the front door. It is structured where possible (action, resource, limits), hashed and carried *in the signed credential* so every later hop compares against the original, not against the delegating LLM's paraphrase. Today the root human question never reaches the gateway (GG §12.4, §13g), so this is a front-door design problem, not a storage problem.
4. **Model-output provenance.** When a local LLM or decision model labels intent, its output (label, confidence, model id and version, input digest) is written into the decision receipt as evidence. It changes durable state (baselines, trust tiers) only through an explicit admission policy (the r2r-jev "Evidence Admission" pattern, S14).

### 6.2 "Memory" MUST NOT mean
1. **Verdict caching or "precedent".** "A similar request was allowed before, so allow" (vector or semantic recall over past decisions). That is storage as authority by similarity. It is also an attack surface: an adversary can seed benign-looking history to earn trust (OWASP ASI06 memory and context poisoning, cited in source 01).
2. **LLM conversational memory as a policy input.** Free-text summaries of prior turns fed to the judge model are unbounded, poisonable, unverifiable and non-reproducible. Replay becomes impossible, which contradicts the deterministic-audit value WAAG sells.
3. **Agent-writable history.** The subject of a decision must never be able to write the state that decides it. TealTiger's in-process model runs inside the agent. WAAG's inline model is exactly the right place to hold this line.
4. **Decaying or importance-weighted audit.** Retention must follow regulation. It must never follow salience or access frequency (§3.8).
5. **Positive authority from stored state**, with one exception: an exact-action, digest-bound, single-use, unexpired, signed approval, which still cannot lower a floor (TealTiger `Approval` semantics, S7).
6. **A new runtime dependency on the ALLOW path that fails open on the DENY path.** If history powers a restriction, its absence must not silently lift that restriction.

### 6.3 Pressure test of the CEO's "local light LLM + memory" **[Judgment]**
- **The "light LLM in the customer environment" part is compatible with the principle** if its output is a *signal* (TealTiger's "probabilistic signals as inputs", S8) that feeds the deterministic PDP.
- **The "memory" part is where the risk sits.** If it means "the LLM remembers past requests and decides faster next time", it turns the store into authority and breaks reproducibility.
- Re-scope it as: (a) L2 trajectory attributes the gateway computes deterministically; (b) L3 receipts that record the LLM's labels; (c) offline learning from L3 to improve policies and baselines, promoted through review. That keeps the value and removes the failure mode.
- **Much of the "memory" value needs no LLM.** Trajectory counters, parent scope, the parent's A2A text already in the in-memory registry keyed by corr_id, and prior deny counts are all deterministic lookups on data WAAG already holds (GG §13f). They cost well under 1 ms in-process.

---

## 7. Recommendations

**Do now (low effort, high credibility; code seams from GG):**
1. Read the inbound OBO `corr_id` and `scope`, and persist `parent_correlation_id` and `parent_scope` on both ledgers. Replace TraceGraph's inference by name with real edges.
2. Add `trace_id`, `session_id`, `decided_at` (on-thread), `params_hash` (JCS SHA-256 of raw args), `policy_set_digest` and `engine_version` to `pdp_audit_log`.
3. Make decision rows non-droppable (outbox or synchronous insert for RENDERED). Alert on any audit drop.

**Next (evidence-grade audit, PB H6):**

4. Per-tenant hash chain plus a per-trace `seq`/seal. Hourly signed window roots using the existing per-tenant STS key. Optional RFC 3161 anchoring. A tenant retention policy.
5. Add an A2A replay/idempotency guard on (caller, messageId, params_hash). Add the missing `/a2a` jti check (GG §5.10).

**Intent track (depends on the other research streams):**

6. Add `context.trace.*` continuity attributes, with declared fail-closed semantics, through a real `CustomAttributeProvider` implementation that receives trace and act_chain.
7. Add an intent anchor claim in the OBO (hash plus structured fields) set at the front door. Write any local-LLM intent label into the receipt as evidence with model provenance.
8. Add a REQUIRE_APPROVAL outcome with exact-action approvals modelled on TealTiger's `Approval` contract. This needs PDP output beyond ALLOW/DENY.

**Don't:**
- Don't adopt Dakera on the decision path. The engine is proprietary, there is no Java SDK, records are mutable and decaying, and Postgres already covers the need.
- Don't expose any "similar past decision" recall to the PDP or to the intent model.

**Adjacent opportunity:**
- Agent memory servers (dakera-mcp exposes memory as MCP tools, S13) can sit behind WAAG like any MCP server.
- Memory writes and reads then become governed capabilities with the egress classifier on recalls. This parallels TealTiger's TealMemory scopes and DENY_WRITE/DENY_READ actions (S8 TEEC).
- This is a concrete WAAG story for ASI06 memory poisoning.

---

## 8. Open questions
1. What is the original @rpelevin "AG2 governance discussion" where the principle was coined, and did it say more on fail modes? (Not found; @rpelevin is visible only as a CrewAI #6030 reviewer.)
2. What is the Jev CEO's relationship to TealTiger or Dakera? Source 03 says "we've been exploring" the pattern. Is it a partnership, or just interest?
3. Dakera tag-filter semantics (AND vs OR) for `batch_recall` / `batch_forget`: could `DakeraCostStorage.clear()` delete decision receipts in a shared namespace?
4. Which memory does `is_terminal` return when several decisions share a correlation_id (ordering under `limit=1`)?
5. What is Dakera's default importance floor and pruning schedule? Can decay be disabled per namespace for governance data? (A "No Decay" strategy is described in a blog; API-level configuration was not confirmed.)
6. Does any TealTiger component sign receipts with an engine key, or is integrity purely hash + Merkle + TSA?
7. What should a caller do when Dakera is unavailable (fail open vs closed)? The design discussion itself left this unresolved (S6).
8. Are the latency figures (p99 28 ms / 14 ms) from a documented benchmark setup?
9. For WAAG: is the deployment single-instance? This decides whether in-memory L2 trajectory state keyed by trace_id is enough, or whether cross-instance state is needed (GG §15 Q2). A fast child can outrun its parent's ledger write under load (GG §13f, §15 Q5), so L2 must not be derived from the async ledger.
10. For WAAG: which retention period do target customers need (SOX/SOC 2), and must receipts be exportable with their verification material (chain plus signed roots)?
