# Pressure test: the CEO's "light local LLM + memory" approach to intent-aware authorization (and where Jev fits)

*Analysis for the WAAG intent-aware authorization research. Written 2026-09-26. Every claim is cited to a dossier, a captured source or the grounding doc. Estimates and judgments are labelled. Nothing was built, run or installed.*

---

> ### Verdict box
>
> **Overall: the problem and the instinct are right. The architecture as stated is not.** The CEO is right that buyers want intent-aware control, that inference must stay under customer control, and that WAAG holds data nobody else in the chain holds. The plan goes wrong in three places. It locates intent in data that does not contain it. It puts a probabilistic model on every request, which is the one place the evidence says a model cannot be the authority. And it treats "memory" as a model feature rather than as signed state and evidence. It also leaves out the piece that matters most: **where the originally authorized intent comes from, and how it is bound to every hop.**
>
> | Sub-claim | Verdict | One-line reason |
> |---|---|---|
> | (a) "We already have the data" | **MODIFY** | True for lineage and behavior (offline, unwired). False for intent: the human's words never reach WAAG, MCP carries no natural language, and the A2A text is written by an LLM. |
> | (b) "Light LLM in the customer environment, so no data leakage" | **MODIFY** | Keep "no third-party inference" as a hard requirement. Drop "LLM" as the default: use an optional, signed encoder first, and a GPU sidecar only when the customer opts in. The leakage claim covers inference, not the new memory store. Hosted Jev breaks this premise (US-only SaaS). |
> | (c) "Light, so it processes every request fast" | **DROP** (as stated) | On CPU a model adds roughly 2x to 240x WAAG's 12–13 ms governance overhead, on blocking threads. It adds nothing on MCP hops, which carry no natural language. Risk-tier the model instead, and compute intent once at the root. |
> | (d) "Understand the intent" | **MODIFY** | It would "understand" LLM-written, attacker-reachable text. Use the model to *extract* typed intent at a trusted ingress and to *raise* risk on A2A text. It must never grant. |
> | (e) "Use memory with the LLM" | **MODIFY** | Keep gateway-computed continuity attributes and an evidence-grade ledger. Drop recalled precedents and online learning, which open poisoning, inversion and cross-tenant leaks. Storage is evidence, not authority. |
> | (f) Missing: origin and binding of the authorized intent | **ADD (the core)** | WAAG has mostly solved *identity* decay with the signed act_chain. *Intent* decay is unaddressed. Capture intent once, sign it into the OBO, allow it only to narrow, and enforce it deterministically. |
> | "…and then Jev" | **MODIFY** | Hosted Jev: drop it for request-path use, because it is US-hosted with no on-prem option. Jev-class *open* typed decision models (Laya, Verdict, SemIf, JevK5) are good candidates for the sensor role, as a bounded spike. |
>
> **Corrected approach in one sentence:** capture the intent once, sign it into the token, check it deterministically at every hop, and use a small local model only to turn words into typed proposals and to add friction; the model never grants.

---

## 0. What is being tested, and the evidence base

### 0.1 The proposal, broken into sub-claims
From source 03 part A (the user relayed it near-verbatim), plus the colleague's 2026-09-17 version of the same idea:
1. "We already have the data." Most of the work is processing the request.
2. "A light LLM deployed in the customer's environment", so no data leaks to an external AI.
3. "Light", so it can process **every request** quickly to understand intent.
4. The model "understands the intent".
5. "Use memory concept with the LLM."
6. Then Jev, the model the CEO discovered.

### 0.2 Source keys

| Key | Path / source |
|---|---|
| **S01–S05** | `…/intent-research/sources/01…05` (Reva IBAC whitepaper; LinkedIn/LangChain SemIf post; CEO idea + Jev CEO chat; Reva on Dogwood; Reva on Inference Hooks) |
| **GG §x** | `/Users/amitprakash/Desktop/WS Apps/AzureAdWsIntegration/docs/others/gateway-grounding.md` (verified code facts; §12.4 and §13 are the intent-relevant ones) |
| **PB** | `/Users/amitprakash/Desktop/WS Apps/AzureAdWsIntegration/docs/others/Agentic-Gateway-Product-Brief.md` |
| **A2AGAP** | `/Users/amitprakash/Desktop/WS Apps/AzureAdWsIntegration/docs/features/a2a-missing-governance-checks.md` |
| **IF** | `…/intent-research/research/internal-fit.md` |
| **SM** | `…/research/small-models.md` |
| **JEV** | `…/research/jev.md` |
| **TD** | `…/research/tealtiger-dakera.md` |
| **RV** | `…/research/reva.md` |
| **AC** | `…/research/academic.md` |
| **ST** | `…/research/standards.md` |
| **DW** | `…/research/aws-dogwood-agentcore.md` |
| **VL** | `…/research/vendor-landscape.md` |

(`…` = `docs/features/intent-authz`; `sources/` is not kept in the repo.) External URLs are carried over from the dossiers, which read them on 2026-09-26.

---

## 1. Steelman: what the CEO gets right

These points are strong, and the corrected design keeps each of them.

1. **The problem is real, and buyers are asking about it now.**
   - Netskope RFI Q15 asked whether agent access tightens or blocks based on risk or intent. WAAG answered "Partially", but none of the risk signals it named reaches the decision today (IF §6.1).
   - Zscaler prep Q6 asked how ready WAAG is for intent-aware authZ. The honest internal answer was "not ready" (PB:763-774, via IF §6.2).
   - The CEO is steering toward the product's biggest open line item.
