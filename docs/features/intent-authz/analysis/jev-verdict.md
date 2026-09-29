# Verdict on the Jev / OpenJev / Laya / Verdict / SemIf family for WhiteSwan WAAG

*Analysis, 2026-09-26. Written from a senior security architect's view and a senior product view together. This file cites the research dossiers rather than repeating them. All external facts come from those dossiers, which cite primary URLs, or from the two read-only checks noted in §0.*

```
+--------------------------------------------------------------------------------------------+
| VERDICT: ADOPT THE PATTERN, NOT THE PRODUCT.  Bounded spike: A2A hops only, shadow first.  |
|                                                                                            |
| 1. The Jev CEO's framing is right and fits our constraints. Keep the model as a SENSOR     |
|    that answers "what is this text asking for?"; the deterministic PDP stays the           |
|    AUTHORITY. The model's output may only ADD friction (REVIEW/DENY), never grant.         |
| 2. Where the model adds value: A2A hops only. That is the one place natural language       |
|    reaches us, and it is LLM-written and attacker-influenceable. Structured MCP calls      |
|    need no model: about 22 of the 33 catalogued intent decisions are deterministic once    |
|    parent text, typed args and an effect tier are wired.                                   |
| 3. Hosted Jev: NO. It is US-only and hosted-only, which fails "runs in customer env".      |
|    DiffusionGemma/OpenJev: NO as the default (needs a >=24 GB GPU).                        |
|    SemIf/JevK5 (4B): only as an optional GPU tier.                                         |
|    CPU tier: a ~150M ModernBERT-class encoder with a typed or fixed head, in-JVM via       |
|    ONNX Runtime. Verdict's architecture, or our own fine-tune, beats Laya-421M on CPU      |
|    (Laya measured 193-580 ms per question).                                                |
| 4. Maturity: every repo is 1-10 days old. Benchmarks are self-reported. There are NO       |
|    adversarial or prompt-injection evaluations anywhere. Treat as reference designs;       |
|    freeze, pin and re-train. Do not take a runtime dependency.                             |
| 5. Zero-shot quality is weak: Laya base is near chance on typed decisions. Value appears   |
|    only after fine-tuning and calibration on OUR labelled data. The "memory" is            |
|    deterministic chain context, not the model.                                             |
| 6. Prerequisites come before any model:                                                    |
|    - a REQUIRE_APPROVAL outcome;                                                           |
|    - a fail-closed provider;                                                               |
|    - reserved attribute names;                                                             |
|    - a strict policy parser (no silent widening);                                          |
|    - evaluate-what-you-forward (the 2000-char gap);                                        |
|    - reading the parent text via corr_id.                                                  |
| 7. Next step: a 4-week BAKE-OFF (§8) against a no-model baseline. Ship a model only if it  |
|    beats that baseline at a fixed false-positive budget, with CPU p99 <= 150 ms and a      |
|    measured adaptive-attack success rate.                                                  |
+--------------------------------------------------------------------------------------------+
```

---

## 0. Inputs and conventions

**Citation keys**

| Key | Source |
|---|---|
| **JEV §x** | `research/jev.md` |
| **SM §x** | `research/small-models.md` |
| **ACA §x** | `research/academic.md` |
| **IF §x** | `research/internal-fit.md` |
| **TD** | `research/tealtiger-dakera.md` |
| **VL** | `research/vendor-landscape.md` |
| **GG §x** | `AzureAdWsIntegration/docs/others/gateway-grounding.md`. Section 13 was re-read for this analysis. |
| **S02 / S03** | Captured sources: the LinkedIn/LangChain SemIf post, and the CEO idea plus the Jev-CEO chat. Both were read in full. |

**Checks made for this analysis**
- **HF model API, read-only, 2026-09-26:**
  - `heman10x/rlcd-modernbert-151m` (Verdict) ships `model.onnx`, `model_fp16.onnx`, `calibrator.json`, `model.safetensors` and `tokenizer.json`. It is Apache-2.0, has 20,634 downloads, and was created 2026-09-17.
  - `convaiinnovations/laya` and `laya-typed-decisions` ship **safetensors only, no ONNX**. Both are Apache-2.0 and were created 2026-09-18. The API reports 0 downloads.
  - Sources: https://huggingface.co/api/models/heman10x/rlcd-modernbert-151m and https://huggingface.co/api/models/convaiinnovations/laya
- **GitHub REST API:** rate-limited during this session. Repo metrics are therefore taken from JEV §1, §4 and §5.1, which were API-verified the same day.

**Labels**
- **VERIFIED:** confirmed from primary metadata, source code or official docs.
- **VENDOR CLAIM:** self-reported by the author or vendor and not reproduced.
- **Judgment:** my own assessment.

---

## 1. The Jev CEO's framing, applied to WAAG

The chat (S03 §B) gives three principles. Each one maps onto a hard fact about WAAG.

| Principle (S03 §B) | What it means in WAAG | Consequence |
|---|---|---|
| "separate intent understanding from authorization" | Understanding produces **typed attributes**. Authorization stays in the PDP, alongside act_chain, capability profile and OBO scope. | The model's output enters as `context.*` strings or longs, never as a decision. |
| "the model shouldn't become the authorization authority" | Every model in this family can be steered by the text it judges. TypeSafe says so itself (JEV §2.4). A fake pre-approval field moved Jev from 0.76 to 0.48 (JEV §2.5). Laya answered cancel at 0.9998 on "do not cancel" (JEV §5.8). | The model's output must be **monotonic**: it can only restrict. A successful evasion then falls back to the deterministic baseline rather than escalating privilege (SM §6.3; ACA §6). |
| "smallest computational primitive capable of making each decision" | 22 of the 33 candidate decisions are deterministic comparisons once parent text, typed arguments and an effect tier exist (IF §7). Semantic work clusters on text-bearing A2A hops. | A model is justified only where rules provably cannot do the job. The bake-off (§8) has to show it beats the rules baseline. |

