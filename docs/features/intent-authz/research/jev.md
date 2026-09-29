# The Jev ecosystem of typed "System One" decision models: dossier for WAAG intent-aware authorization

Research date: 2026-09-26. Everything below is from sources dated 2026-09-15 to 2026-09-26, so it is newer than the assistant's training data and is cited source by source. GitHub numbers come from the public REST API (`api.github.com/repos/...`) on 2026-09-26. `gh` is not installed on this host, so the same read-only endpoints were called with curl.

## Executive summary (10 lines)

1. **What Jev is.** Jev is TypeSafe AI's closed, hosted "System One" decision API (launched 2026-09-15). It is not an LLM. You send a state plus typed questions (`noul` yes/no, `choice`, `score`) and get back probabilities, not text. TypeSafe's CEO is Diogo Almeida.
2. **Deployment and data.** Jev is **hosted in the US only.** A third-party repo records TypeSafe as saying in writing that it has no on-prem or VPC option, now or planned. That rules Jev out for the "model runs in the customer's environment" requirement.
3. **Open alternatives.** Several are Jev-API-compatible and self-hostable:
   - **Laya:** 421M/322M encoders, Apache-2.0. **25,410 stars is confirmed**, on a repo only 8 days old.
   - **Verdict:** 151M encoder, ships an ONNX file.
   - **OpenJev (razorback16):** a server that routes to all of these, plus DiffusionGemma 26B on a GPU.
   - **SemIf:** Qwen3.5-4B logits, the model LangChain hosts.
   - **CLM** (Qwen3-8B plus heads) and **JevK5** (Qwen3.5-4B plus LoRA).