2. **"We have data others don't" is partly true, and it is a real moat.**
   - No other vendor verifiably combines a signed, human-rooted act_chain, per-hop single-capability OBOs, and a per-hop ledger across heterogeneous MCP *and* A2A (VL §7 W1).
   - WAAG also keeps full sanitized PDP requests, full tool args and responses, response classifications and per-agent counters (IF §4).
   - For *behavior* this is exactly the raw material that trajectory models need. Praetor compiles benign tool-call telemetry into an automaton that checks each call in about 2.2 ms (AC §4.6, [arXiv 2604.26274](https://arxiv.org/abs/2604.26274), author-reported).
3. **Keeping inference under customer control is a genuine differentiator.**
   - Reva's shipped Claude Code plugin sends full prompts, shell commands and file contents to `api.reva.ai` by default (RV §5.1, V-code).
   - Hosted Jev is US-only (JEV §2.3).
   - WAAG's own admin assistants today send tenant PII to Anthropic under one shared WhiteSwan key, with no BYOK (PB §11.9, lines 689-694).
   - Regulated buyers in WhiteSwan's ICP (financial services, healthcare; PB:86) will ask where inference runs. "In your environment, on weights you can verify" answers that.
4. **"Light" is the right direction.**
   - The latency budget really is tight: governance is 12–13 ms p50 per hop (GG §13(d)).
   - Frontier-LLM judges are the wrong tool here. Reva's own code measures 2.6–3.0 s per call with its guardrail inline (RV §7).
   - Small typed decision models are purpose-built for this role (JEV §1), and LangChain already runs one in the hot path of a guardrail demo (S02).
5. **The market is going the same way.** Reva's whitepaper describes Reva-provided small language models, enriched with enterprise retrieval and kept under enterprise control (S01 §6). That is close to the CEO's picture, so the idea is market-aligned and not naive.
6. **"Process the request and most things are done" is closer to true than it sounds, though not for the reason the CEO gives.**
   - Of 33 candidate intent decisions catalogued from WAAG scenarios, about 22 are deterministic-first once three facts are available at decision time: the parent task text via `corr_id`, typed arguments, and a capability effect tier (IF §7, analyst judgment).
   - Most of the value is therefore in *processing data the gateway already holds*. That processing is mostly deterministic, not an LLM.

Where the steelman stops: every point above survives the corrections below. What does not survive is making the model the place where intent *lives* and the thing that *runs on every request*.

---

## 2. (a) "We already have the data"

### 2.1 Which data, for which decision?
Decisions come in three kinds, and the data behind them differs a lot.

| Decision family (IBAC's three questions, S01 §5) | Data WAAG holds | Available at decision time? | Contains intent? |
|---|---|---|---|
| **Identity continuity**: can this hop be traced to the originating human? | Signed OBO `act_chain` rooted at the human, with invariants re-verified per hop (GG §5.8-5.9) | **Yes**. Root, actor and depth reach the PDP; intermediate nodes do not (IF §1.3) | n/a |
| **Behavior**: is this consistent with history? | `gateway_audit_log` by trace, `pdp_audit_log.pdp_context`, AGENT_FIELD counters, the admin 6-signal risk score, post-processor fingerprints and drift (IF §4) | **No.** The PDP consults no history. The ledgers are async and drop rows under load. The risk score is admin-only. 0 AGENT_FIELD attributes are registered (GG §13(f); IF §1.3, §4) | No |
| **Intent alignment**: does this still serve the originally authorized intent? | Hop-1 A2A text (console LLM's paraphrase), hop ≥2 A2A text (delegating agent's LLM), structured MCP args, descriptors (IF §1.1-1.3) | Partly. Only `argumentsFlat` on A2A, truncated at 2000 chars. Descriptors are in memory but not given to the PDP (GG §13(a-b)) | **No.** See §2.2 |

### 2.2 The intent is not in the data
- **Whose words are they?**
  - On the console path the human's question never leaves the console.
  - Hop 1 carries the console LLM's paraphrase. Every later A2A hop carries text written by the delegating agent's LLM, and the root question is not propagated.
  - MCP `tools/call` carries no natural language at all (GG §12.4).
  - The earliest intent-bearing artifact WAAG sees is therefore already one LLM step removed from the human. Each hop adds another step. This is Reva's "intent decay" (S01 §3), and WAAG's architecture shows it structurally (IF §1.1).
- **Much of what could carry purpose is dropped on the way in** (GG §13(a), §13(g)):
  - MCP `_meta` is dropped.
  - A2A DataParts, message metadata and contextId are dropped, apart from contextId used as a session id.
  - MCP tool annotations are not even stored.
  - Tool and skill descriptions never reach the PDP.
- **What is evaluated is not what is executed.** The PDP sees the A2A `input` cut at 2000 chars, but the full text is forwarded downstream (IF §5 P6, inferred from source lines). A model reading "the data" inherits this blind spot: an instruction placed after character 2000 is never scored.
- **Autonomous chains have no human at all.** `run_autonomous.py` sends a brief under client credentials. The act_chain root is an unverified human derived from `sub` (GG:1012, :1025; IF §7 F8).

### 2.3 "We have the data" to train or tune a model? Not yet.
- The labelled corpus is tiny: **63 A2A skill decisions** locally (JEV §8.6, citing GG §12.1).
  - Laya's own evidence shows that fine-tuning supplies all of its capability. Base checkpoints score 0.36 vs 0.32 random on typed decisions, and the fine-tuned checkpoint reaches 0.77 after about 30k labelled questions (JEV §5.5, §8.6; author-reported).
- There are no production customers, and the traffic is OBO-only demo traffic (AC §7 Idea 3; team memory, schema-drop note).
- Behavior learners like Praetor used 500–5,000 benign traces per workflow (AC §11 Q4).
- The ledger has quality gaps that matter for training and for evidence (TD §5.1; GG §9):
  - rows drop under load;
  - `pdp_audit_log` has no trace or session column;
  - its timestamp is the write time;
  - the parent→child edge is not stored;
  - MCP `isError` is dropped, so tool errors are classified as data.

### 2.4 What "the data" *is* good for (keep this)
- **Parent-task recovery.**
  - A child hop's inbound OBO `corr_id` equals the parent's correlationId.
  - The parent's in-flight entry, including its A2A text, is live in `InFlightRequestRegistry` on the same JVM while the child is being decided.
  - Nothing reads it today (GG §13(f)). This is a sub-millisecond lookup, not a model.
- **Monotonic down-scoping.** The parent's capability is in the inbound OBO `scope` claim, which nobody reads (GG §13(f)). Reading it enables "child cannot exceed parent" checks from a *signed* credential.
- **Behavior baselines and taint**, learned offline from the ledger and exposed as typed, decision-time attributes (AC Ideas 2-3; DW §9.2).

**Verdict (a): MODIFY.** Say instead: "We hold the lineage and behavior data (unwired to the decision) and the plumbing to carry intent. We do not hold the intent itself." Most of the near-term value is in *wiring* existing data to the decision deterministically.

---

## 3. (b) "A light LLM deployed in the customer's environment, so no data leakage"

### 3.1 Feasibility and footprint: "light" covers two very different things

| Class | Examples (license) | Weights | Runs where | Evidence |
|---|---|---|---|---|
| Embedding / encoder classifier | bge/e5/gte-small 33M (MIT); ModernBERT-base 150M (Apache-2.0); Verdict 151M (Apache-2.0, ships ONNX); Laya 421M (Apache-2.0) | ~35 MB INT8 up to ~1.6 GB FP32 | In the JVM via ONNX Runtime Java + DJL tokenizers, or a sidecar | SM §2, §4.1, §8; JEV §6 |
| Small generative LLM with constrained decoding | Qwen3/3.5 0.6–4B (Apache-2.0), Gemma 4 E2B/E4B (Apache-2.0), Phi-4-mini (MIT), Llama 3.2 1–3B (gated) | Q4 0.5–2.9 GB; BF16 1.4–8 GB | Sidecar (vLLM or llama.cpp). Practically needs a GPU to stay under ~100 ms | SM §2.5, §3, §8 option 8 |
| "Jev-compatible" server | OpenJev: DiffusionGemma 26B-A4B | NVIDIA ≥24 GB | GPU sidecar; images total about 21 GB | JEV §4 |

- **Footprint facts** (author-reported):
  - Laya's peak RSS is 9.3 GiB with five checkpoints loaded (JEV §5.6).
  - The OpenJev torch base image is 8.7 GB (JEV §4).
  - ONNX Runtime allocates off-heap, so container limits must cover model plus arena, not just `-Xmx` (SM §4.1).
- *Judgment:* a small **encoder** is feasible inside the gateway. A small **LLM** means a second runtime, and usually a GPU, in every customer deployment.

### 3.2 CPU vs GPU latency (details in §4)
- **CPU measured:**
  - Laya takes about 580 ms per question (English / typed-decisions) and about 193 ms (multilingual) on a 4-core EPYC, author-measured ([Laya BENCHMARKS.md](https://github.com/NandhaKishorM/laya/blob/main/BENCHMARKS.md); JEV §5.6).
  - Prompt Guard 2-86M under load: p50 280–787 ms ([tinfoilsh/confidential-llama-guard](https://github.com/tinfoilsh/confidential-llama-guard); SM §3.1).
- **CPU estimated** (SM §3.2, estimates): base encoder INT8 20–70 ms; any LLM judge 0.3–3 s.
- **GPU:** encoders 5–40 ms; 0.6–4B LLM judges about 30–150 ms (SM §3.3, estimates plus vendor figures).
- **Open question:** what hardware target customers actually run, and whether they will provide GPUs (SM §10 Q1). No source answers it.

### 3.3 Operational burden in the customer environment
Each item below is a cost the customer's platform and security teams must accept before a POC. That is a sales-cycle cost, not just an engineering cost.
- **Another runtime to patch.**
  - llama.cpp's GGUF parser has had RCE-class bugs (Talos 2024, CVSS 8.8; a 2026 GHSA).
  - Ollama had "Probllama", an API-server RCE (CVE-2024-37032).
  - HF TGI entered maintenance mode on 2026-03-21.
  - A JNI binding crash takes down the gateway JVM (SM §4.1-4.2).
- **Air-gap packaging.** No hub calls at startup, pinned local tokenizers, and OCI/ModelPack distribution (SM §5.1).
- **Licensing.**
  - Llama and Gemma terms add attribution, naming and acceptable-use flow-down, plus gated download.
  - An all-Apache/MIT stack is achievable (SM §5.3).
- **Per-version evaluation, calibration and thresholds.**
  - Thresholds must be fitted per checkpoint, per dtype and per option-count bucket, on WAAG-labelled data (JEV §8.4).
  - bf16 vs fp32 alone moved Laya probabilities by up to 0.073 (JEV §5.7).
- **Thread and core budget.** A bounded inference executor with its own cores is needed, because the gateway blocks threads (SM §3.3).

### 3.4 Model supply chain
- **The Jev-class ecosystem is days old.**
  - Laya: created 2026-09-18; 25,410 stars in 8 days (verified via the GitHub API); 413 commits; 9 releases in about 2 hours on 2026-09-24.
  - OpenJev: 2 contributors, no releases.
  - The dossier reads this as a supply-chain and stability concern, not a quality signal (JEV §5.1, §4).
- **Signed provenance is not safety.** OpenSSF Model Signing proves origin ([OMS v1.0](https://openssf.org/blog/2025/04/04/launch-of-model-signing-v1-0-openssf-ai-ml-working-group-secures-the-machine-learning-supply-chain/)). But about 250 poisoned documents were enough to backdoor 600M–13B models ([Anthropic, Oct 2025](https://www.anthropic.com/research/small-samples-poison); SM §5.2).
- **Baseline controls:** safetensors/ONNX only; pinned SHA-256 manifests verified at load; a WhiteSwan acceptance and red-team gate per model version; a sandboxed sidecar with no egress (SM §5; JEV §9.5).

### 3.5 Is "no data leakage" true?
- **For third-party inference: yes.** A local model sends nothing to an external AI provider. That is a real improvement on the admin assistants' shared Anthropic key (PB §11.9).
- **It presupposes a hosting model WhiteSwan has not chosen.** WAAG's hosting is an open question: WhiteSwan-hosted SaaS, a stack per customer, or on-prem (PB §13 Q21; §11.7). A model co-located with a WhiteSwan-hosted gateway is not "in the customer's environment". *Judgment:* the model sees nothing the gateway does not already see, so the leakage posture is set by where WAAG runs, not by the model.
- **The proposal adds a leakage surface of its own: memory** (see §6).
  - Embeddings of prompts and arguments are personal data. Vec2Text recovered 92% of 32-token inputs exactly ([arXiv 2310.06816](https://arxiv.org/abs/2310.06816); SM §7).
  - Shared KV/prefix caches leak across tenants through timing ([arXiv 2608.09225](https://arxiv.org/abs/2608.09225); SM §7.4).
- **"No leakage to an external AI" is equally satisfied by using no model.** The residency argument supports *where* a model runs. It does not argue for *having* one.
- **Hosted Jev contradicts the premise.**
  - TypeSafe's docs describe a single hosted endpoint ([docs.typesafe.ai/models](https://docs.typesafe.ai/models)).
  - A third-party repo records TypeSafe's written answer: hosted API only, US-only, no on-prem or VPC option now or planned ([factorysemantics PR #71](https://github.com/factorysemantics/factorysemantics-mes/pull/71), secondhand; JEV §2.3).

**Verdict (b): MODIFY.**
- **Keep:** "no third-party inference on request data", as a hard requirement.
- **Replace** "light LLM" with an *optional* model tier. Start with a signed, Apache/MIT **encoder** in the JVM or a sidecar. Offer a small **LLM judge only as an opt-in GPU sidecar**.
- **Decide the WAAG hosting model** before promising "in your environment".

---

## 4. (c) "Light, so every request is processed fast"

### 4.1 Latency budget arithmetic

WAAG's current overhead is about 12–13 ms p50 per hop. Downstream p50 is 1.4 s for MCP and 6.9 s for A2A. There are about 4.9 hops per journey (GG §13(d); IF §3.2).

| Option on **every** hop | Added per hop | Added ÷ 12–13 ms governance overhead | Per journey (×4.9) | Status |
|---|---|---|---|---|
| Deterministic chain checks (parent via `corr_id`, counters, typed-arg compare) | < 1–2 ms | ≈ 0.1x | ≈ 5–10 ms | SM §8 option 0 (estimate) |
| Small embedding, in-JVM CPU INT8 | ~5–20 ms | ~0.4–1.6x | ~25–100 ms | SM §3.3 (estimate) |
| Base encoder, in-JVM CPU INT8 | ~20–70 ms | ~1.6–5.6x | ~0.1–0.35 s | SM §3.3 (estimate) |
| Laya on 4-core CPU, 1 question | 193–580 ms | ~15–46x | ~0.95–2.8 s | JEV §5.6 (author-measured) |
| Small LLM judge on CPU | 0.3–3 s | ~24–240x | ~1.5–15 s | SM §3.2 (estimate) |
| Admin-class hosted LLM | ~2.6 s p50 | ~200x | ~13 s | IF §3.2 (internal measurement) |
| Reva's own inline guardrail | 2.6–3.0 s | ~200–240x | — | RV §7 (vendor code comment) |
| Any encoder on a small GPU | ~30–40 ms | ~2.4–3.2x | ~0.15–0.2 s | JEV §8.1 |

- **Latency alone is not decisive.** Compared with 1.4–6.9 s downstream, a 20–100 ms model is under 2–7% of a hop (VL §4.2; SM §1).
- **Threads are the binding constraint.**
  - Every hop is synchronous and blocking. An A2A hop holds a Tomcat worker for its whole subtree.
  - About 33 concurrent journeys would exhaust Tomcat's 200 workers (inferred, GG §13(d)).
  - A CPU model on the gateway's own cores lengthens every hold and competes for those cores. It lowers the concurrency ceiling more than it raises per-request latency (SM §3.3; IF §3.2).
  - The only async pool (4/16/2000, drop-on-full) is shared with audit. An inline evaluator must not use it (IF §3.2).
- **Even the leading "IBAC" vendor does not run an LLM on every request inline.** Reva's shipped default posture defers guardrails, at about 150–250 ms warm. Inline judging measured 2.6–3.0 s (RV §7, V-code). Its "p90 under 40 ms" most plausibly describes the Cedar-only path (RV §7, inference).
- **Hard budgets elsewhere fail open.** Copilot Studio's external check must answer within 1 s, or the action is **allowed** (VL §4.2). A slow model in someone else's hook becomes a bypass.

### 4.2 A model on every request is mostly wasted
- **MCP hops carry no natural language.** A model there would re-read structured arguments that deterministic comparison handles better (GG §12.4). The Jev CEO makes the same point: a structured call like a refund with an amount needs no model to know what is being asked (S03 §B).
- **Prompt and resource hops have an empty `argumentsFlat`** (GG §13(b)).
- **Determinism covers most of the catalogue.** About 22 of 33 catalogued decisions are deterministic-first; the model-worthy ones cluster at text-bearing A2A hops and at entity extraction from task text (IF §7).

### 4.3 Cost
- Hosted-model per-token prices are negligible: Jev charges $0.042 per million input tokens (JEV §2.2). But hosted is excluded by the residency premise.
- Local cost is **hardware and people**: reserved cores or a GPU per customer stack, plus model evaluation, calibration and patching per version (§3.3).
- *Judgment:* the unit economics of "a GPU per customer" should be weighed against WAAG's undecided pricing unit (PB Q6: per agent, per hop, per server or per seat).

### 4.4 What should be risk-tiered instead
This follows the Jev CEO's advice to find the smallest mechanism that can make each decision (S03 §B), and the allow/evaluate/deny agent card from the LangChain demo (S02).

| Tier | Where it runs | What runs | Cost |
|---|---|---|---|
| T0: every hop | All protocols | Deterministic: act_chain, capability profile, parent `scope` down-scoping, typed-arg vs intent-envelope compare, per-trace counters, taint bit | Sub-ms to ~2 ms |
| T1: capabilities marked "evaluate", or effect class write/egress/destructive | Mostly MCP leaves | Deterministic envelope, plus trajectory automaton (Praetor-class, ~2.2 ms, AC §4.6) | ~2–5 ms |
| T2: NL-bearing hops only (A2A text) | A2A | Optional local encoder: injected / off-task / typed label. **Raise risk only** | 20–70 ms CPU (estimate) |
| T3: flagged or high-value | Small slice | Opt-in GPU LLM judge, or **REQUIRE_APPROVAL** to a human | 30–150 ms GPU, or seconds to minutes for a human |
| Once per trace, at root | Front door | Intent extraction or confirmation (see §7), reused via the OBO | Paid once, not per hop |

**Verdict (c): DROP "every request" as stated.** Put deterministic checks on every hop, a model on a risk-tiered slice, and intent computed **once at the root** and carried forward. The last point changes the arithmetic most (IF §3.2).

---

## 5. (d) "Understand the intent"

### 5.1 Of what input?
At WAAG a model can read only what WAAG sees (GG §12.4, §13):
- the console LLM's paraphrase (hop 1);
- the delegating agent's LLM text (hop ≥2);
- structured MCP args;
- descriptors, if they are wired in.

So a gateway model can classify **what an upstream LLM claims it wants**. It cannot classify what the human asked (JEV §9.2; AC exec #9). Product messaging must say this plainly.

### 5.2 The judge reads attacker-influenced text
- **The attack path.** If an upstream agent read a poisoned document, the attacker writes the input to the intent model. The injection can even address the judge directly (SM §6.2). After hop 1, `argumentsFlat` is written by an agent that may already be compromised, so it has low integrity (AC exec #9).
- **Evidence that input text steers typed decision models:**
  - TypeSafe's own limitations page says injected instructions and self-arguing text can move Jev's answer ([Jev 1.13 jaggedness](https://docs.typesafe.ai/model-jaggedness/jev-1.13); JEV §2.4).
  - A fake pre-approval field moved Jev's block probability from 0.76 to 0.48 ([VentureBeat, 2026-09-21](https://venturebeat.com/security/companies-are-putting-jev-in-charge-of-ai-agent-decisions-and-prompt-injection-can-influence-the-verdict); a single test).
  - Laya returned `cancel_account` at 0.9998 on "please do not cancel" text ([issue #377](https://github.com/NandhaKishorM/laya/issues/377)).
  - **No Jev-class project publishes an adversarial robustness evaluation** (JEV §8.5).
- **Evidence against detectors and judges in general:**
  - Adaptive attacks bypassed all 12 published defenses tested, most at over 90% ASR ([arXiv 2510.09023](https://arxiv.org/abs/2510.09023)).
  - Character-injection evasion reaches up to 100% against commercial guards ([arXiv 2504.11168](https://arxiv.org/abs/2504.11168)).
  - Punctuation-only or "Thought process:" responses fooled LLM judges at false-positive rates up to 60–90% ([arXiv 2507.08794](https://arxiv.org/abs/2507.08794)) (SM §6.1; AC §6).
- **Truncation games.**
  - Prompt Guard 2 caught **0 of 350** injections placed after token 510 ([independent benchmark](https://github.com/briankhoi/llama-prompt-guard-2-benchmark), small, self-labelled).
  - Laya English silently cuts state beyond about 320 tokens (JEV §8.3).
  - WAAG's own 2000-char evaluate-vs-forward gap stacks on top (IF P6).
- **Comparing the hop with itself proves nothing.** Reva's shipped adapter learned that when the hop's text is compared with itself, the verdict is always "aligned". Intent needs an independent anchor (RV §2.2, V-code). A gateway model judging only the current A2A message against the current skill has no anchor.

### 5.3 Non-determinism, calibration, explainability
- **Non-determinism.** At temperature 0, 1,000 runs of one prompt gave 80 distinct completions, driven by batch-size variance ([Thinking Machines](https://thinkingmachines.ai/blog/defeating-nondeterminism-in-llm-inference/); SM §6.1). The same request could get different verdicts under load. That breaks the replay story WAAG sells. Encoders at batch 1 on CPU are much more reproducible (SM §6.1).
- **Calibration.**
  - Guard models are overconfident under jailbreaks ([arXiv 2410.10414](https://arxiv.org/abs/2410.10414)).
  - Laya ships with ECE 0.466. It reports 0.952 confidence at 0.000 accuracy on Khmer (JEV §5.7, author-reported).
  - Jev's confidence formula is disputed or unpublished, so thresholds do not transfer between implementations (JEV §5.7).
- **Explainability to auditors.**
  - A probability is not a reason. WAAG's evidence story rests on `pdp_policy_id` + `pdp_reason` per decision (IF §4).
  - Any model output must be recorded as evidence alongside the policy decision: model id, weights digest, input hash, score, threshold and latency (SM §4.3 item 5). The policy verdict and the model verdict should be audited separately, as Reva's decision log does (RV §9.3).
- **Capability limits of small models.**
  - Meta reports that small models lack the reasoning for its AlignmentCheck, which also relies on the agent's chain of thought that WAAG never sees (AC §4.3).
  - Intent-to-scope mapping is a measured bottleneck: ASTRA's F1 falls from 0.96 with one tool to 0.67 with three (AC §3).

### 5.4 Reva's own position
Reva, which sells intent-based access control, says in its whitepaper that "enterprises cannot rely exclusively on probabilistic systems to make authorization decisions" (S01 §4). Its answer is to let the LLM judge only *narrow* inside a Cedar boundary (S01 §5; RV §5.2).

Standards and frameworks agree:
- OWASP LLM06 complete mediation;
- NIST AI 100-2 (design as if prompt injection will succeed);
- the AP2 whisper-attack paper, where signed carts passed every protocol check yet did not match the user's request at 56–90% success ([arXiv 2609.11757](https://arxiv.org/abs/2609.11757); ST §10, §13.1).

### 5.5 WAAG-specific integration hazards (fail-open by default)
- **Missing attributes read as false.** A missing attribute makes a condition false, **including `!=`**. So `forbid … when context.intentRisk == "HIGH"` silently does nothing when the model times out (IF §2.2; GG §6.2).
- **Provider errors vanish.** The `CustomAttributeProvider` SPI swallows exceptions (GG §13(c)).
- **The engine widens grants.** It ignores `principal in` / `resource in` heads, drops unparsed fragments, and mis-evaluates `!`/`||`. A permit that rests on a model boolean is doubly fragile (IF §5 P1).
- **There is no third outcome.** The engine is ALLOW/DENY only (GG §13(b)). Low-confidence "ask the human", the natural result of an uncertain model, cannot be expressed, so every false positive becomes a hard deny (IF §5 P9).
- **Caller-controlled context.** Attributes can overwrite built-ins, and `X-WS-Tenant` beats the verified claim. An attacker could spoof or re-select the context the model sees (IF §5 P5, P8; SM §6.2).

### 5.6 What the model *can* legitimately do
1. **At a trusted ingress** (where the human's words are available): map natural language to a **typed intent plus confidence**. This is the Jev CEO's duplicate-payment example (S03 §B). The typed output becomes a *proposal* that a deterministic envelope, or the human, confirms (AC Idea 4; ST §13.2).
2. **On A2A text:** act as an **advisory sensor** for injected, off-task or scope-expanding requests that can only **raise** friction (DENY or step-up) (SM §6.3; JEV §8.5; AC Idea 4).
3. **Structurally**, which is stronger than judging: project A2A free text onto the target skill's typed inputs, so embedded instructions have no channel. This is the "Language Converter Firewall" result: security ASR from 60% to 3% ([arXiv 2502.01822](https://arxiv.org/abs/2502.01822), TMLR 2026; AC §4.5).

Also note the "mistaken but authorized" failure raised under the LinkedIn post (S02, first comment). The agent took a legitimate action at the right permission level, but it was the wrong action. Risk classification does not see this. It needs a bound task envelope and end-state checks (AC §10 item 4).

**Verdict (d): MODIFY.** Replace "understand the intent" with "**extract** typed intent where trusted words exist, and **sense** risk in delegated text". The policy engine, checking against a *bound* intent, decides. The model never grants.

---

## 6. (e) "Use memory with the LLM"

### 6.1 Which memory? Five different things share the word

| Meaning | Mechanism in WAAG | Value | Main risks | Verdict |
|---|---|---|---|---|
| **M1. Chain/trace working context** | Parent text via `corr_id` (in-flight registry, then `pdp_audit_log`), capabilities used so far in the trace, deny counts, cumulative amounts | **Highest.** Fixes the biggest blind spot. No training needed | Parent text is still LLM-written; per-JVM; async ledger lag untested under load (SM §7.1; GG §13(f)) | **KEEP**, as gateway-computed typed attributes |
| **M2. Per-agent / per-workflow behavior baseline** | Offline pDFA or graph model learned from curated ledger traces; counters | Delivers the "behavior" half of IBAC with evidence. Praetor ~2.2 ms, Skynet <1% FPR (author-reported, AC §4.6) | Telemetry poisoning if learned from uncurated traffic; thin corpus today | **KEEP**, offline-learned, reviewed, versioned |
| **M3. Retrieval memory** (vector store of past approved intents, "precedents") | kNN or few-shot context for the judge | Adapts without fine-tuning | **Poisoning:** AgentPoison >80% ASR at <0.1% poison ([arXiv 2407.12784](https://arxiv.org/abs/2407.12784)). **Inversion:** Vec2Text 92% exact recovery. **Cross-tenant bleed:** WAAG's policy assistant already has one global metadata cache (SM §7.2) | **GATE**: per verified tenant, human-adjudicated writes only, never as permission |
| **M4. Fine-tuning / online learning** from decisions | Periodic retraining on tenant traffic | Best per-domain accuracy | Feedback-loop poisoning (an attacker generates "allowed" traffic); ~250 documents backdoor a model; PII memorization; erasure impossible; silent drift breaks audit replay (SM §7.3) | **DROP online.** Offline, reviewed, signed retraining only |
| **M5. Serving caches** (KV/prefix) | Automatic in vLLM or llama.cpp | Latency | Timing side channels up to 100% success against unprotected servers ([arXiv 2608.09225](https://arxiv.org/abs/2608.09225)) | **ISOLATE**: per-tenant sidecar or `cache_salt` |

### 6.2 The principle: storage is evidence and continuity, not authority
The Jev CEO relayed this principle from TealTiger/Dakera (S03 §B; [TealTiger blog, 2026-06-16](https://blogs.tealtiger.ai/governance/integrations/tealtiger-dakera-governance-state/)). As reconstructed from their code and docs (TD §3.5, §6):
- **A stored ALLOW never authorizes a new action.** Every new action is evaluated fresh.
- **History enters only as an input attribute** to deterministic policy. It may restrict or trigger step-up.
- **The only positive authority from storage** is an exact-action, digest-bound, single-use, expiring approval that cannot lower a floor (TealTiger `Approval` contract).
- **The subject of a decision must never be able to write the state that decides it.** WAAG, which sits outside the agent, is exactly where this can be enforced (TD §6.2).

**Precedent-style memory ("a similar request was allowed before, so allow") breaks this principle directly.** It is authority by similarity, and it is exactly what OWASP ASI06 memory poisoning targets (TD §6.2; S01 Table 1).

### 6.3 Lessons from Dakera itself
- The Dakera store is **mutable and decaying**. Under its published 30-day half-life, a DENY outlives an ALLOW by only about 7.4 days, whatever the floor. The ALLOWs, which actually caused side effects, are kept the shortest (TD §3.8, inferred arithmetic).
- Audit retention must follow regulation, never salience.
- **A fail-mode trap.** The integration removes the store from the ALLOW path but not from the DENY path. If history powers a restriction and the store is unavailable, the restriction silently lifts (TD §3.5). Any WAAG continuity attribute needs a **declared fail mode**. Restrictive rules should fail closed via a sentinel value (TD §5.4).

### 6.4 Privacy and multi-tenancy
- A memory of prompts and arguments is a **personal-data store**, with erasure, residency and retention obligations. Embeddings must be treated as personal data (SM §7.2).
- **Tenant isolation must key on the *verified* tenant.**
  - Today `X-WS-Tenant` overrides the verified claim (GG §14 #3).
  - `TenantContext` is null on MCP handler threads, so per-tenant configuration silently falls back (GG §13(d); IF §5 P4).
  - A per-tenant memory built on today's plumbing could be selected by the caller, or could bleed between tenants.

### 6.5 Where "memory" investment should go first
WAAG already holds the *authority* half more strongly than TealTiger/Dakera: a signed, append-only, per-hop act_chain. Its gap is the *evidence* half (TD §5.1):
- audit writes drop under load;
- there is no hash chain, signature, retention policy or policy digest;
- the timestamp is the write time;
- the `corr_id` edge is never persisted.

"Memory" money is best spent on:
1. decision-time continuity attributes (M1, M2);
2. an evidence-grade ledger: non-droppable decision rows, a per-tenant hash chain signed with the existing STS key, `policy_set_digest`, `parent_correlation_id` (TD §7).

**Verdict (e): MODIFY.** "Memory" = gateway-computed trajectory attributes, plus offline-learned baselines, plus an evidence ledger. No recalled precedents as permission, no online learning, no LLM conversational memory as a policy input. *Much of the "memory" value needs no LLM at all* (TD §6.3).

---

## 7. (f) The missing piece: where the authorized intent comes from, and how it is bound

### 7.1 Identity decay vs intent decay in WAAG today
Reva's framing names two parallel decays across hops: identity decay and intent decay (S01 §3).

| | Identity decay | Intent decay |
|---|---|---|
| **WAAG today** | **Largely addressed.** Gateway-minted, signed per-hop OBO; human-rooted `act_chain` with hard invariants; one-capability scope; `cnf` on A2A (GG §5.8-5.9). Open issues: lineage guardrails disabled in the live tenant, and A2A/stateless doors skip status gates (IF §5 P2-P3; A2AGAP #1-#8) | **Unaddressed.** No purpose field in any envelope; no purpose claim in the OBO; no purpose column on agents, sessions or policies; root question not propagated; parent `corr_id`/`scope` unread (GG §13(g)) |
| **Compared with Reva** | Reva's shipped chain is rebuilt from a caller-supplied `traceparent`, an unverified JWT and header agent ids. WAAG is stronger (RV §6.6, §9.2) | Reva anchors on the user's first utterance, held in PEP state keyed by `traceparent`. A downstream agent could plausibly reset it (RV §9.2, inferred, untested) |

**The core flaw in the CEO's plan.** It re-infers intent at each request from the text in front of the gateway. That text is exactly what intent decay has already degraded, so it measures the drift with a ruler that has itself drifted. IBAC's first question asks about the *originally authorized* intent (S01 §5). That intent has to be **captured once, at the root, from a trusted source**, then **bound** so no downstream agent can rewrite it. Standards, research and AP2's cryptographic mandates all converge on this (ST §13.1; AC Idea 1; VL §5 item 5).

### 7.2 Capture (options, strongest first; ST §13.2)
- **A. Structured intent picked or confirmed by the human** at the front door: purpose code, allowed capability classes, constraints, expiry. It is minted after a fresh login, optionally with CIBA approval. **This is the natural home for a small model**: it turns the human's words into a typed proposal the human confirms (the Jev CEO's duplicate-payment example, S03 §B).
- **B. Admin-defined purpose templates** ("missions", RAR types, A-JWT workflow ids). The caller picks a purpose id, and policy fills in the constraints. This is cheap and needs no human words (ST §13.2; AC §4.5).
- **C. Derived from the first A2A message.** This text is LLM-written and **untrusted**. At most it yields a *proposal* that needs A or B for risky classes.
- **D. Accept upstream intent artifacts:** an inbound Txn-Token `tctx`, AP2 / Mastercard Verifiable Intent mandates, or AAuth `mission_s256`. Verify them, then adopt (ST §1, §10).
- **Autonomous chains** (no human) need an NHI purpose on the registry, which has no such column today (IF §7 F8; GG:694).
- **Getting the human's words at all** requires a console change, or platform hooks that already hand them over: the Copilot Studio webhook and Anthropic Inference Hooks (VL §4.1, W5).

### 7.3 Bind (ST §13.3)
- **Mint once.** At the root, mint a Txn-Token-shaped `tctx.intent` plus `intent_s256`: a SHA-256 hash of the JCS-canonical intent, signed with the existing per-tenant STS key. Cost is sub-millisecond (judgment).
- **Copy it unchanged into every per-hop OBO.** Children may **narrow** but never widen, mirroring the Txn-Token replacement rule and Progent's narrowing (ST §1; AC Idea 1).
- **Fix one semantic clash first.** In Txn-Tokens, `scope` means the stable transaction purpose; WAAG uses `scope` for the per-hop capability. Move the per-hop capability to `authorization_details` or a `cap` claim (ST §1).

### 7.4 Propagate
- **A2A:** the signed OBO (already on the wire), plus a WAAG intent extension in `message.metadata` as a reference only. The gateway should mint and bind `contextId`, which also closes the contextId session-revocation dodge (ST §9; A2AGAP #2).
- **MCP:** bind gateway-side by `txn`/`trace_id` from the inbound OBO. Optionally emit `_meta["io.whiteswan/intent"]` to opt-in servers (ST §8, §13.4).
- **Prerequisite:** MCP spec 2026-07-28 is stateless, with no `initialize` and no sessions. That breaks WAAG's session-based `/mcp` door filter, so per-request identity gates rank *above* intent work (ST §8).

### 7.5 Enforce (deterministic first)
- **Checks** (all fit the current operators; ST §13.3, §13.5):
  - capability ∈ `intent.caps`;
  - each typed constraint satisfied (amount, target id, symbol, environment);
  - annotation or effect class compatible with the purpose;
  - child `tctx` == root `tctx`;
  - intent not expired.
- **Third outcome.** REQUIRE_APPROVAL, carried by CIBA with `binding_message`, A2A `AUTH_REQUIRED`, or MCP URL-mode elicitation / the SEP-2848 pattern. This needs the PDP to gain obligations and an async hold, because threads block (ST §5-§9).
- **Model outputs** become `context.intent*` attributes that can only tighten.
- **Audit.** Every decision row carries `txn`, `intent_s256`, the per-constraint results and any model evidence.
- **Why signed intent beats judged content.** AP2's whisper attacks steered agents into carts that passed every protocol check. The proposed fix enforces the signed intent as a capability grant instead of judging content (ST §10, [arXiv 2609.11757](https://arxiv.org/abs/2609.11757)).

**Verdict (f): ADD. This is the core of the approach, not an extension.** Without an anchor, every model at every hop is judging a paraphrase against a paraphrase.

---

## 8. "…and then Jev"

1. **Hosted Jev fails the CEO's own premise.** It is a hosted, US-only API with no on-prem option, per the reported written answer (JEV §2.3, secondhand). One search digest claimed containerized on-prem runtimes. No primary source supports it, and it contradicts the written answer (JEV §2.3). Hosted Jev is usable only for offline R&D on synthetic data (JEV §8.2 option D).
2. **The open Jev-class models are the right *shape* for the sensor role.** Laya, Verdict, SemIf and JevK5 give typed `choice`/`score`/`noul` outputs from a single pass, with no free-text parsing. Most are Apache/MIT. Verdict ships ONNX (JEV §1, §6).
3. **But they are not ready to be trusted**, on four counts:
   - CPU latency is 193–580 ms per question for Laya (JEV §5.6).
   - Zero-shot accuracy is near chance until fine-tuned (JEV §5.5).
   - Laya ranks 41st on the independent JevBench v1.4.2 (JEV §3).
   - None publishes an adversarial robustness evaluation (JEV §8.5), and the code bases are days old (JEV §5.1).
4. **The "Jev CEO" message is itself a correction of the CEO's plan.** It says:
   - don't fine-tune yet;
   - separate *understanding* intent from *authorizing* it;
   - keep the policy engine as the authority;
   - include the credential scope behind the tool;
   - start from the decisions the gateway actually has to make and pick the smallest mechanism for each.

   All of this agrees with this analysis (S03 §B).
5. **The sender's identity is unverified.** They point to rival open projects and speak of "we" for TealTiger/Dakera. The dossier's hypothesis is that the sender is not TypeSafe's CEO (JEV §2.6). The advice holds either way.
6. **Where Jev-class models fit:** a bounded spike on (i) front-door typed extraction and (ii) the A2A sensor role (T2 in §4.4). Run it shadow/async first, fine-tuned and calibrated on WAAG-labelled data, behind a localhost Jev-wire sidecar. Success means it measurably **raises** detection without false-deny damage (JEV §8.2, §10).

---

## 9. Verdict summary per sub-claim

| # | CEO sub-claim | Verdict | Keep | Change | Evidence anchor |
|---|---|---|---|---|---|
| a | We already have the data | **MODIFY** | Lineage, ledgers, counters, parent `corr_id`/`scope`, descriptors | Say: "we hold lineage and behavior data, not the intent"; wire existing data to the decision; build a labelled set before any model | GG §12.4, §13(f-g); IF §1.3, §7; JEV §8.6 |
| b | Light LLM in customer env, no leakage | **MODIFY** | No third-party inference; customer-controlled weights | Encoder-first and optional; GPU LLM opt-in; signed Apache/MIT weights; decide WAAG hosting; memory stores are a new leakage surface | SM §3-§5, §7; PB §11.7, Q21; JEV §2.3 |
| c | Every request, fast | **DROP** (as stated) | "Light" as a design goal | Deterministic on every hop; model on the risk-tiered slice; intent computed once at the root | GG §13(d); IF §3.2; RV §7; VL §4.2 |
| d | Understand the intent | **MODIFY** | Typed NL→intent extraction | Only at a trusted ingress; advisory on A2A; never grants; fail-closed attributes; evidence-logged | S01 §4; JEV §2.4, §8.5; AC §6; SM §6; ST §10 |
| e | Memory with the LLM | **MODIFY** | Trace continuity; offline baselines | Evidence ≠ authority; no precedent recall, no online learning; verified-tenant keys; declared fail modes | TD §3.5-§6; SM §7; AC §9 |
| f | (missing) Origin and binding of authorized intent | **ADD** | — | Capture once, bind in the OBO (`tctx.intent` + hash), narrow-only, deterministic enforcement, REQUIRE_APPROVAL | S01 §3, §5; ST §13; AC Idea 1; VL W2 |
| — | Then Jev | **MODIFY** | Jev-class *open* typed models as candidate sensors | Hosted Jev out of the request path; bounded shadow spike on A2A and ingress | JEV §2.3, §5-§9 |

**Prerequisites that come before any of this** (IF §5; VL exec #10):
- replace the regex engine (it widens grants) with real Cedar via `cedar-java:uber` (DW exec #8-10);
- add an obligation / REQUIRE_APPROVAL outcome;
- make the SPI fail closed and reserve an attribute namespace;
- close the A2A door-gate gaps and the evaluate-vs-forward truncation gap;
- read `corr_id`/`scope`.

Otherwise, in the dossiers' words, intent becomes a marketing claim on a leaky base.

---

## 10. Corrected approach (one paragraph)

Treat intent as a **bound authorization input** and any model as an **optional local sensor**, never the authority.
- **First,** wire the data WAAG already holds to the decision as typed, fail-closed `context.*` attributes: parent task via `corr_id`, parent `scope` for monotonic down-scoping, per-trace counters and a taint bit, and capability effect tiers from stored and admin-attested annotations. Run them on real Cedar with a REQUIRE_APPROVAL outcome. This alone answers most of the catalogued intent decisions with no model and sub-millisecond cost.
- **Second,** capture the *originally authorized* intent once, at the trusted root. Options: a human-confirmed typed intent card, an admin purpose template, or an accepted upstream mandate / Txn-Token. Sign it into the OBO as an immutable, narrowing-only `tctx.intent` plus hash, and check every hop against it deterministically. That turns intent decay into the same kind of verifiable chain WAAG already has for identity decay.
- **Third,** only where natural language is the only input, run a small, customer-hosted, signed, encoder-first typed decision model; Jev-class open models are candidates. There are two such places: at the front door, turning the human's words into a typed intent *proposal* the human confirms; and on A2A delegation text, as a risk sensor. Its output may only raise risk or require approval, and its label, confidence, model digest and input hash go into the decision receipt.
- **Fourth,** "memory" means gateway-computed trajectory attributes, offline-reviewed behavior baselines and an evidence-grade, hash-chained ledger. It never means recalled precedents, online learning or LLM conversational memory.

Hosted Jev stays out of the request path.

---

## 11. Product view (how to talk about this, now)

- **Outward claim that holds up:** "WAAG binds the human-approved purpose to every hop and enforces it deterministically, with optional local models that can only add friction." Do not claim "WAAG implements the intent standard"; no such standard exists (ST exec #10). Do not claim "our LLM understands intent on every request".
- **Differentiation versus Reva and LangChain:**
  - A cryptographically bound intent anchor and signed lineage outside untrusted agents, against Reva's `traceparent`-keyed state and LangChain's in-harness middleware (RV §9.2; JEV §9.4; VL W1-W2).
  - Inline deterministic speed, against Reva's measured 2.6–3.0 s inline judge.
- **Netskope Q15 / Zscaler Q6:** T0/T1 deterministic trace and intent-envelope checks, plus REQUIRE_APPROVAL, turn "Partially" into a demonstrable "yes" sooner than any model. Candidate demos on the financial flow: wrong ticker (F2), scope fan-out (F3), BALANCE_SHEET during an "earnings" sub-task (F5), injected-headline fan-out (F7) (IF §7.1).
- **Do not publish model accuracy numbers without a method.** Publish ASR, benign utility, false-deny rate and p50/p95/thread occupancy on AgentDojo-style suites wrapped as MCP servers, including adaptive attacks (AC §10). Buyers will probe numbers like Reva's "98%" (RV §7).

---

## 12. Verified facts vs vendor claims used here

**Verified** (code, API metadata, official docs or peer-reviewed papers, per the dossiers):
- All GG facts: whose words reach the gateway; MCP has no NL; 2000-char truncation; ALLOW/DENY only; missing attribute = false; SPI swallows exceptions; 12–13 ms p50; blocking threads.
- Hosted Jev's API shape and limits, and TypeSafe's own admission about adversarial state.
- Laya's star count, age and commit metrics (GitHub API).
- Laya's confidence formulas (source).
- Reva's shipped PEP behavior and measured 150–250 ms / 2.6–3.0 s (Reva's own repo comments).
- TealTiger/Dakera adapter behavior (source read in full).
- Robustness papers: arXiv 2510.09023, 2504.11168, 2507.08794, 2410.10414, 2407.12784, 2310.06816.
- Standards status: Txn-Token -11; MCP 2026-07-28; A2A v1.0; AP2 v0.2.

**Author- or vendor-reported, not reproduced:**
- Laya latency, accuracy and ECE; Verdict's 36 ms CPU; JevK5 and SemIf numbers.
- Praetor 2.2 ms and Skynet FPR; IGAC and IntentCap results.
- Reva "p90 < 40 ms" and "98% drift accuracy".
- Dakera and TealTiger latency; Prompt Guard 2 A100 latency.

**Secondhand:**
- TypeSafe "no on-prem, US-only" (third-party repo).
- The Octomind Jev injection test (VentureBeat, one command).

**My estimates:** CPU encoder/LLM latencies marked "estimate"; per-journey multiplications; the "×governance" ratios.

---

## 13. Open questions

1. **Hosting model.** Is WAAG WhiteSwan-hosted SaaS, a stack per customer, or on-prem (PB Q21)? The meaning of "in the customer's environment" depends on this.
2. **Customer hardware.** Do target customers have GPUs, and on which x86/Arm generation? This decides whether a model tier is in-JVM CPU or a GPU sidecar (SM §10 Q1).
3. **The human's words.** Can the console, or a platform hook (Copilot Studio, Anthropic Inference Hooks, Claude Code), deliver them, or a registered purpose id, at hop 1? And is storing them acceptable (IF §8 Q1; AC §11 Q1)?
4. **What the CEO means by "memory".** Per-trace working context (M1), per-agent baselines (M2), or retrieval/fine-tuning (M3/M4)? The risk profile differs completely (IF §8 Q9).
5. **Labels.** Who labels the WAAG A2A decisions needed to fine-tune and calibrate any sensor (roughly 1k–30k), and from what traffic (JEV §11 Q6)?
6. **Robustness.** What is the real flip rate of Laya, Verdict or SemIf under WAAG-specific adversarial state: injected pre-approvals, negation, judge-addressed text, window padding? Nobody has measured it (JEV §11 Q5).
7. **Who sent the "Jev CEO" message**, and do they have a commercial interest in TealTiger/Dakera or Laya (JEV §2.6; TD §8 Q2)?
8. **Measured cost at load.** What does p95 intent evaluation cost under concurrent load on WAAG's own box, given blocking threads (GG §15 Q5; SM §10 Q2)?