**Two corrections to the chat's example (Judgment, grounded in GG §12.4):**

1. **The input is not the human's words.** The chat's "take care of that duplicate payment" example assumes we see what the human said. We do not. On the console path, hop 1's text is the console LLM's paraphrase, and each later hop is another LLM's paraphrase (GG §12.4, §13(g)). A typed model in WAAG therefore classifies **the delegating agent's claimed request**. Product copy must say this. Real human-intent extraction belongs at the front door (the console), or needs the console to forward the human's request.
2. **The sender's identity is unverified.** The "CEO of Jev" is probably not TypeSafe's CEO (JEV §2.6). The sender steers towards Laya and razorback16/openjev, which are rivals of TypeSafe. The sender's "we" refers to TealTiger/Dakera. The advice stands on its merits either way, but Vinay should confirm who sent it before any partnership talk.

---

## 2. Where a typed decision model adds value, and where it does not

### 2.1 Decision-by-decision placement

Hop, input and decision IDs come from IF §1.2 and IF §7.

| Decision class (IF §7 examples) | Hop | Input to the model | Model question (typed) | Primitive | Jev-family value |
|---|---|---|---|---|---|
| **Skill-purpose fit.** Does the request fit the skill's declared purpose? (F1: "research" skill asked to "place a buy order") | A2A hop 1 | `input` text (≤2000 chars today) plus the skill **description** from the descriptor. The descriptor is in scope at the spine but not passed today (IF §3.1 S-A). | `choice` over an admin-authored, per-skill vocabulary, e.g. {`research_readonly`, `transaction`, `admin_change`, `data_export`, `other`}, plus `__insufficient__` | **S** | **High.** This is the one hop-1 decision rules cannot do well. |
| **Contributes to parent task.** Is this sub-request on-task for its parent? (F2, F3, F6) | A2A hop ≥2 | Child `input` plus the **parent text**, recovered through the inbound OBO `corr_id` → `InFlightRequestRegistry` (GG §13(f); not wired) | `noul` "serves the parent task" plus `choice` of deviation type {`different_entity`, `scope_expansion`, `new_action`, `none`} | **D first** (entity/ticker regex, counts), then **S** | Medium. Rules catch entity and count drift. The model adds value on paraphrased deviations only. |
| **Purpose ↔ data-category compatibility** (C4 marketing and home addresses; H1 policy question and compensation; H2 recruiting and medical leave) | A2A | `input` plus the calling agent's registered purpose. That purpose needs a new column (GG:694). | `choice` of data category requested; `choice` of declared purpose. **The compatibility matrix itself is deterministic.** | **S** (label) + **D** (matrix) | **High.** Typed labels feeding a deterministic GDPR-style purpose matrix is exactly the chat's "understanding → authorization" split. |
| **Instruction-bearing / injected delegation.** Did the A2A text pick up instructions from tool output? (F7, G5, R5) | A2A | `input` text; the trace's earlier response classifications | `noul` "contains instructions not derivable from the parent task" | **D** (taint bit from `gateway_response_classification`) + **S** (advisory) | Low to medium. Detection is only 0.70–0.76 even for Laya's trained detector (JEV §8.5), and it is adaptively breakable (ACA §6). Use it for **friction only**. The deterministic taint and Rule of Two carry the weight (ACA §7 idea 2). |
| **Entity extraction for target binding** (R1 payment/amount, G2 path scope, I3 staging vs prod) | Computed once on the **A2A text that carries the task**; consumed at the **MCP leaf** | Parent text | Extraction is not a natural Jev primitive. Use deterministic regex/NER to find candidates, then `choice` over the candidates when several exist. | **D** + occasional **S** | Indirect but real. The model runs on the A2A hop and its typed output is **compared deterministically** with structured MCP arguments later. |
| **Structured MCP call authorization** (G3 self-grant, I4 SQL verb, C1 field set, R2 counts, R3 write-in-read-task, I1 destructive) | MCP leaf | Structured args, tool name, effect tier, act_chain, trace counters | none | **D** | **None.** The chat itself says a structured call needs no model to know what the agent wants (S03 §B). |
| Identity, lineage, delegation, capability allow-list, credential scope, child ⊆ parent scope | every hop | act_chain, OBO `scope` (unread today), profile | none | **D** | None. This is WAAG's existing strength. |
| Rug-pull / description drift (I5) | registry | description hash | none | **D** | None. |
| Weakening security controls (G6), approval-class actions | MCP | effect tier | none | **D → H** (always escalate) | None. The model must not be allowed to *remove* an escalation. |

### 2.2 The pattern the table implies

**Compute semantic labels once, where the text enters, then compare deterministically downstream.** This follows the ACA §7 ideas 1 and 4 and IGAC/IntentCap (ACA §4.2). A model call per MCP hop is never needed.

```
A2A hop (text enters) ── typed model ──► {intentClass, dataCategory, deviation, conf, status}
        │                                          │ (recorded in pdp_context; optionally a signed
        │                                          │  reference carried in the next OBO: IF §3.1 S-H)
        ▼                                          ▼
MCP leaf (structured) ─────────────── deterministic compare: args ⊆ envelope, effect tier,
                                      counters, taint  ──► ALLOW / DENY / REQUIRE_APPROVAL
```

**Budget arithmetic (Judgment on measured inputs):**
- A journey has about 4.9 hops (IF §3.2), of which roughly 1–3 are A2A.
- One or two questions per A2A hop is the entire model budget.
- MCP hops stay at about 12–13 ms of governance overhead.