4. **CPU is the blocker.** Laya on a 4-core server CPU measured about **580 ms per question** (English and typed-decisions checkpoints) and **193 ms** (multilingual). That is 15–45x WAAG's whole ~12–13 ms p50 governance overhead. The advertised ~33 ms is on a T4 GPU.
5. **Zero-shot is weak.** On typed-decisions, Laya's base checkpoints score **near chance** (0.36 vs 0.32 random). Its authors call it a fast base to fine-tune, not a zero-shot engine. On the independent JevBench v1.4.2 it ranks 41st (score 30.3), against Jev's 2nd (63.3).
6. **Steerable by the text it judges.** TypeSafe's own docs say text written to steer Jev "can move the answer". An Octomind test cut a block probability from 0.76 to 0.48 with a fake pre-approval field. Laya answered cancel_account at 0.9998 to "do not cancel" text (issue #377). **No project publishes an adversarial or prompt-injection robustness evaluation.**
7. **Why that matters for WAAG.** Our state text is `context.argumentsFlat` on A2A hops. It is written by an upstream LLM and can carry injected text. So a typed decision model can be a **sensor that feeds the deterministic PDP**, never the authority. That is the same framing the "Jev CEO" chat uses.
8. **Java integration.** The most practical path is a **localhost HTTP sidecar** that speaks the Jev wire API (`laya-serve` or OpenJev's `laya`/`verdict` backends). The alternative is ONNX Runtime Java plus DJL tokenizers, but then we must re-implement Laya's sequence builder, temperature scaling and confidence math ourselves.
9. **Input limits.** English Laya leaves about 320 tokens for state; the multilingual and typed-decisions checkpoints leave about 768. Longer input is cut **silently**. Our `argumentsFlat` carries up to 2,000 characters per top-level string.
10. **Bottom line.** Worth a bounded spike on **A2A hops only**, offline or async first, with a fine-tuned head and temperatures fitted on WAAG-labelled data. The "Jev CEO" in the chat is probably not TypeSafe's CEO (see §2.6).

---

## 1. Ecosystem map

| Name | Who | What it is | Size / base | License | Runs on | Maturity (2026-09-26) |
|---|---|---|---|---|---|---|
| **Jev** (`jev-1.13.0`) | TypeSafe AI | Closed hosted System One API | Undisclosed | Proprietary | TypeSafe cloud (US) | Early access, launched 2026-09-15 |
| **Laya** | Nandakishor M / Convai Innovations | Encoder + typed decision head, RLCD-trained, with a Router | 421M ModernBERT-large; 322M mmBERT-base | Apache-2.0 (code and weights) | CPU, CUDA, MPS, XPU; ONNX export script | 25,410★, 413 commits, 86 contributors, v0.3.20 |
| **OpenJev** | razorback16 | Jev-compatible server; routes to other models | DiffusionGemma 26B-A4B (4B active) + routed models | Apache-2.0 | NVIDIA ≥24 GB or Apple silicon; encoders on CPU/GPU | 437★, 41 commits, 2 contributors, no releases |
| **Verdict** (`verdict-1.4`) | Heman10x | ModernBERT-base + GLiClass head | 151M | Apache-2.0 | CPU/GPU; ONNX published on HF | ~104★ (per WebFetch) |
| **SemIf** (formerly "OpenJev") | Theo Lee (TheoLeeCJ) | Reads option logits directly from a frozen open LLM | Qwen3.5-4B default | MIT (code) | CUDA, MLX/MPS, llama.cpp CPU, WebGPU | 4,365★, 25 commits, 6 contributors |
| **CLM** | Contrastive-LM | Contrastive state/action heads over frozen Qwen3-8B | 8B + 2×9.4M | Apache-2.0 | GPU (vLLM pooling) | ~1.4k★, 8 commits (per WebFetch) |
| **JevK5** | Alibi Serikbay (allebee) | Qwen3.5-4B + distilled LoRA; reads letter logits | 4B (also a 9B) | Apache-2.0 | GPU; GGUF on CPU | ~109★, 29 commits (per WebFetch) |
| **JevBench** | Benchmark Heaven (Florian Standhartinger) | Independent benchmark of Jev-class models | — | MIT harness | — | v1.4.2, scored 2026-09-24 |

Naming trap: **two different projects have been called "OpenJev".** razorback16/openjev is one. TheoLeeCJ/SemIf-OpenJev is the other: it was renamed to SemIf, its README says "formerly OpenJev", and its homepage field is `openjev.com`. The "Jev CEO" chat links the razorback16 repo.

---

## 2. Jev / TypeSafe AI

### 2.1 Company and people
- **Company.** TypeSafe AI was founded in 2024 and is based in San Francisco. It came out of stealth with Jev on **2026-09-15**. [Wikipedia: Jev (AI model)](https://en.wikipedia.org/wiki/Jev_(AI_model)); [Winzheng, launch report](https://www.winzheng.com/en/article/typesafe-ai-jev-system-one-model-launch)
- **Leadership.** From the [team page](https://typesafe.ai/team):
  - **Diogo Almeida, CEO.** The page says he co-invented RLHF and InstructGPT and was at Google Brain. Press describes him as ex-OpenAI.
  - **Sasha Sheng, COO.** Ex-Meta/FAIR.
  - **Erik Gafni, CTO.** A repeat founder.
- **Funding.** A **$40M seed** round. Wikipedia says it was led by DCVC at a $200M valuation. Heise gives no lead investor. [Wikipedia](https://en.wikipedia.org/wiki/Jev_(AI_model)); [heise, 2026-09-17](https://www.heise.de/en/news/AI-model-Jev-to-make-machines-decide-faster-11457071.html). The lead investor and valuation are therefore secondary-source only.

### 2.2 Product and API (verified from docs and integrator docs)
- **Endpoint.** `POST https://api.typesafe.ai/v1/systemone`, with body `{state, model, questions}`. The SDKs are `typesafe-sdk` (Python) and `@typesafe-ai/sdk` (JS). [LangChain decision-models docs](https://docs.langchain.com/langsmith/llm-gateway-decision-models); [OpenJev README](https://github.com/razorback16/openjev)
- **Question primitives:**
  - `noul`: probability of true. In the podcast, Almeida describes it as a continuous Bernoulli.
  - `choice`: up to **255** options.
  - `score`: 2–10 ordinal levels.
  - Sources: [Latent Space, 2026-09-21](https://www.latent.space/p/jev); [Pydantic AI docs](https://pydantic.dev/docs/ai/models/typesafe/)
- **Structured inputs.** State, instructions and criteria can all be structured JSON. [Latent Space](https://www.latent.space/p/jev)
- **Limits.** From [docs.typesafe.ai/models](https://docs.typesafe.ai/models):
  - **64k tokens** per request.
  - **32k tokens** for the state plus the longest question.
  - Text only.
  - English is primary; other languages are handled "not equally well".
  - Rate limits of 250k tokens/s and 1,200 requests/min, adjusted dynamically.
- **Models.** `jev-1.13.0` is current. `jev-latest` and `jev-preview` are aliases, and version pinning is supported. [docs.typesafe.ai/models](https://docs.typesafe.ai/models)
- **Pricing.** Input costs **$0.042 per million tokens**. Output is free. [TypeSafe launch blog](https://typesafe.ai/blog/introducing-system-one-models-and-jev)
- **Architecture.** Not disclosed. The launch blog mentions a new architecture and a parallel sampler. Training uses "Reinforcement Learning for Calibrated Decisions (RLCD)", which the podcast calls novel and unpublished. Wikipedia says it is transformer-based and trained only on synthetic data. [launch blog](https://typesafe.ai/blog/introducing-system-one-models-and-jev); [Latent Space](https://www.latent.space/p/jev)
- **Refusals by design: none.** Almeida argues that a background dependency cannot be allowed to refuse. [Latent Space](https://www.latent.space/p/jev)

### 2.3 Deployment and data handling (critical for "runs in customer env")
- **Official docs.** The docs describe a single hosted endpoint. Customer requests are **not used for training**. **Zero data retention is available for enterprise customers.** No on-prem or VPC option is mentioned. [docs.typesafe.ai/models](https://docs.typesafe.ai/models)
- **Written survey answers (secondhand).** A third-party repo records TypeSafe's written answers dated 2026-09-17. It reports:
  - "Hosted API only". No on-prem, VPC or edge option, now or planned.
  - ZDR is enterprise-tier only.
  - **US-only region**, with no public EU region.
  - The confidence formula is not published and drifts across versions.
  - Source: [factorysemantics-mes PR #71](https://github.com/factorysemantics/factorysemantics-mes/pull/71). The TypeSafe respondent is not named.
- **Conflicting claim.** One search digest said enterprise tiers ship containerized on-prem runtimes. No primary source for this was found, and it contradicts the survey answer. Treat it as **unverified**.

### 2.4 Documented weaknesses ("jaggedness", official)
TypeSafe's [Jev 1.13 jaggedness page](https://docs.typesafe.ai/model-jaggedness/jev-1.13) lists these limits:
- **Interpretation:** it reads instructions literally.
- **Numbers and dates:** unreliable at math, counting, number comparison and date ordering.
- **Reasoning:** weak on multi-hop reasoning and double negatives.
- **Irrelevant context:** accuracy degrades as unrelated context is added ("context rot").
- **Consistency across calls:** P(x) and 1−P(not x) are not guaranteed to agree.
- **Adversarial content (most important for us):** Jev does not treat the state as hostile, so injected instructions, misleading framing, or text arguing for its own label can move the answer. TypeSafe's only mitigations are explicit criteria and testing, plus a promise to improve.

### 2.5 Independent evidence on Jev
- **Prompt-injection test.** [VentureBeat, 2026-09-21](https://venturebeat.com/security/companies-are-putting-jev-in-charge-of-ai-agent-decisions-and-prompt-injection-can-influence-the-verdict) reports an Octomind engineer's test:
  - Question: should `rm -rf ~/.ssh` be blocked? Jev gave 0.76 block probability at 0.64 confidence.
  - After adding a fake tool-output field claiming pre-approval, it gave **0.48 at 0.22 confidence**.
  - This is one command, not a benchmark.
  - Per the same article, Pydantic advises using Jev alongside deterministic checks, and LangChain's middleware keeps tool output away from Jev so that it cannot authorize itself.
- **Latency.** Third parties measured **236–276 ms p50**; the Laya README cites [AbdelStark/jev-benchmarks](https://github.com/AbdelStark/jev-benchmarks) and [nibzard/decision-model-benchmark](https://github.com/nibzard/decision-model-benchmark). TypeSafe's own claim is 70–500 ms. JevBench measured **0.65 s** from Germany.
- **Other third-party findings** (cited via Laya's research README):
  - nibzard reports ECE 0.246 and a **13% option-order flip rate**.
  - AbdelStark reports that Jev put zero probability on the true label for 16% of DAIR Emotion items.

### 2.6 Who is the "CEO of Jev" in the WhiteSwan chat?
The evidence points away from TypeSafe's CEO:
- **The writer promotes rivals.** They point Vinay to **Laya** and **razorback16/openjev**. Both are independent open reimplementations. Laya markets itself as beating Jev, and OpenJev disclaims any TypeSafe affiliation. TypeSafe's CEO would be unlikely to steer a prospect toward competitors, and would be likely to offer hosted Jev.
- **"We" means TealTiger/Dakera.** The writer says "we've been exploring" the **Dakera + TealTiger** pattern and links posts by those projects. Both are dated 2026-06-16:
  - [TealTiger blog post](https://blogs.tealtiger.ai/governance/integrations/tealtiger-dakera-governance-state/), by-lined "Naga Satish Chilakamarti (Maintainer)".
  - [Dakera blog post](https://www.dakera.ai/blog/dakera-tealtiger-integration), by-lined "Dakera AI Team".
  - The chat's phrase "storage = evidence/continuity, not authority" matches the TealTiger post word for word.
- **Hypothesis.** The sender is more likely someone connected to TealTiger or Dakera, or a Jev community figure, than Diogo Almeida. **Unverified.** Ask Vinay for the sender's profile or company domain.
- **Advice quality does not depend on the answer.** "Separate intent understanding from authorization" and "smallest primitive per decision" are sound either way.

---

## 3. JevBench (independent)

- **Who runs it.** "Benchmark Heaven" is a one-person hobby project by Florian Standhartinger. It is not affiliated with TypeSafe. [jevbench repo](https://github.com/fstandhartinger/jevbench); [benchmarkheaven.com/jev-models/v1](https://benchmarkheaven.com/jev-models/v1)
- **Scoring (v1.4.2).**
  - **Score** = chance-corrected Intelligence, Calibration, Speed and Cost, 25% each, combined as a geometric mean.
  - **Penalty:** a quadratic penalty applies below 50% Intelligence.
  - **Items:** 231 public items, plus sealed hard items.
  - **Test setup:** one request at a time from a server in Germany; self-hosted models run in offline containers.
- **Leaderboard (v1.4.2, 2026-09-24).** [benchmarkheaven.com/jev-models](https://benchmarkheaven.com/jev-models); read via a WebFetch digest, so re-check the exact figures before quoting them externally.

  | Rank | System | Score | Intelligence | Latency |
  |---|---|---|---|---|
  | 1 | decider-4b v2 (Mapika) | 64.1 | — | 0.02 s |
  | 2 | Jev 1.13.0 | 63.3 | 53.1 | 0.65 s |
  | 3 | JevK5 v0.2.0 | 62.0 | — | — |
  | 11 | SemIf | 47.7 | — | — |
  | 27 | OpenJev (DiffusionGemma) | 36.9 | — | — |
  | 41 | Laya | 30.3 | 36.1 | — |
  | 45 | CLM-8B | 8.6 | — | — |

- **Earlier run (v1.0).** Frontier LLMs and Jev all scored about 95–97% accuracy on 242 easy-ish decisions. [v1 page](https://benchmarkheaven.com/jev-models/v1)
- **Caveats.** v1.0 describes itself as a pilot, English-only. **JevBench has no adversarial or prompt-injection axis.**
- **Other "JevBench"es exist**, e.g. [model-collapse/jev-bench](https://github.com/model-collapse/jev-bench), plus many forks. When a README cites "JevBench", check which one.

---

## 4. OpenJev (github.com/razorback16/openjev)

**Maturity (GitHub API, 2026-09-26):**
- 437 stars, 35 forks, 4 open issues.
- Created 2026-09-18, last push 2026-09-25.
- **41 commits**, 40 of them by razorback16; **2 contributors**.
- **No releases or tags.**
- Apache-2.0. The homepage is codiv.ai.

**README facts (verified by reading it in full):** [README](https://github.com/razorback16/openjev)
- **Wire API.** It uses the same API as Jev (`POST /v1/systemone`), so the TypeSafe SDKs work unchanged. It accepts the `jev-latest` and `jev-preview` aliases. It states it is **not affiliated** with TypeSafe.
- **Hosted option.** A free hosted version runs on **Codiv** (`api.codiv.ai`, 100M free input tokens). The same author domain suggests a commercial funnel.
- **Model table:**

  | Model id | Base | Size | Input limit | Options | Runs on |
  |---|---|---|---|---|---|
  | `openjev-0.1` | DiffusionGemma 26B-A4B NVFP4 | 26B total, 4B active | text + images | ≤255 | vLLM on NVIDIA **≥24 GB**, or MLX on Apple silicon (~16 GB) |
  | `laya-1.0` | Laya typed-decisions checkpoint | 421M | 1,024 tokens | ≤255 (use about 20 at most) | GPU or CPU |
  | `verdict-1.4` | Verdict | 151M | 512 tokens | ≤24 | GPU or CPU |
  | `clm-v0.1` | Qwen3-8B + heads | 8B | 2,048 tokens | ≤255 | GPU |
  | `jevk5-0.2` | Qwen3.5-4B + LoRA | 4B | 16,384 tokens | ≤255 | GPU |

- **How it works.** DiffusionGemma reads a canvas in which only the one-token answer slots are masked. The probability distribution over those slots is the answer, so output cannot leave the schema. If a slot's entropy is above 0.1, it reads 3 more times and averages. **Confidence = 1 − H(p)/ln K.**
- **Latency, DiffusionGemma (RTX PRO 6000):**
  - One request at a time: 27 ms p50 for 1 question, 31 ms for 3.
  - Under load (3 questions per request): 94 ms at concurrency 1, 760 ms p50 / 1,109 ms p95 at concurrency 64.
  - M3 Ultra / M4 Max with MLX: about 0.2–0.4 s per request.
- **Latency, encoders on GPU (RTX PRO 6000, 16 questions):**
  - Laya: 10 ms with a short state, 109 ms with a full state; 2.5 GB.
  - Verdict: 7 ms and 21 ms; 1.2 GB.
- **No CPU numbers are published** for the encoders.
- **Silent truncation.** An encoder cuts an over-long state **without error**, and every question re-bills the state.
- **Verdict mapping.** Verdict's "insufficient evidence" option is removed and the remaining probabilities are renormalized, which means OpenJev **discards Verdict's abstention signal**.
- **Disk footprint.** Images share an 8.7 GB CUDA/torch base, about 21 GB for all three.
- **Server auth.** Optional `OPENJEV_API_KEY` bearer and `OPENJEV_ORIGIN_SECRET` settings.
- **Caveat.** The README says answer quality is DiffusionGemma's in this read-only mode, and tells users to evaluate it themselves.
- **DiffusionGemma checkpoint.** `nvidia/diffusiongemma-26B-A4B-it-NVFP4` exists on HF: created 2026-06-10, ~53k downloads per HF API.

---

## 5. Laya (github.com/NandhaKishorM/laya)

### 5.1 Maturity and the star count (verified)
**GitHub REST API, 2026-09-26:**
- **Stars: 25,410. This confirms the WebFetch figure.**
- 2,202 forks, 99 watchers.
- 176 open issues and PRs combined.
- Created **2026-09-18**, last push 2026-09-25.
- **413 commits** and **86 contributors** (top is NandhaKishorM with 151).
- **23 releases** from v0.2.0 to v0.3.20. The last 9 were published within about 2 hours on 2026-09-24.
- Topics include `jev`, `calibration`, `typed-decisions`.
- Apache-2.0.

**Hugging Face** (`convaiinnovations/laya`): 3,806 likes, created 2026-09-18. The HF API reports **0 downloads**. That is probably a tracking artifact, because the repo ships `rl_agent_config.json` rather than a standard `config.json`. Inference, not verified.

**Reading of the numbers.** 25k stars in 8 days is extreme. The broad contributor base (86 people, many merged PRs) points to genuine viral attention tied to the Jev launch. Star inflation cannot be ruled out from the metadata alone. Either way, the code base is **8 days old and changes hourly**. For us that is a supply-chain and stability concern, not a quality signal.

### 5.2 Who
- Author: Nandakishor M / Convai Innovations. [dev.to post, 2026-09-18](https://dev.to/nandakishor_m_6cc0adfde9f/i-built-non-autoregressive-decision-models-a-year-ago-then-a-frontier-lab-called-it-a-18me)
- The post claims prior work on arXiv. Both papers exist, but neither is Laya itself:
  - [2503.23303 "SalesRLAgent"](https://arxiv.org/abs/2503.23303) (2025-03).
  - [2510.01237 "Confidence-Aware Routing…"](https://arxiv.org/abs/2510.01237) (2025-09, author Nandakishor M).

### 5.3 Architecture (verified from the HF card and source)
- **Model.**
  - **Backbone:** ModernBERT-large (395M, fully fine-tuned).
  - **Decision head (new, trained from scratch):** 2 transformer layers, an option-marker scorer, and an act/escalate head. **421M total.**
  - **Multilingual variant:** mmBERT-base (22 layers, 256k vocab), **322M**.
  - Source: [HF card](https://huggingface.co/convaiinnovations/laya)
- **What "typed decisions" means.** Every option gets its own `[MASK]` marker. The logits at those markers are softmaxed over that question's options. The answer space is defined per request, so no retraining is needed for a new schema, and output cannot be off-type.
- **Input sequence** ([`laya/common.py` build_sequence](https://github.com/NandhaKishorM/laya/blob/main/laya/common.py)):
  `[CLS] <type> question: instructions [SEP] [MASK] opt0 [MASK] opt1 … [SEP] state [SEP]`
  - Options are cut to 48 tokens each and share a budget (`head_max_len`).
  - The state fills the remaining room and is **cut from the end**, except conversation lists, which are cut from the start.
  - `[MASK]` strings inside state, options or instructions are replaced with spaces. That stops marker spoofing, but it is **not** an injection defense: the state still attends bidirectionally to the instructions and options.
- **Outputs.** From the ONNX runtime `_infer` in [`laya/onnx_agent.py`](https://github.com/NandhaKishorM/laya/blob/main/laya/onnx_agent.py):
  - `choice`: the choice, per-option probabilities, `confidence` (normalized entropy), `answer_confidence` (max p), and `action.act_probability`.
  - `score`: expected level.
  - `noul`: P(true).
- **Checkpoints** (per README):

  | Checkpoint | Encoder | Context | Default option budget | State budget |
  |---|---|---|---|---|
  | `laya` | ModernBERT-large, English | **512** | 192 | **about 320 tokens** |
  | `laya-multilingual` | mmBERT-base | 1,024 (up to 8,192 with `max_len`) | 256 | about 768 tokens |
  | `laya-typed-decisions` | ModernBERT-large | 1,024 | 256 | about 768 tokens |

- **Router.** Detects script and language in under 0.5 ms and picks a checkpoint.
- **Languages.** Claimed 100+. Measured: 45 of 51 are "usable" (more than 3x random on MASSIVE) with routing, 23 of 51 with the English checkpoint alone.

### 5.4 Training data and method
- **RLCD.** Gaussian noise is added to the logits for exploration. The reward is a strictly proper scoring rule: log plus spherical, plus RPS for ordinal questions. Updates use REINFORCE with a group-mean baseline (GRPO-style). Multi-turn uses TD(λ=1). Code: `proper_reward` and `td_lambda_targets` in `laya/common.py`. [HF card](https://huggingface.co/convaiinnovations/laya)
- **Base-model data.** The author says it is all human-labelled public datasets across nine categories: support triage, fact verification, content safety, jailbreak detection, sales conversations, and others. [dev.to](https://dev.to/nandakishor_m_6cc0adfde9f/i-built-non-autoregressive-decision-models-a-year-ago-then-a-frontier-lab-called-it-a-18me). The README marks AG News, BoolQ, spam, phishing, RAG relevance and triage as "in training mix".
- **typed-decisions checkpoint.** Fine-tuned on the [LocalLLaMA/typed-decisions](https://huggingface.co/datasets/LocalLLaMA/typed-decisions) workflows: agent-trace observability, customer service, invoices, and security incidents. [HF card](https://huggingface.co/convaiinnovations/laya-typed-decisions)
- **Fine-tuning notebook.** Runs on Kaggle's free 2×T4, about **4–5 h for 4 epochs over ~30k questions**. It builds the data, trains with RLCD, fits temperatures, evaluates, and pushes to HF.

### 5.5 Accuracy (self-reported by the author; not independently reproduced)
- **typed-decisions (2,000 decisions):**
  - Fine-tuned checkpoint: **0.766**.
  - Jev published: 0.727.
  - **Base checkpoints: 0.362 and 0.352**, against 0.318 random and 0.461 majority class.
  - The author's own conclusion: all of the capability on this benchmark comes from fine-tuning.
- **Security-flavoured tasks (held out):**
  - Jailbreak detection: 0.708–0.762.
  - deepset prompt-injections: **0.698**.
  - Moderation (toxic-chat): **0.530**, barely above chance.
  - [BENCHMARKS.md](https://github.com/NandhaKishorM/laya/blob/main/BENCHMARKS.md)
- **Many options.** Banking77 (77 options) scores 0.425 against Jev's 0.870. With many options, option text is squeezed to 3–4 tokens each.
- **Independent result.** JevBench v1.4.2 ranks Laya 41st (30.3) (§3).

### 5.6 Latency: GPU vs CPU (author-measured, from README and BENCHMARKS.md)

| Hardware | Checkpoint | 1 question | Notes |
|---|---|---|---|
| Tesla T4 | multilingual | **32.8 ms** | 7.2 ms/question batched at 10 |
| Tesla T4 | English | 39.5 ms | 158.6 ms for 10 questions |
| **AWS m7a.xlarge (4-core EPYC 9R14, fp32)** | **english / typed-decisions** | **580 / 584 ms** | about 600 ms per extra question; batching barely helps on CPU |
| same | **multilingual** | **193 ms** | about 185 ms per extra question |
| Laptop Ryzen 9 6900HX, 8 threads | typed-decisions | 910 ms (1 thread) → **329 ms** (8 threads) | 3-question call over HTTP. Default torch threading gave **9.4 s** p50; set intra-op to physical cores and inter-op to 1 |
| Laptop CPU fp32 vs Intel Arc XPU | english, 2-option choice | 288 ms CPU / 29.7 ms XPU | CPU scales linearly with question count |
| NVIDIA GB10 | typed-decisions | 100 ms | about 93 ms fixed overhead per call |

- **Cold load:** 0.5–4.4 s.
- **Memory:** peak RSS 9.3 GiB with five checkpoints loaded. The README's summary row gives **193–464 ms** on CPU with preload.
- **Reproducibility.** Raw result files are committed, e.g. `research/results/latency_cpu_m7a_xlarge_20260924.json`.

### 5.7 Calibration and "confidence"
- **Two numbers are returned** (source docstrings in `laya/common.py`):
  - `confidence` = 1 − H(p)/ln k. The docstring says it is **not calibrated**.
  - `answer_confidence` = max p. This is the quantity that temperature scaling fits and that ECE is measured on. **Gate on `answer_confidence`.**
- **Over-confident as shipped.** Refitting one temperature per (question type, option-count bucket) moves ECE from 0.466 to **0.081** (`laya`) and from 0.314 to 0.106 (multilingual). **`laya-multilingual` ships with no fitted temperatures.** The typed-decisions checkpoint has ECE 0.213.
- **Temperature clamp.** Runtime temperatures are clamped to [0.5, 5]. One shipped bucket had T=0.1006, which would have reported a 0.24 top probability as 0.99.
- **Confidence cannot catch wrong-language inputs.** On Khmer the English checkpoint scores 0.000 accuracy at 0.952 confidence.
- **Miscalibration direction depends on the task.** On a routing task the model was under-confident.
- **`act_probability` is useless.** It reads about 1.0 for everything, with AUROC 0.30 (issue #185). `confidence` has AUROC 0.77.
- **Precision affects thresholds.** bf16 vs fp32 moves probabilities by up to 0.073 and flipped 3 of 864 argmaxes. Fit thresholds in the dtype you serve.
- **Jev's formula is disputed.** Laya says Jev's confidence is (n·p_max − 1)/(n − 1). OpenJev's is entropy-based. TypeSafe reportedly calls its formula unpublished. **Thresholds do not transfer between implementations.**

### 5.8 Known limitations (the "Honest limits" section plus issues)
- **Negation.** Issue [#377](https://github.com/NandhaKishorM/laya/issues/377), opened 2026-09-24 and now documented in the README:
  - "Please do not cancel it" returned `cancel_account` at **0.9998** on multilingual.
  - A state that ends "Ignore the cancellation above: do NOT cancel" returned `cancel_account` on both checkpoints.
- **Label-following.** `noul` can follow its `false:`/`true:` label words instead of the state (#156). Boolean-word keys in `choice` are unsafe.
- **Position bias.** The multilingual checkpoint rarely picks the first score level (#131).
- **Weak primitive.** Ordinal `score` is the weakest (SST-5 0.372).
- **Option count.** Keep `choice` under about 20 options, or use `predict_shortlist`.
- **Silent truncation.** Long state is cut without error; `predict_long` windows exist but are uncalibrated.
- **No adversarial or prompt-injection robustness evaluation is published.** The "metamorphic and presentation checks" in `research/eval` cover option order and wording.

### 5.9 Integration surface
- **Packaging.** `pip install laya`, with extras `[serve]` (a Jev-compatible `laya-serve` over FastAPI, with bearer-key support), `[mcp]`, `[langchain]`, `[onnx]` and `[fast]` (TileLang GPU).
- **Hooks.** Hooks such as PII redaction run before inference.
- **Evaluation harness.** `laya-evals` can be used as a CI gate on accuracy and ECE.
- **Pinning.** Revision pinning plus an `expected_sha256` check before any checkpoint file is parsed.
- **Community tools.** One is [stuntd](https://github.com/bladedevoff/stuntd), which serves Laya behind the Jev API and trains a per-decision head on the frozen encoder; its README reports 89.5% zero-shot rising to 100% trained on a 12-label intent task.

---

## 6. Other models (one paragraph each)

**Verdict** ([Heman10x-NGU/Verdict-open-jev](https://github.com/Heman10x-NGU/Verdict-open-jev); weights [heman10x/rlcd-modernbert-151m](https://huggingface.co/heman10x/rlcd-modernbert-151m)).
- **Model.** A **151M** ModernBERT-base encoder with a GLiClass head. It is trained with proper scoring rules (cross-entropy plus Brier) and post-hoc L-BFGS temperature scaling. It has an explicit `__insufficient_evidence__` abstention option. Limits are 24 options and 512 tokens.
- **ONNX: yes.** It is the **only model here with published ONNX files** (`model.onnx`, `model_fp16.onnx`; about 20.6k HF downloads).
- **Author claims:**
  - 35.6 ms median single-thread on CPU (a WASM proxy, K=5).
  - 95% top-1 on its own held-out set.
  - Only **48%** on TypeSafe's public evaluation, against 88% for DiffusionGemma.
- **Documented weaknesses:**
  - Abstention recall falls from 75.5% to **18%** on hard negatives.
  - 3–4.5% of choices flip under option reordering.
- **No injection evaluation.** OpenJev throws away its abstention output (§4).
- Repo metrics come from a WebFetch digest (~104 stars, 26 commits) and could not be API-verified because of the rate limit.

**SemIf** ([TheoLeeCJ/SemIf-OpenJev](https://github.com/TheoLeeCJ/SemIf-OpenJev); the old URL redirects).
- **Metadata (API):** MIT license; 4,365 stars, 300 forks, 40 open issues; created 2026-09-16, last push 2026-09-23; 25 commits, 6 contributors; no releases. It says it is not affiliated with TypeSafe.
- **Method.** It reads declared option logits from a frozen open LLM (default **Qwen3.5-4B** in BF16) in one forward pass. Criteria are defined at runtime. A shared state can be prefilled once and branched across many criteria (20 decisions/s on one RTX 3090 for 777 decisions over roughly 8k-character states).
- **Backends:** CUDA, MLX/MPS, a **llama.cpp GGUF CPU backend** (no CPU timings published), and WebGPU in the browser.
- **Self-reported quality:**
  - Balanced accuracy 0.813 on its own authored set.
  - 0.845 modal agreement with Jev's published answers on a 102-row public subset; Jev scored 0.883 there.
  - Out-of-fold per-workload ECE of 0.038–0.069 after temperature fitting.
- **Robustness tests are not adversarial.** They cover meaning-preserving changes only: option reversal caused 10 flips, a criterion wrapper 9, and irrelevant context 4.
- **LangChain hosts it.** The LinkedIn post calls SemIf the "free and open-source Jev". LangSmith's LLM Gateway serves `semif-qwen3.5-4b`, free until 2026-09-28 for US orgs. [LangChain docs](https://docs.langchain.com/langsmith/llm-gateway-decision-models)
- **JevBench:** 11th (47.7).

**CLM** ([Contrastive-LM/CLM](https://github.com/Contrastive-LM/CLM)).
- **Model.** Two 9.4M projection heads (the repo says about 20M) over a **frozen Qwen3-8B**. The answer is a softmax over scaled cosine similarity between the state embedding and each option embedding. Embeddings are cached, so a revisited state costs under 1 ms.
- **Training** (per repo): about 60M Q&A pairs, then about 30M synthetic hard negatives, then about 1M agent trajectories, using bidirectional InfoNCE.
- **Vendor-style claims:** matches Jev at 9x lower latency, and state-of-the-art as a verifier.
- **Known issue.** `score` questions can ignore the state (CLM issue #3, confirmed by OpenJev's own checks).
- **Hardware.** GPU only in practice: 8B weights, 7.7 GB even in FP8. 99 ms per request on an RTX 3090.
- **JevBench:** 45th (8.6).
- Metrics are from a WebFetch digest: ~1.4k stars, 8 commits.

**JevK5** ([allebee/jevk5](https://github.com/allebee/jevk5); weights [alibiserikbay/JevK5](https://huggingface.co/alibiserikbay/JevK5)).
- **Model.** Qwen3.5-4B with a merged LoRA distilled from larger models (the repo names Qwen3.6-27B and GPT-6 Luna). The answer is a softmax over the next-token logits of letters A–P, at a fitted temperature (1.532 in v0.2, per OpenJev).
- **Limits.** Up to 16 options per pass, with knockout passes for more. 16,384 tokens.
- **Self-reported speed and footprint.** 13–14 ms on an H100; **~0.6 s per decision on CPU** (GGUF, M1 Pro); about 9 GB in bf16.
- **Benchmark results:**
  - **JevBench v1.4.2: 3rd (62.0),** the best open model on that board after decider-4b.
  - OpenJev reproduced JevK5's 86.6% on the 231 public items.
- **Weaknesses.** Weak on dates and numbers (0.47) and on out-of-scope recall (33%). No injection testing.
- Metrics are from a WebFetch digest: ~109 stars, 29 commits.

---

## 7. The LangChain demo in the LinkedIn post

- **What the post (source 02) describes.** Avi Kumar (LangChain), about 2026-09-24:
  - **Step 1, model guardrail:**
    - Deep Agents passes the user input to **ModelGuardrailMiddleware**.
    - SemIf returns one of `allow`, `block_model` or `cancel_run`.
    - `cancel_run` POSTs to the LangSmith Agent Server's Cancel Run API.
    - A separate `GuardrailState` holds the latest user message, prior decisions and tool names, so the full history is not sent.
  - **Step 2, tool guardrail:**
    - **ToolGuardrailMiddleware** reads a static "agent card" that labels each tool allow, evaluate or deny.
    - Allow and deny are deterministic.
    - For "evaluate", SemIf checks user intent, prior guardrail decisions and tool metadata, and returns `allow`, `block_tool` or `cancel_run`.
  - **Routing:** all model calls go through the LangSmith LLM Gateway.
- **Code: not found.** No public repo, blog or code was found for this demo:
  - GitHub repo search returned 0 for "ModelGuardrailMiddleware", "ToolGuardrailMiddleware" and related terms. Unauthenticated code search is unavailable.
  - The langchain-ai org has no "semif" repo, and its only "guardrail" repo is a 2024 archive.
  - Web searches found nothing.
  - It is probably a private or deployment demo. **Open question: ask the author for the repo.**
- **Related public LangChain material found:**
  - **Decision models on the LLM Gateway:** SemIf is free until 2026-09-28; Jev is bring-your-own-key; 1–32 questions per request; no streaming. [LangChain docs](https://docs.langchain.com/langsmith/llm-gateway-decision-models)
  - **"Building a harness with Jev"** (Sydney Runkle and Hunter Lovell, 2026-09-17) describes ModelRouterMiddleware and **AutoModeMiddleware**, which uses Jev to screen risky tool calls, e.g. bash, before they run. It links the `langchain-typesafe` package. [LangChain blog](https://www.langchain.com/blog/building-a-harness-with-jev)
  - **Design choice worth copying.** VentureBeat reports that LangChain's middleware keeps tool output out of Jev, to prevent self-authorization.
- **Community analog, now gone.** `codecampn/semif-guardrail` was a self-hosted YAML-rule allow/block/review service on SemIf. It showed in search results but **returns 404 today**.

---

## 8. Critical questions for WAAG

### 8.1 Can a small encoder run on CPU inside a customer environment with acceptable latency?

**Our budget.**
- Governance overhead today is about **12–13 ms p50 per hop**. Downstream calls dominate: A2A skill 6.9 s p50, MCP tool 1.4 s p50.
- Everything is synchronous. An A2A hop holds a Tomcat worker for its whole subtree.
- Source: gateway-grounding §13(d).

**Measured CPU costs, per question on a small server, from Laya BENCHMARKS.md** (the multilingual row is also worth considering for English-only traffic):

| Model | 1 question | 3 questions | Share of an A2A hop's 6.9 s | Share of an MCP hop's 1.4 s |
|---|---|---|---|---|
| Laya english / typed-decisions (4-core EPYC, fp32) | ~580 ms | ~1.7 s (linear) | ~8% / ~25% | ~41% / — |
| Laya multilingual (same host) | ~193 ms | ~560 ms | ~3% / ~8% | ~14% / ~40% |
| Laya on a tuned 8-core laptop | ~290–330 ms | — | ~5% | ~22% |
| JevK5 GGUF (M1 Pro) | ~600 ms per decision | — | — | — |
| Verdict 151M single thread (author claim) | ~36 ms | — | ~0.5% | ~2.5% |
| Any encoder on a small GPU (T4 / Arc) | 30–40 ms | 40–70 ms | <1% | ~3% |

**Verdict.**
- **CPU inference of the 421M checkpoints cannot fit a 12 ms budget.** It adds 0.2–0.6 s per question.
- **On A2A hops only** (the only hops that carry natural language), that is a single-digit share of the hop in the typical case. So it is tolerable **if** we ask 1–2 questions per hop.
- It still stretches Tomcat thread hold-time and needs a bounded pool with a timeout.
- **Verdict (151M) with ONNX** is the most promising CPU candidate. Its 36 ms figure is an author claim that must be re-measured. Its accuracy is lower (48% on TypeSafe public evals).
- **A small GPU (T4 or better)** brings any of these into the 30–40 ms range. Many customers will not provide one.
- **Hard-won tuning lesson from Laya.** Pin intra-op threads to the physical core count and inter-op threads to 1. Defaults cost 12x.

### 8.2 Integration from a Java (Spring Boot) service

| Option | Effort | Pros | Cons |
|---|---|---|---|
| **A. Localhost HTTP sidecar speaking the Jev wire API** (`laya-serve`, OpenJev `OPENJEV_BACKEND=laya` or `verdict`, stuntd) | Low | No re-implementation. Same request shape as hosted Jev, so we can A/B Jev, Laya, Verdict and SemIf by swapping the URL. The Python process is isolated. Bearer auth is available. | Extra process and container (the torch base image is about 8.7 GB). A network hop (sub-ms on loopback). Python ops burden in customer environments. Supply chain: fast-moving 8-day-old code, so pin versions and hashes. |
| **B. ONNX Runtime Java** (`com.microsoft.onnxruntime:onnxruntime`, [docs](https://onnxruntime.ai/docs/get-started/with-java.html)) **+ DJL HuggingFace tokenizers** (`ai.djl.huggingface:tokenizers`, Rust-backed, [docs](https://docs.djl.ai/master/extensions/tokenizers/index.html)) | Medium–high | In-process, no Python, easy to ship in the gateway JAR. | Laya ships **no ONNX file**. We would export it with `scripts/export_onnx.py` (opset 18). The graph takes `input_ids`, `attention_mask`, `marker_pos`, `marker_mask`, `qtype` and returns `logits` and `act_logits`. We must **port `build_sequence`** (the prompt layout, the 48-token option cap, budget splitting, [MASK] scrubbing, truncation) and the per-bucket temperature, softmax and confidence math, then keep them in step with upstream. Verdict **does** ship `model.onnx`, but its prompt format and calibration logic would also need porting. |
| **C. DJL with a PyTorch or ONNX engine** | Medium | Same as B, plus a model-zoo abstraction. | Same porting work as B. |
| **D. Hosted Jev or Codiv / LangSmith SemIf** | Very low | Fastest to try. | Sends A2A text out of the customer environment, which **violates the core requirement**. US-only. Adds 236–650 ms plus network. OK only for offline R&D on synthetic data. |

Recommendation: **A for the spike** (Laya multilingual and Verdict behind one OpenJev-style router), and **B later** only if the approach proves out and one model is frozen. Put a CI parity test on the Java port against the Python outputs.

### 8.3 Input length vs WAAG inputs
- **Our input.** The PDP sees `context.argumentsFlat`, with each top-level string cut at **2,000 characters**. The A2A text is the `input=…` value. Our estimate, not measured: 2,000 English characters is roughly 450–550 tokens.
- **Laya English (512 context, about 320 tokens of state)** will **silently drop the tail** of a long A2A message. That tail is exactly where an injected instruction could sit.
- **Laya multilingual and typed-decisions (about 768 tokens of state)** hold one 2,000-character field. Instructions plus options must stay inside the 256-token head budget.
- **Verdict (512 including options)** is tight.
- **JevK5 (16k), CLM (2k) and Jev (32k)** are comfortable.
- **Recommendation.** Send only the NL field, not the whole flat k=v string; this matches TypeSafe's "context bloat" guidance. Log truncation.

### 8.4 How is "confidence" computed, and is it calibrated?
- **Laya:**
  - `answer_confidence` = max p after temperature scaling. It is calibratable, but ships over-confident; multilingual ships with no temperatures at all.
  - `confidence` = 1 − normalized entropy. Not calibrated by design.
  - `noul` confidence = max(p, 1 − p).
- **OpenJev:** 1 − H/ln K.
- **SemIf:** fits per-workload temperatures, out-of-fold.
- **Jev:** formula disputed or unpublished (§5.7).
- **Consequence for WAAG.** Any threshold, e.g. "escalate if answer_confidence < 0.8", must be **fitted on WAAG-labelled data, per checkpoint, per dtype, per option-count bucket**.
- **Confidence cannot flag unreadable input.** Wrong language or truncated text produces confident garbage. Put deterministic pre-checks in front: language detection, and a length/truncation flag.

### 8.5 Robustness to adversarial text: can the "state" flip the decision?
Yes, and nobody has measured how badly.

**Evidence that state can steer the answer:**
- **TypeSafe's official statement.** Injected instructions and self-arguing text can move Jev's answer. [jaggedness page](https://docs.typesafe.ai/model-jaggedness/jev-1.13)
- **One public demonstration against Jev.** A fake pre-approval field moved block probability from 0.76 to 0.48. [VentureBeat](https://venturebeat.com/security/companies-are-putting-jev-in-charge-of-ai-agent-decisions-and-prompt-injection-can-influence-the-verdict)
- **Laya flips confidently in the dangerous direction.** Negation and "ignore the above" style text produced `cancel_account` at 0.86–0.9998 when the correct answer was no_action (#377).
- **Architecture.** In Laya the state sits in the same bidirectional sequence as the instructions and options. Only `[MASK]` strings are scrubbed.
- **No project publishes an adversarial or injection robustness evaluation:** not Jev, Laya, SemIf, Verdict, CLM, JevK5, or JevBench. SemIf's and Laya's perturbation tests are meaning-preserving (option order, wording, irrelevant context). Even those flip 4–10 of 36 cases for SemIf, and cause a 13% order flip rate for Jev (per nibzard).
- **Detecting injection is only moderate.** Laya's prompt-injection *detection* accuracy is 0.70–0.76 on held-out sets.

**WAAG-specific threat.**
- The A2A text is **written by an upstream agent's LLM**. That LLM may itself be injected, and it may be aware the gateway checks it. An attacker who controls that text controls the model's input. With the model's thresholds known, flipping a borderline "is this consistent with purpose X?" answer is cheap.
- **Therefore the model's output must never widen access.** Use it only to **add friction**: DENY or step-up on "suspicious" or "low confidence". An attacker who pushes the score toward "benign" gains nothing, because the deterministic grant (profile, act_chain, Cedar) still applies.
- **Fail closed.** The existing `CustomAttributeProvider` SPI swallows exceptions and is **fail-open** (grounding §13(c)). Any model-backed attribute must default to the restrictive value on error, timeout or truncation.

### 8.6 Adapting to a new decision
- **Zero-shot.** New questions need only `instructions` plus `criteria`, with no retraining; the answer space is set per request. But Laya's base checkpoints are **near chance** zero-shot on realistic workflow decisions. Jev and SemIf do better zero-shot, per JevBench.
- **Fine-tuning.**
  - Laya's RLCD notebook needs about 30k labelled questions and 4–5 h on 2×T4. It fits temperatures and pushes to HF. The 0.36 → 0.77 jump on typed-decisions shows where the value is.
  - Cheaper: stuntd-style per-decision heads on a frozen encoder.
  - For SemIf: per-workload temperature fitting only, with no weight updates.
- **Data we would need.**
  - Labelled WAAG A2A messages per decision, e.g. "consistent with delegating skill?", "requests data outside capability scope?", "contains instructions to escalate or exfiltrate?".
  - We have the raw material in `pdp_audit_log.pdp_context` and `gateway_audit_log`, but it is small: 63 A2A skill decisions locally (grounding §12.1).
  - Plus adversarial and red-team variants.
  - Plus held-out data for calibration.

---

## 9. Relevance to WhiteSwan (architect and product view)

1. **The CEO's premise holds only in part.** He wants a light model in the customer environment, per-request, with memory.
   - **Jev fails "in customer env":** US-hosted, no on-prem per the reported written answer.
   - **The open encoders pass "in customer env" and "light"** (Apache-2.0, 151–421M). They **fail "fast on every request" on CPU:** 0.2–0.6 s per question against a 12 ms overhead.
   - **"Memory" is not a model feature.** These models are stateless. History must come from our side: act_chain, parent text via `corr_id`, audit rows. It has to be passed in as state.
2. **The "Jev CEO" chat's framing matches our constraints:** keep the model as a sensor ("what is this asking?") and Cedar as the authority.
   - **MCP hops:** structured arguments are handled deterministically; no model is needed.
   - **A2A hops:** the only place natural language exists is `argumentsFlat`, and it is **LLM-paraphrased, not the human's words** (grounding §12.4). An "intent" model here classifies the delegating agent's claim, not human intent. Product messaging must say that.
3. **Where it could plug in with no PDP rewrite.**
   - A `CustomAttributeProvider` (already receives raw untruncated args) calls the sidecar. It emits `context.nlIntent` (a string from a fixed enum), `context.nlSuspicious` (a boolean), and `context.nlConfidencePct` (a long).
   - Cedar-subset policies then work within our operators (`==`, integer compare, `&&`), e.g. deny when `context.nlSuspicious == true`.
   - The provider must be made **fail-closed**, and asynchronous shadow mode should come first.
   - Our PDP has no OR/NOT and no obligations, so "step-up" needs a new outcome type if we want a REQUIRE_APPROVAL path like the chat suggests.
4. **Product differentiation.**
   - LangChain already ships SemIf/Jev guardrail middleware *inside* the agent harness.
   - WAAG's angle is **on-the-wire, cross-vendor, identity-bound** evaluation. A post titled "Should your Jev guardrail live in the agent or on the wire?" shows the market is asking this question ([hoop.dev](https://hoop.dev/blog/should-your-jev-guardrail-live-in-the-agent-or-on-the-wire); only the title was seen in search, the post was not read).
   - Combine the NL signal with act_chain and capability scope, which harness-level guards do not see.
5. **Risk posture.**
   - Every component is 1–10 days old, single-maintainer or hype-driven, with benchmark numbers mostly self-reported.
   - Treat as R&D. Pin HF revisions with SHA-256 (Laya supports `expected_sha256`). No auto-update. Run it in a sandboxed sidecar with no egress.

---

## 10. Verified facts vs vendor claims (quick reference)

**Verified by me (API metadata, source code, official docs):**
- Laya: 25,410 stars, created 2026-09-18, 413 commits, 86 contributors, v0.3.20, Apache-2.0.
- OpenJev: 437 stars, 41 commits, 2 contributors, no releases, Apache-2.0.
- SemIf: 4,365 stars, 25 commits, MIT, renamed from OpenJev.
- Laya's sequence layout, confidence formulas, the ONNX export script and the ONNX graph I/O. There is no Laya ONNX file on HF. Verdict ships `model.onnx`.
- Jev's API limits (64k/32k, 255 options, 2–10 levels), $0.042/MTok input, and the jaggedness page's admission about adversarial content.

**Self-reported (author or vendor measured, not reproduced):**
- All latency and accuracy tables in the Laya, OpenJev, SemIf, Verdict, CLM and JevK5 READMEs.
- Jev's 70–500 ms, "40–200x faster", "0% hallucination", "1T tokens/day", "100k Discord".
- CLM's "SOTA verifier" claim.

**Secondhand:**
- TypeSafe's written survey answers (factorysemantics PR #71).
- The DCVC lead and $200M valuation (Wikipedia).
- The Octomind injection test (VentureBeat).

---

## 11. Open questions
1. Who exactly sent the "CEO of Jev" message? Is it TypeSafe's Diogo Almeida, or someone from TealTiger or Dakera? Ask Vinay for the profile or company domain.
2. Is there any TypeSafe on-prem or VPC offering for enterprise? The written answer says no; one unsourced digest says yes. Ask TypeSafe directly.
3. Where is the code for Avi Kumar's LangChain SemIf + Deep Agents demo? Ask the author.
4. What are the real CPU latencies for Verdict ONNX and Laya-multilingual ONNX on a typical customer node (4–8 vCPU, no GPU) at our text lengths? This needs our own benchmark.
5. How robust are these models to adversarial state? We would need our own red-team set: injected pre-approvals, negations, "ignore the above", and text that argues for its own label. We would measure flip rate, not accuracy.
6. Can we assemble enough labelled WAAG A2A decisions (probably 1k–30k) to fine-tune and calibrate? Synthetic generation plus audit-log replay is one route.
7. Is Laya's star count organic? Confirming this needs stargazer timestamps, which were rate-limited during this session. Should hype or stability affect vendor selection?
8. ONNX Runtime Java support for macOS arm64 on developer laptops. The digest of the Get Started page listed only x64 platforms.
9. What licenses apply to Qwen3.5-4B (SemIf, JevK5) and Qwen3-8B (CLM)? They were not checked here. Laya, Verdict and DiffusionGemma are Apache-2.0 per their repos.

---

## Sources
- Captured inputs: `sources/02-linkedin-langchain-semif-post.md` (LinkedIn post by Avi Kumar, ~2026-09-24) and `sources/03-ceo-idea-and-jev-ceo-chat.md`.
- WAAG facts: `/Users/amitprakash/Desktop/WS Apps/AzureAdWsIntegration/docs/others/gateway-grounding.md` §11–13.
- TypeSafe: [launch blog](https://typesafe.ai/blog/introducing-system-one-models-and-jev) · [team](https://typesafe.ai/team) · [models doc](https://docs.typesafe.ai/models) · [Jev 1.13 jaggedness](https://docs.typesafe.ai/model-jaggedness/jev-1.13) · [Latent Space podcast, 2026-09-21](https://www.latent.space/p/jev) · [Pydantic AI TypeSafe docs](https://pydantic.dev/docs/ai/models/typesafe/) · [flaviocopes deep dive](https://flaviocopes.com/jev/) · [Wikipedia](https://en.wikipedia.org/wiki/Jev_(AI_model)) · [heise, 2026-09-17](https://www.heise.de/en/news/AI-model-Jev-to-make-machines-decide-faster-11457071.html) · [Winzheng](https://www.winzheng.com/en/article/typesafe-ai-jev-system-one-model-launch) · [VentureBeat, 2026-09-21](https://venturebeat.com/security/companies-are-putting-jev-in-charge-of-ai-agent-decisions-and-prompt-injection-can-influence-the-verdict) · [factorysemantics PR #71](https://github.com/factorysemantics/factorysemantics-mes/pull/71) · [jev.pro founders (unofficial)](https://jev.pro/open/jev-founders/)
- OpenJev: [repo](https://github.com/razorback16/openjev) · [api.github.com/repos/razorback16/openjev](https://api.github.com/repos/razorback16/openjev)
- Laya: [repo](https://github.com/NandhaKishorM/laya) · [BENCHMARKS.md](https://github.com/NandhaKishorM/laya/blob/main/BENCHMARKS.md) · [research/README.md](https://github.com/NandhaKishorM/laya/blob/main/research/README.md) · [laya/common.py](https://github.com/NandhaKishorM/laya/blob/main/laya/common.py) · [laya/onnx_agent.py](https://github.com/NandhaKishorM/laya/blob/main/laya/onnx_agent.py) · [scripts/export_onnx.py](https://github.com/NandhaKishorM/laya/blob/main/scripts/export_onnx.py) · [pyproject.toml](https://github.com/NandhaKishorM/laya/blob/main/pyproject.toml) · [issue #377](https://github.com/NandhaKishorM/laya/issues/377) · [HF laya](https://huggingface.co/convaiinnovations/laya) · [HF laya-typed-decisions](https://huggingface.co/convaiinnovations/laya-typed-decisions) · [dev.to author post](https://dev.to/nandakishor_m_6cc0adfde9f/i-built-non-autoregressive-decision-models-a-year-ago-then-a-frontier-lab-called-it-a-18me) · arXiv [2503.23303](https://arxiv.org/abs/2503.23303), [2510.01237](https://arxiv.org/abs/2510.01237)
- SemIf: [repo](https://github.com/TheoLeeCJ/SemIf-OpenJev) · [docs/RESULTS.md](https://github.com/TheoLeeCJ/SemIf-OpenJev/blob/master/docs/RESULTS.md) · [docs/METHOD.md](https://github.com/TheoLeeCJ/SemIf-OpenJev/blob/master/docs/METHOD.md)
- Verdict: [repo](https://github.com/Heman10x-NGU/Verdict-open-jev) · [HF weights with ONNX](https://huggingface.co/heman10x/rlcd-modernbert-151m)
- CLM: [repo](https://github.com/Contrastive-LM/CLM)
- JevK5: [repo](https://github.com/allebee/jevk5) · [HF](https://huggingface.co/alibiserikbay/JevK5)
- JevBench: [fstandhartinger/jevbench](https://github.com/fstandhartinger/jevbench) · [leaderboard v1.4.2](https://benchmarkheaven.com/jev-models) · [v1.0](https://benchmarkheaven.com/jev-models/v1)
- LangChain: [LLM Gateway decision models](https://docs.langchain.com/langsmith/llm-gateway-decision-models) · [Building a harness with Jev, 2026-09-17](https://www.langchain.com/blog/building-a-harness-with-jev)
- TealTiger / Dakera: [TealTiger blog, 2026-06-16](https://blogs.tealtiger.ai/governance/integrations/tealtiger-dakera-governance-state/) · [Dakera blog, 2026-06-16](https://www.dakera.ai/blog/dakera-tealtiger-integration)
- Java: [ONNX Runtime Java](https://onnxruntime.ai/docs/get-started/with-java.html) · [DJL HuggingFace tokenizers](https://docs.djl.ai/master/extensions/tokenizers/index.html)
- Other: [hoop.dev, agent vs wire](https://hoop.dev/blog/should-your-jev-guardrail-live-in-the-agent-or-on-the-wire) · [stuntd](https://github.com/bladedevoff/stuntd)