### 2.3 Where it does not add value (explicitly)
- **Structured MCP calls:** covered in §2.1.
- **Hop 1 "human intent":** we do not have the human's words (§1).
- **Anything the model would *grant*:** see the monotonic rule in §6.
- **Numbers, dates, amounts, counts and ordering:** Jev's own jaggedness page lists these as unreliable (JEV §2.4), and Laya's `score` primitive is its weakest (JEV §5.8). Amount ≤ original charge, Nth refund and similar checks must be deterministic.
- **Negation-heavy or multi-hop reasoning:** a documented weakness of both Jev and Laya (JEV §2.4, §5.8).

---

## 3. Which variant fits a customer-environment deployment

### 3.1 Fit matrix

| Variant | Runs where | Latency (source, status) | Input fit for our ≤2000-char text (≈450–550 tokens, estimate) | Fit for "in customer env" | Verdict |
|---|---|---|---|---|---|
| **Jev** (TypeSafe, hosted) | US cloud only. A written answer reports no on-prem or VPC option (JEV §2.3, secondhand). | 236–276 ms p50 third-party; 0.65 s from Germany (JEV §2.5) | 32k state, which is fine | **Fails.** Data leaves the customer. | Use only as an **upper-bound reference on synthetic data** in the bake-off. Never on customer data. |
| **OpenJev + DiffusionGemma 26B-A4B** | NVIDIA ≥24 GB, or Apple silicon via MLX | 27–31 ms on RTX PRO 6000 at concurrency 1; **760 ms p50 at concurrency 64** (JEV §4, VENDOR CLAIM) | Fine | Only with a big GPU. ~21 GB of images. | **Not a default.** JevBench rank 27 (JEV §3). |
| **SemIf** (Qwen3.5-4B logits) | CUDA, MLX, **llama.cpp CPU (no timings published)**, WebGPU | ~20 decisions/s on an RTX 3090 with shared prefill (JEV §6, VENDOR CLAIM) | Fine | GPU tier | **Optional GPU tier candidate.** JevBench 11th. MIT code; Qwen license not yet checked (JEV §11 Q9). |
| **JevK5** (Qwen3.5-4B + LoRA) | GPU; GGUF on CPU | 13–14 ms H100; **~0.6 s/decision on CPU** (JEV §6, VENDOR CLAIM) | 16k | GPU tier | **Best open quality** (JevBench 3rd, reproduced 86.6% on public items). But its LoRA was distilled from named third-party models, which is a license and ToS provenance question (§4). |
| **CLM** (Qwen3-8B + heads) | GPU only in practice | 99 ms on RTX 3090 | 2k | GPU tier | **No.** JevBench 45th (8.6); `score` ignores state (JEV §6). |
| **Laya** (ModernBERT-large 421M / mmBERT 322M) | CPU, CUDA, MPS, XPU | **CPU 4-core EPYC: 580 ms per question (EN/typed), 193 ms (multilingual); ~linear per extra question.** T4: 33–40 ms (JEV §5.6, author-measured, raw files committed) | EN checkpoint leaves **~320 tokens of state, so our tail is cut silently**. Typed and multilingual leave ~768 (JEV §8.3). | Passes "local", fails "fast on CPU" | **Not for the CPU hot path as shipped.** Useful as a fine-tunable base on a GPU, and as a reference for RLCD and calibration code. |
| **Verdict** (ModernBERT-base 151M + GLiClass head) | CPU/GPU; **ONNX published** (VERIFIED via HF API) | 35.6 ms single-thread, K=5, WASM proxy (VENDOR CLAIM; must be re-measured) | 512 tokens including options, which is **tight** | **Best CPU fit of the family** | **Leading CPU candidate architecture**, but weak quality: 48% on TypeSafe's public eval (JEV §6). Expect to re-train its head on our data. Keep its **abstention option**; OpenJev discards it (JEV §4). |

### 3.2 Recommendation (Judgment)

**Default tier (every customer): CPU, in-JVM**
- A ~150M ModernBERT-base-class encoder with a **typed or fixed head fine-tuned on WAAG decisions**.
- INT8 ONNX: an estimated 20–70 ms at 512 tokens on modern x86 (SM §3.2, estimate, unmeasured).
- Candidates are Verdict's weights with a re-fitted head, or our own ModernBERT-base / DeBERTa-v3-base fine-tune (§7). The bake-off decides.
- Budget: **p99 ≤ 150 ms for one question at 512 tokens, on 2 dedicated cores**.

**Optional tier (customers who already run GPUs)**
- A SemIf/JevK5-class 4B logit reader, used only for flagged or high-risk A2A hops.
- Deployed as a sidecar with per-tenant isolation and prefix-cache salting (SM §7.4).

**Rejected**
- Hosted Jev and Codiv/LangSmith-hosted SemIf for customer data, because they break the CEO's no-data-leakage premise (S03 §A; JEV §8.2 option D).
- DiffusionGemma as a default, because of the GPU class it needs.

### 3.3 Integration pattern with a Java 17 / Spring Boot gateway

**Phase 1 (bake-off and shadow): a localhost sidecar speaking the Jev wire API**
- Endpoint: `POST /v1/systemone`, the same shape as TypeSafe, OpenJev and `laya-serve` (JEV §4, §8.2 option A).
- Why:
  - We can A/B Laya, Verdict, SemIf and a hosted-Jev reference by changing a URL.
  - A Python crash cannot take down the JVM.
  - Bearer auth exists.
- Costs: an extra container (~8.7 GB torch base per JEV §4) and Python operations in the customer environment. Acceptable for a spike; not ideal for GA.

**Phase 2 (GA, one frozen model): in-JVM ONNX Runtime Java plus DJL HF tokenizers**
- Why: no Python, one artifact, deterministic at batch 1 (JEV §8.2 option B; SM §4.1).
- Work we must own:
  - **port the sequence builder** (layout, option caps, truncation, `[MASK]` scrubbing);
  - port **temperature and confidence math**;
  - add a **CI parity test** against the Python reference outputs.
- Memory: ORT memory is off-heap, so container limits must cover it (SM §4.1).
- Laya ships no ONNX; Verdict does (VERIFIED, §0).

**Call site: the spine seam S-A in `HopOrchestrator`, between the registry lookup and `buildFor*`, not the `CustomAttributeProvider` SPI as it stands**
- The SPI lacks the descriptor, RequestContext, `corr_id` and act_chain.
- It swallows exceptions, which makes it fail-open.
- It has zero implementations (GG §13(c); IF §3.1).
- Either extend the SPI signature or call the sensor directly from the SKILL leg (lines ~596–613).

**Execution rules**
- **A2A SKILL legs only.** No MCP legs.
- Run on a **dedicated bounded executor**, never the shared `auditExecutor`: it drops tasks when full and is shared with the decision ledger (GG §13(d)).
- Use a hard deadline (e.g. 150 ms CPU) and a circuit breaker.
- Cache results keyed by `(sha256(canonical input), model digest, question-set version)`.
- **Evaluate the exact bytes that will be forwarded.** Today the PDP sees a 2000-char cut while the full text is forwarded (IF §5 P6). Either score the full text with windowing and take the maximum risk, or forward only what was scored.

**Threading (Judgment on GG §13(d))**
- A2A hops hold a Tomcat worker for the whole subtree, so added inference time turns into lost concurrency, not only latency.
- Give the sensor its own core budget.
- Measure journey throughput, not only per-call latency (§8.4).

---

## 4. Maturity and vendor risk

| Item | Facts (JEV §§1–6 unless noted) | Risk reading (Judgment) |
|---|---|---|
| **TypeSafe / Jev** | Launched 2026-09-15. $40M seed (secondary sources). Hosted-only and US-only (written answer, secondhand). Confidence formula unpublished and drifting across versions. Jaggedness page admits the state can steer answers. | Credible company, wrong deployment model for us. Its thresholds do not transfer between versions, which is a nightmare for audit replay. |
| **Laya** | Repo created 2026-09-18. 25,410 stars (API-verified by JEV). 413 commits, 86 contributors, **23 releases, 9 of them within ~2 h** on 2026-09-24. Safetensors weights (good). HF reports 0 downloads (tracking artifact?). Author's arXiv papers exist but are not Laya itself. | Viral, 8 days old, changing hourly. Star count is not a quality signal. Supply-chain and stability risk. Revision pinning plus `expected_sha256` exist, so use them. |
| **OpenJev** | 437 stars, 41 commits, **40 by one author**, 2 contributors, **no releases**. Free hosted tier on the author's Codiv domain. | Single maintainer and a likely commercial funnel. Use it as a reference router, not a dependency. |
| **SemIf** | MIT, 4,365 stars, 25 commits, 6 contributors, no releases. Renamed from "OpenJev". LangChain hosts it (free until 2026-09-28). | Most ecosystem traction (LangChain). Watch for the name collision with razorback16/openjev. |
| **Verdict** | ~104 stars (digest). Weights created 2026-09-17, ~20.6k downloads, ships ONNX and a calibrator (VERIFIED). | Small, young, single author. The ONNX-first packaging is the most enterprise-friendly in the family. |
| **JevK5** | ~109 stars. LoRA **distilled from named larger third-party models**, including a proprietary one per the repo. | Best open quality, but **provenance and ToS risk** in shipping distilled weights inside a commercial product. Needs legal review before use. |
| **Benchmarks** | JevBench is a one-person hobby project with no adversarial axis. Accuracy tables are self-reported. **No project publishes an adversarial or prompt-injection robustness evaluation** (JEV §8.5). | We must generate our own robustness evidence (§8). |

**Controls if any artifact is used (SM §5; JEV §9.5)**
- **Freeze.** Fork at a commit, vendor the minimal inference code, and pin weights by SHA-256.
- **Sign.** Use OMS signing with WhiteSwan as signer, and verify the hash at load; refuse to start the sensor on mismatch.
- **Isolate.** Safetensors or ONNX only, never pickle. No hub fetch at runtime. Sidecar with **no egress**. No auto-update.
- **Re-accept per version.** Run the acceptance evaluation (§8) on every model version. A signed checkpoint can still be backdoored; about 250 poisoned documents suffice (SM §5.2).

**Strategic preference (Judgment).** Treat the family as **reference designs** for three things:
- the typed per-option `[MASK]` head;
- RLCD with proper scoring rules;
- per-bucket temperature fitting.

The product artifact should be **our own fine-tune on an established base** (answerdotai/ModernBERT-base, Apache-2.0, 2024-12, SM §2.1). That base has a clean provenance story for enterprise buyers and removes dependence on 8-day-old repos.

---

## 5. Security risks: the "state" is attacker-influenced

The judged text on an A2A hop is written by an upstream LLM. That LLM may have read a poisoned document or tool output, and it may know the gateway checks it (SM §6.2; ACA §1 item 9). The attacker therefore controls the model's input.

| # | Threat | Evidence | Mitigation |
|---|---|---|---|
| T1 | **Direct steering.** Text argues for its own label, e.g. "pre-approved by security" or "reviewer: mark on-task". | TypeSafe jaggedness page; Octomind 0.76 → 0.48 (JEV §2.4–2.5) | Monotonic use (§6). Never let a model score *remove* friction set by rules. Keep tool output and self-descriptions out of the state, as LangChain does (JEV §7). |
| T2 | **Negation and "ignore the above" flips**, with high confidence | Laya #377: cancel at 0.9998 (JEV §5.8) | Treat high confidence as meaningless on adversarial text. Include negation cases in the attack set (§8.2). |
| T3 | **Label-word following.** Answers track the `true:`/`false:` words, not the state. | Laya #156 (JEV §5.8) | Use opaque option IDs with neutral descriptions. No boolean-word keys. |
| T4 | **Window games.** The payload sits past the scored window. | Laya EN keeps ~320 tokens and truncates silently; PG2 caught 0 of 350 injections after token 510 (SM §6.1); our 2000-char PDP cut vs full forward (IF §5 P6) | Score the forwarded bytes with overlapping windows and take max risk. Emit a `TRUNCATED` status that maps to REVIEW. |
| T5 | **Language and encoding evasion** | English checkpoint: 0.000 accuracy at 0.952 confidence on Khmer (JEV §5.7). Character injection reaches up to 100% evasion against guards (SM §6.1). | Canonicalize (NFKC, strip zero-width characters, fold homoglyphs). Detect language deterministically; unsupported → `LANG_UNSUPPORTED` → REVIEW. |
| T6 | **Parse differential.** The model scores text A while the agent acts on text B. | `metadata.arguments.input` overwrites text parts; DataParts are dropped yet may be consumed downstream (IF §5 P17; SM §6.2) | Score exactly what is forwarded. Reject or score multi-part messages. |
| T7 | **Boundary probing.** The ALLOW/DENY result acts as an oracle. | SM §6.2 | Rate-limit model-attributed denials per principal. Alert on bursts of near-threshold scores. Do not echo scores to callers. |
| T8 | **Order and wording instability** | Jev 13% option-order flips; Verdict 3–4.5%; SemIf 4–10 of 36 (JEV §5–6) | Fixed option order per question version. Measure the flip rate (§8.3). |
| T9 | **Fail-open plumbing** | SPI swallows exceptions. A missing attribute makes `forbid` conditions false, including `!=` (GG §13(c); IF §2.2) | The sensor always emits a status. Permits for opted-in capabilities **require** `context.nlGate == "PASS"`, which fails closed (§6). |
| T10 | **Attribute spoofing or clobbering** | Custom attributes can overwrite built-ins. HEADER attributes are caller-supplied (IF §5 P5) | Reserve the `nl*` names in code. Reject HEADER or DB attributes with those names. |
| T11 | **Policy-engine widening** | Unknown fragments are dropped, so the permit widens (IF §5 P1) | A mis-parsed `&& context.nlGate == "PASS"` would be silently dropped, and the model gate would vanish. **Strict parsing is a hard prerequisite.** |
| T12 | **Resource exhaustion.** Long or crafted inputs maximize inference time and pin Tomcat workers. | GG §13(d) | Input cap, deadline, bounded executor, circuit breaker. Record `TIMEOUT` as a status. |
| T13 | **Supply chain and backdoor** | SM §5.2 | §4 controls. |
| T14 | **Tenant-selected thresholds via the `X-WS-Tenant` header** | SM §6.2 | Key per-tenant models and thresholds on the **verified** tenant claim only. |

**The bottom line (Judgment, consistent with ACA §6).** Assume a motivated attacker can always make the model say "benign". The design goal is to make that worthless: a benign score changes nothing, and only non-benign or uncertain scores add friction.

---

## 6. Making the output safe to consume

### 6.1 Pipeline

```
A2A text (exact forwarded bytes)
  → [D] canonicalize (NFKC, zero-width strip, homoglyph fold) → language detect → length/window plan
  → [M] typed questions (closed enums, opaque IDs, abstain option, fixed order, versioned question set)
  → [D] calibrate: per-(checkpoint, dtype, question type, option-count bucket) temperature
        fitted on WAAG data; read answer_confidence = max p (NOT entropy "confidence")
  → [D] threshold table (Java, versioned, per tenant/capability) → nlGate ∈ {PASS, REVIEW, BLOCK}
  → PDP attributes (namespaced, reserved):
        context.nlStatus   OK | LOW_CONF | TRUNCATED | LANG_UNSUPPORTED | TIMEOUT | ERROR | SKIPPED
        context.nlIntent   enum string from the skill's vocabulary
        context.nlRiskPct  long 0-100      context.nlConfPct long 0-100
        context.nlGate     PASS | REVIEW | BLOCK
  → deterministic PDP decides; model evidence written into pdp_context
```

**Why `answer_confidence`.** Laya's `confidence` (1 − normalized entropy) is not calibrated by design. Its `answer_confidence` (max p) is what temperature scaling fits (JEV §5.7). Laya ships over-confident: ECE 0.466, falling to 0.081 after refit. The multilingual checkpoint ships with **no temperatures** at all. Thresholds do not transfer across implementations, dtypes or versions (JEV §5.7, §8.4).

### 6.2 Deterministic threshold table (illustrative; values come from the bake-off)

| Condition (evaluated in Java, in order) | nlGate | PDP outcome for an opted-in capability |
|---|---|---|
| `nlStatus ≠ OK` (timeout, error, truncated, unsupported language) | REVIEW, or BLOCK for tenant-marked high-risk capabilities | REQUIRE_APPROVAL (or DENY) |
| `nlIntent ∈ deny-set for this skill` and `nlConfPct ≥ τ_block` | BLOCK | DENY |
| `nlIntent ∈ deny-set` and `nlConfPct < τ_block` | REVIEW | REQUIRE_APPROVAL |
| `nlIntent = __insufficient__`, or `nlConfPct < τ_conf` (abstain band) | REVIEW | REQUIRE_APPROVAL |
| `nlIntent ∈ allowed-set` and `nlConfPct ≥ τ_conf` | PASS | **No effect.** The deterministic grant (profile, act_chain, scope, policy) decides. |

**Five invariants**
1. **Monotonic.** PASS never widens anything. It only satisfies a gate that deterministic policy already required. The model is never the sole basis of a permit (SM §4.3).
2. **Fail-closed polarity.** Opted-in permits carry `&& context.nlGate == "PASS"`. A missing attribute evaluates false, so absence denies. This only works once the strict parser (T11) is in place.
3. **The abstain band maps to REQUIRE_APPROVAL, not DENY.** Otherwise false positives break the product (IF §5 P9). The band is sized from the risk-coverage curve (§8.3).
4. **Rules outrank the model in both directions for friction.** A deterministic escalation (G6, destructive tier, taint plus egress under the Rule of Two) cannot be cleared by any model output.
5. **Replayable audit.** Record model id, weights digest, question-set version, input hash, per-option probabilities, temperature bucket, thresholds, status and latency in `pdp_context`, so every decision can be replayed. This extends TealTiger's receipt idea natively (TD §0 item 10).

### 6.3 PDP work this requires (prerequisites, IF §5)
- **REQUIRE_APPROVAL outcome (P9).** Options:
  - a third effect;
  - Cedar-style annotations such as `@advice("REQUIRE_APPROVAL")` on the determining policy (aws-dogwood dossier);
  - an approval bound to the **exact action** (params hash, expiry, approver ≠ requesting agent; TD).

  Until it exists, run REVIEW as **shadow only** (log it, still ALLOW) or as DENY for opted-in high-risk capabilities.
- **Strict parser and validator (P1).** Reject policies with unparsed fragments instead of dropping them.
- **Reserved attribute names (P5)** and a **fail-closed provider contract** (SPI signature change).
- **Parent-text read via `corr_id` (P10)**, needed for the "contributes to parent" question.
- **Evaluate-what-you-forward (P6).**

---

## 7. Alternatives that do the same job

The Jev-family encoders (Laya, Verdict) **are** a ModernBERT encoder plus a head plus calibration (SM §2.6). Their novelty is **per-request typed schemas**: options defined at call time, output that cannot go off-type, and no retraining for a new question. For WAAG the decision vocabulary is admin-authored per skill and per tenant, so that flexibility has real product value in a multi-tenant setting. It has to be weighed against the control a fixed head gives.

| Option | How it answers "what is this A2A text asking for?" | CPU cost (512 tok) | Adapts to a new skill vocabulary | Robustness | Provenance / ops | Role (Judgment) |
|---|---|---|---|---|---|---|
| **0. Deterministic** (keyword/regex, EgressClassifier recognizers on request text, entity regex, counters, taint) | Not semantic; exact | < 1–5 ms | Admin edits rules | Not evadable by wording, but blind to paraphrase | In-house | **Mandatory baseline.** Most decisions stop here. |
| **Jev-family typed encoder** (Verdict 151M / Laya 421M), fine-tuned plus calibrated | `choice`/`noul` over per-request options | Verdict ~36 ms claimed; Laya 193–580 ms measured | **Runtime** (new options, no retrain), but zero-shot is near chance until fine-tuned | Steerable; no adversarial evaluation | Young repos; Apache weights | Candidate for the S tier |
| **Fine-tuned ModernBERT-base / DeBERTa-v3-base classifier** (fixed labels) | Softmax over fixed labels | est. 20–70 ms INT8 (SM §3.3) | Retrain per new label set (hours on 1 GPU) | Moderate in-distribution, weak out-of-distribution without adversarial training | Established bases (Apache/MIT) | **Strongest candidate** where the vocabulary is stable, e.g. cross-tenant categories like {transaction, data_export, admin_change, research} |
| **NLI zero-shot** (ModernBERT-large / DeBERTa-v3-large zeroshot) | Entailment per label hypothesis | N labels × 40–200 ms (est.) | Runtime, via label text | Weak; sensitive to label wording | Apache/MIT | Label bootstrapping and cold start. **Not for enforcement.** |
| **GLiClass** (151M) | All labels in one pass | one pass | Runtime | Modest zero-shot F1 (Emotions 0.30) | Apache | Same class as Verdict's head. A zero-shot baseline. |
| **Embedding similarity** (bge/e5/gte-small, 33M) | Cosine of the text against skill description / parent text | est. 5–20 ms | Runtime | **Easy to pad**, so use one-sided only: flag low similarity, never grant on high | MIT | Cheapest "off-topic delegation" flag. Also a Praetor-style string bound (ACA §4.6). |
| **Guard models** (Prompt Guard 2 22M/86M; Qwen3Guard-Gen-0.6B; Granite Guardian 4.1-8B BYOC) | "Is this an injection or harmful?", not "is it on-task?" | PG2: 280 ms p50 loaded CPU; others need a GPU | Fixed taxonomy; Granite BYOC on a GPU | PG2 ~0% recall on plain imperative injections; adaptively breakable | PG2 carries Llama 4 terms; Qwen/Granite are Apache | Injection sub-signal only (T-class 4 in §2.1). Granite BYOC is the GPU-tier alternative to SemIf. |
| **Small LLM plus constrained decoding** (Qwen3/3.5 0.6–4B, Gemma 4, Phi-4-mini) | Rubric prompt plus `choice` grammar | CPU 0.3–3 s; GPU 30–150 ms | Runtime, via prompt | Lowest (reads attacker text as prompt) | Apache/MIT options | GPU opt-in escalation only. SemIf/JevK5 are this class done as logit readers. |

**Structural alternative for A2A (ACA §4.5, "Language Converter" firewall).** Project the delegating agent's free text onto the target skill's typed inputs, so embedded instructions have no channel to travel through. This is architecture, not detection, and it deserves a design spike alongside the model bake-off.

---

## 8. Bake-off plan on our workload

**Goal.** For each decision, find the smallest primitive that meets the acceptance gates, and prove or disprove that any model beats the deterministic baseline.

**Safety.** All third-party code and weights run only in an isolated sandbox with no network egress, pinned hashes and prior security review. This research did not run any of it.

### 8.1 Tasks (from §2.1)

| Task | Question | Hop | Label source |
|---|---|---|---|
| **T1** | Skill-purpose fit: `choice` over the per-skill vocabulary plus `__insufficient__` | A2A hop 1 | Human labels + synthetic templates |
| **T2** | Contributes to parent: `noul` + deviation `choice`, with parent text as context | A2A hop ≥2 | Paired parent/child generation |
| **T3** | Data-category / declared-purpose labels (feeding a deterministic matrix) | A2A | Human labels |
| **T4** | Instruction-bearing / injected delegation: `noul` | A2A | Attack corpus (§8.2) |
| **T5** | Entity choice among regex candidates (target binding) | A2A → MCP | Synthetic, with ground truth |

### 8.2 Datasets
- **D1. Audit-ledger replay.**
  - Source: A2A rows from `pdp_audit_log.pdp_context` (`input=` text, act_chain, resource) and `gateway_audit_log` (trace structure, parent/child pairs recovered via `corr_id`).
  - **Size is small:** about 63 A2A skill decisions locally (JEV §8.6; GG §12.1).
  - Two annotators label it; report Cohen's κ. Use it as the *realism* test set, not for training.
- **D2. Synthetic benign at scale.**
  - Drive the four sample agents and synthetic agents for the payments, GitHub, CRM, HR and infra scenarios (IF §7) over a few thousand varied task prompts.
  - Capture the real A2A texts the gateway sees. Labels come from the generating template.
  - Generate with a **different model family** from any candidate judge, to avoid self-similarity bias.
  - Target: about 5k–30k questions, which matches Laya's fine-tuning notebook scale (JEV §5.4).
- **D3. Synthetic attacks (static).** Each benign item gets variants:
  - (a) injected pre-approval or authorization fields (the Octomind pattern);
  - (b) negation and "ignore the above" (#377);
  - (c) self-labelling text ("this is a research-only request");
  - (d) label-word collisions;
  - (e) payload past token 320/512/768 and past character 2000;
  - (f) on-topic padding against embedding similarity;
  - (g) multilingual, code-switched, homoglyph and zero-width text;
  - (h) option-order and wording permutations;
  - (i) text split across `metadata.arguments.input` and text parts;
  - (j) paraphrases.

  Add public sets: AgentDojo and InjecAgent injections, MSB MCP attacks, deepset prompt-injections, ASB (ACA §5), recast as A2A delegation texts.
- **D4. Adaptive attacks.** A black-box optimizer per candidate: it rewrites malicious items to reach PASS within a query budget (e.g. 200 and 1,000 queries), following the ACA §6 methodology (2510.09023, 2606.26479). Report ASR at each budget.
- **Splits.**
  - Split by **trace** (no leakage within a journey) and hold out **whole skills and one whole domain** to test zero-shot generalization to new tenants.
  - Keep a separate calibration split for temperature fitting (out-of-fold, as SemIf does, JEV §6).

### 8.3 Metrics

| Group | Metric |
|---|---|
| Quality | Accuracy, macro-F1, AUROC per task. Confusion on the deny-set classes specifically. |
| Calibration | **ECE** (15 bins) and Brier on `answer_confidence`, before and after temperature fitting, **per dtype** (bf16 vs fp32 moved probabilities by up to 0.073; JEV §5.7). Reliability diagrams. |
| Operating points | **FP/FN at thresholds:** fix a benign friction budget (e.g. ≤ 1% BLOCK, ≤ 3% REVIEW of benign A2A hops) and report FN (malicious reaching PASS). Report the risk-coverage curve and AURC for the abstain band. |
| Robustness | Flip rate under meaning-preserving perturbations (D3 h, j). **Static attack ASR** (D3). **Adaptive ASR at budget** (D4). Accuracy as a function of payload position (truncation curve). Behaviour on unsupported languages (should give `LANG_UNSUPPORTED`, not confident garbage). |
| Latency and cost | **CPU p50/p95/p99** at 256/512/1024 tokens, 1 and 3 questions, 1/2/4/8 threads (intra-op = physical cores, inter-op = 1; the default settings cost Laya about 12×, JEV §5.6). fp32 vs INT8. Concurrency 1/8/32 on 4-vCPU and 8-vCPU nodes with no GPU. Cold load, RSS. **End-to-end effect on demo journey throughput and Tomcat thread hold** (GG §13(d)). |
| Determinism | 100 repeats per input: outputs must be identical at batch 1. Document the tolerance across ORT versions. |
| Engineering | Java ORT parity with the Python reference (max probability delta). Sidecar vs in-JVM overhead. Crash and fuzz resistance. |

### 8.4 Candidates

| ID | Candidate | Mode |
|---|---|---|
| C0 | Deterministic baseline: regex/keywords, EgressClassifier recognizers applied to request text, entity regex, counters | — |
| C1 | bge-small embedding similarity (ONNX, in-JVM) | zero-shot |
| C2 | ModernBERT-large-zeroshot NLI; GLiClass-modern-base | zero-shot |
| C3 | Verdict 151M ONNX | zero-shot → head re-fit + recalibrated |
| C4 | Laya typed-decisions and multilingual | zero-shot → RLCD fine-tune on D2 + temperatures |
| C5 | Own ModernBERT-base / DeBERTa-v3-base fixed-label head | fine-tuned + calibrated |
| C6 | Prompt Guard 2 22M/86M (T4 only) | off-the-shelf + threshold fit |
| C7 (GPU, optional) | SemIf Qwen3.5-4B, JevK5 | per-workload temperatures |
| C8 (reference only, **synthetic data only**) | Hosted Jev | upper-bound comparison |

### 8.5 Acceptance gates (proposals, to be ratified by the team)

A model ships to **shadow** in a customer environment only if, on the held-out test set at the chosen operating point, all of the following hold:
1. It beats C0 by a meaningful margin (proposal: ≥ 15 points recall on the deny-set at the same benign friction budget). **If not, ship C0 and stop.**
2. ECE ≤ 0.05 after calibration, in the served dtype.
3. CPU p99 ≤ 150 ms for 1 question at 512 tokens on 2 dedicated cores, and journey throughput drops by less than 5%.
4. Static-attack FN ≤ 10%. **Adaptive ASR is reported, not gated.** We assume it is high, and the monotonic design (§6) is what contains it.
5. Output is 100% deterministic at batch 1. No silent truncation: every truncation raises a status.

Shadow → enforce (REVIEW/BLOCK live) requires, in addition: REQUIRE_APPROVAL shipped, the strict parser shipped, and 2–4 weeks of shadow data with an observed benign friction rate under budget.

### 8.6 Timeline (estimate)

| Week | Work |
|---|---|
| 1 | D1 labelling, D2 generation harness, question vocabularies per skill, C0 baseline |
| 2 | Zero-shot runs C1–C4 and C6, plus latency matrix on customer-like CPU nodes |
| 3 | Fine-tune and calibrate C3/C4/C5; Java ORT parity for the best CPU model |
| 4 | D3/D4 attacks, operating-point selection, report with a go/no-go per task |

---

## 9. Product view

**What to claim, and what not to claim**
- **Pitch:** "On-the-wire, identity-bound semantic signals with deterministic enforcement."
- **Do not pitch** "Jev inside" or "AI decides access".
- **Differentiation from LangChain + SemIf.** That stack runs inside one agent harness (S02; VL §3.14). WAAG sees the cross-vendor chain, the verified act_chain and the capability scope, which harness guards do not (JEV §9.4).

**The CEO's premise, pressure-tested (S03 §A)**

| Element of the premise | Holds? | Why |
|---|---|---|
| "Light model in customer env" | Yes | Apache-licensed encoders, CPU-capable |
| "For every request" | **No** | Only A2A requests carry text. On CPU, only ~150M-class models fit the budget. |
| "Memory" | **Reframed** | It is deterministic chain context (parent text via `corr_id`, per-trace counters, taint) and not model memory. Model or vector memory adds poisoning and leakage risk (SM §7; TD). |
| "We already have the data" | Partly | The data exists, but it is mostly **not on the decision path** today (IF §6.3). And there are only about 63 A2A decisions locally, so synthetic generation is required. |

**Customer pull.** Netskope Q15 ("tighten or block by risk or intent") and Zscaler Q6 (IF §6):
- The honest near-term answer: deterministic intent and behaviour controls, plus an optional semantic signal that can only tighten.
- That is a stronger, auditable story than a model-as-judge.

**Packaging**
- An optional "semantic signal pack", **off by default**.
- A **shadow dashboard** showing what it would have flagged, before any enforcement.
- Per-tenant vocabularies authored in the policy assistant, with human review before enabling. This avoids the `/chat/save` auto-enable pattern (GG §6.12).

---

## 10. Verified vs vendor claims (for anything we say externally)

**VERIFIED**
- Jev is hosted; its API primitives and limits; the adversarial-content admission on the jaggedness page (JEV §2.2, §2.4).
- Repo metadata for Laya, OpenJev and SemIf (JEV §10).
- Laya's sequence layout and confidence formulas (source code).
- Laya weights are safetensors with no ONNX. Verdict ships ONNX and a calibrator (HF API, 2026-09-26, §0).
- WAAG facts from GG §12–13.

**VENDOR CLAIM**
- Every latency and accuracy table in the Laya, Verdict, SemIf, JevK5, CLM and OpenJev READMEs. The Laya CPU numbers have raw result files committed but have not been reproduced by us.
- Jev's 70–500 ms and "0% hallucination".
- Verdict's 35.6 ms CPU figure.

**SECONDHAND**
- TypeSafe's "no on-prem, US-only" written answers (factorysemantics PR #71).
- The Octomind test (VentureBeat).
- DCVC as lead investor and the valuation.

**ESTIMATE (ours)**
- All CPU INT8 figures for ModernBERT-base / DeBERTa marked "est." (SM §3.2).
- Token counts for 2000 characters.

---

## 11. Open questions
1. Who exactly sent the "Jev CEO" message, and what is their relationship to TealTiger/Dakera? (JEV §2.6; TD Q2)
2. Can the console forward the human's original request, or a registered workflow id, at hop 1? Without it, every model judges a paraphrase (IF §8 Q1; ACA §11 Q1).
3. What CPU hardware do target customers run (AMX/VNNI, Graviton, vCPU budget)? The answer decides the INT8 in-JVM tier versus a sidecar.
4. What are the real CPU latencies of Verdict ONNX and a ModernBERT-base INT8 head at our text lengths under concurrency? This is unmeasured anywhere (JEV §11 Q4; SM §10 Q2).
5. Legal: may we ship JevK5's distilled weights, or Qwen3.5-derived weights, inside a commercial appliance? The Qwen licenses are not yet checked (JEV §11 Q9).
6. Which REQUIRE_APPROVAL mechanics (effect vs annotation, approver UX, expiry, exact-action binding) fit the console, and who approves on autonomous chains?
7. Is a fixed cross-tenant vocabulary (a fixed head) enough, or do tenants need per-skill runtime vocabularies (typed per-request heads)? This decides between C5 and C3/C4.
8. Can we source enough labelled A2A data (1k–30k) from design partners in shadow mode, and under what data-handling terms?
9. Is the gateway single-instance for the foreseeable future? The parent-text lookup through `InFlightRequestRegistry` is per-JVM (GG §15 Q2).
