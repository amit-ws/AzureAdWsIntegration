# Small models inline in a customer environment: feasibility for WAAG intent decisions

Research dossier, 2026-09-26. Area: can a small model run inline, inside the customer's environment, as a signal for intent-aware authorization in the WhiteSwan Agentic Auth Gateway (WAAG)? WAAG is a Java 17 / Spring Boot gateway whose governance overhead today is about 12-13 ms p50 per hop.

## Executive summary (10 lines)

1. **It is feasible, with limits.** Small encoders (22M-400M parameters) and embedding models run in-JVM through ONNX Runtime Java. A 0.6B-4B LLM needs a sidecar, and in practice a GPU, to stay under about 100 ms.
2. **Latency is not the binding constraint.** WAAG hops already wait 1.4 s (MCP) to 6.9 s (A2A) p50 downstream. The real costs are CPU contention with a gateway that blocks threads, operational weight (GPU, patching, evaluation), and robustness.
3. **What a model can see is the binding constraint.** Natural language reaches WAAG only on A2A hops, and only as a paraphrase written by an LLM. The human's words never arrive. MCP hops carry structured arguments only. So a model would mostly judge text that an attacker may have shaped.
4. **Measured or vendor-reported numbers:** Prompt Guard 2 at 512 tokens takes 19 ms (22M) and 92 ms (86M) on an A100, per Meta. An independent run of PG2-86M measured 19 ms p50 on an M4 Pro GPU. ModernBERT-large (Laya) takes 33-40 ms on a T4. On CPU, a 512-token base-size encoder is roughly 60-250 ms in FP32 (my estimate). CPU-only guard deployments report 280-800 ms under load.
5. **Small LLM judges** (Qwen3/Qwen3.5 0.6-4B, Llama 3.2 1-3B, Gemma 4 E2B/E4B, Phi-4-mini) need roughly 30-150 ms per decision on a GPU, and around 0.3-2 s on CPU. Constrained decoding (GBNF grammars, vLLM structured outputs) makes their output typed. It does not make it trustworthy.
6. **Robustness is the weakest link.** Spacing tricks drove attack success against Prompt Guard 1 from under 3% to nearly 100%. Character-injection attacks reach up to 100% evasion against commercial guards. Adaptive attacks break all 12 published defenses tested. Guard models are overconfident, and their confidence is wrong out of distribution.
7. **Rule for integration:** a model output may only restrict access, never grant it. Feed it into `forbid` policies, and emit an explicit value on failure. WAAG's PDP treats a missing attribute as false, and the `CustomAttributeProvider` SPI swallows exceptions. Together these make a naive integration fail open.
8. **"Memory" should mean deterministic chain context** (parent text via `corr_id`, per-trace history) that is read at decision time. It should not mean online learning. Retrieval stores and fine-tuning bring poisoning (over 80% attack success at under 0.1% poison rate), embedding inversion (92% of 32-token texts recovered) and cross-tenant leakage risks.
9. **Licensing and supply chain are manageable.** Apache-2.0 or MIT options exist in every family: ModernBERT, GLiClass, the bge/e5/gte small models, Qwen3/3.5, Qwen3Guard, Granite Guardian, Gemma 4 and Phi-4-mini. Llama and Gemma-3 terms add attribution, acceptable-use and gated-download friction. Signed weights (OpenSSF OMS v1.0), safetensors or ONNX, and pinned digests are the baseline.
10. **Recommendation:** build a deterministic chain-context layer first. Then add an optional CPU encoder or embedding tier, used only to raise risk. Offer a small LLM judge only as an opt-in GPU sidecar for high-risk A2A hops, running in "escalate/deny-only" mode. This also needs a third PDP outcome (REQUIRE_APPROVAL), which WAAG lacks today.

---

## 1. Framing: what a customer-side model would actually see in WAAG

These facts come from `docs/others/gateway-grounding.md`, sections 12-13. They set the ceiling on what any model can do, whatever its size.

| Fact | Consequence for a small model |
|---|---|
| Natural language reaches the PDP only as `context.argumentsFlat`. This is a flat `k=v` string, with top-level strings cut at 2000 chars, and it is present only on A2A hops (§13b). | An intent model has real text to read on A2A hops only. On MCP hops it sees tool names and structured arguments, such as `symbol=AAPL`. |
| On the console path the A2A text is the console LLM's paraphrase. The human's chat never reaches the gateway (§12.1, §12.4). | The model can only compare "what the delegating LLM says it wants" with "what is being called". It cannot compare against what the human asked. If the upstream LLM was prompt-injected, the input the model reads is attacker-shaped. |
| Tool descriptions and inputSchema are in memory at the orchestrator but never reach the PDP. MCP `_meta` is dropped (§13a, §13g). | A model comparing "request vs. tool purpose" needs the descriptor passed to it. The SPI does not provide it today. |
| The parent's text is in `InFlightRequestRegistry`, keyed by the child's inbound `corr_id`, while the child is decided. Nothing reads it (§13f). | The cheapest "memory" already exists in-process: parent text plus child call. |
| `CustomAttributeProvider` SPI: synchronous, receives raw arguments, output merged last. Exceptions are swallowed, which is fail-open. It has zero implementations and gets no RequestContext, traceId or descriptor (§13c). | This is the natural seam, but it needs extending. It must also fail closed. |
| PDP: `==`, `!=`, integer compare, `like`, `.contains`, `&&`. ALLOW/DENY only. A **missing attribute makes a condition false, including `!=`** (§6.2). | Model output must be discretized into a string, boolean or long. `forbid when context.intentRisk >= 80` silently does nothing if the attribute is missing. That is fail-open. |
| Governance about 12-13 ms p50. Downstream A2A 6.9 s p50, MCP 1.4 s p50. Everything synchronous; about 33 concurrent journeys would exhaust 200 Tomcat threads (inferred, §13d). | 20-100 ms extra per hop is under 2% of an A2A hop and 2-7% of an MCP hop. CPU inference on the gateway's own cores lowers throughput under load, and the thread model magnifies that. |

**Product framing.** The Jev CEO's advice was to find the smallest primitive that can make each decision (source 03). That matches the evidence below. Most WAAG decisions (identity, lineage, capability, argument bounds, sequence counts) need no model. A model earns its place in only two spots:
- judging the free-text A2A delegation message, whether it is on-task, risky or injected;
- mapping free text to a typed label that a deterministic policy can use, as in the "duplicate payment → refund" example.

---

## 2. Model families

Parameter counts, licenses and dates below come from the Hugging Face model API (`huggingface.co/api/models/<id>`, read 2026-09-26) unless another source is cited. "Total params" counts embedding matrices, which matters for memory. For example, the backbone of "Prompt Guard 2 22M" is 22M, but the checkpoint holds 70.8M parameters because of its large vocabulary.

### 2.1 Encoder classifiers (fine-tuned): ModernBERT, DeBERTa-v3

| Model | Total params | Context | License | Notes |
|---|---|---|---|---|
| answerdotai/ModernBERT-base | 149.7M | 8,192 | Apache-2.0 | Released 2024-12-19. HF claims it is about 2x faster than DeBERTa, and up to 4x on mixed-length inputs, measured on an RTX 4090. It is the first base-size model to beat DeBERTaV3 on GLUE, using under 1/5 of the memory. Flash-Attention 2 is recommended ([HF blog](https://huggingface.co/blog/modernbert)). |
| answerdotai/ModernBERT-large | 395.9M | 8,192 | Apache-2.0 | Backbone for Laya and several 2026 guard fine-tunes. |
| microsoft/deberta-v3-base | about 184M (e.g. [protectai PI v2](https://huggingface.co/protectai/deberta-v3-base-prompt-injection-v2): 184.4M) | 512 in practice | MIT | The long-time default for prompt-injection classifiers. |
| jhu-clsp/mmBERT-base | n/a | up to 8K | MIT | Multilingual ModernBERT-style model, used by the vLLM Semantic Router ([arXiv 2603.12646](https://arxiv.org/html/2603.12646v1)). |

**Fit.** This is the best cost/quality point for a fixed label set. Examples: "on-task / off-task / injected" for an A2A message, or "which of N capability intents". It requires a labelled training set per domain. Both Meta (PG2 card) and the independent benchmarks below say off-the-shelf detectors need domain fine-tuning.

### 2.2 Zero-shot: NLI cross-encoders and GLiClass

| Model | Total params | License | Mechanism |
|---|---|---|---|
| MoritzLaurer/deberta-v3-large-zeroshot-v2.0 | 435.1M | MIT | NLI entailment. The standard pipeline scores each candidate label as a separate hypothesis, so cost grows linearly with the number of labels. |
| MoritzLaurer/ModernBERT-large-zeroshot-v2.0 | 395.8M | Apache-2.0 | Same mechanism on ModernBERT. |
| knowledgator/gliclass-modern-base-v2.0-init | 151.4M | Apache-2.0 | Scores all labels in one forward pass. The vendor claims cross-encoder-level quality at lower compute. Its zero-shot F1 is modest (IMDB 0.83, AG News 0.66, Emotions 0.30) and ONNX is available ([card](https://huggingface.co/knowledgator/gliclass-modern-base-v2.0-init)). |

**Fit.** Useful for bootstrapping labels before any training data exists, for example turning a capability's description into a candidate label. Zero-shot accuracy on nuanced security labels is weak (see the Emotions score) and is not good enough as an enforcement signal. Laya's own notes say its base checkpoints score near chance zero-shot on typed decisions until fine-tuned (§2.6).

### 2.3 Embedding models for semantic similarity

| Model | Total params | Dim | License | Notes |
|---|---|---|---|---|
| BAAI/bge-small-en-v1.5 | 33.4M | 384 | MIT | ONNX in repo, 63M downloads |
| intfloat/e5-small-v2 | 33.4M | 384 | MIT | ONNX in repo |
| thenlper/gte-small | 33.4M | 384 | MIT | ONNX in repo |
| Qwen/Qwen3-Embedding-0.6B | 595.8M | n/a | Apache-2.0 | Released 2025-06 |
| google/embeddinggemma-300m | 302.9M | n/a | Gemma terms (gated) | Released 2025-07 |

**Fit.** The cheapest semantic signal. Examples: cosine of the A2A message against the target skill or tool description, or against the parent hop's text. It is easy to game. An attacker who knows the tool description can pad the message with on-topic words to raise similarity. Use it to flag low similarity, never to grant on high similarity.

### 2.4 Guard models

| Model | Total params | License | Output | Scope and caveats |
|---|---|---|---|---|
| Llama Prompt Guard 2 22M | 70.8M total (DeBERTa-xsmall backbone) | Llama 4 Community (gated) | benign/malicious | 512-token window. Meta reports English AUC .995 and recall 88.7% at 1% FPR. Latency 19.3 ms at 512 tokens on an A100. Weaker multilingual ([card](https://huggingface.co/meta-llama/Llama-Prompt-Guard-2-86M)). |
| Llama Prompt Guard 2 86M | 278.8M total (mDeBERTa-base) | Llama 4 Community (gated) | benign/malicious | Meta reports AUC .998, recall 97.5% at 1% FPR, 92.4 ms on an A100. Tokenization hardened against whitespace and fragment tricks. The card says adaptive attacks remain possible. |
| Llama Guard 3-1B | 1.50B | Llama 3.2 Community (gated) | "safe/unsafe" + S1-S13 | English F1 .899, FPR .090. The card says it is susceptible to prompt injection ([card](https://huggingface.co/meta-llama/Llama-Guard-3-1B)). |
| Llama Guard 4 12B | 12.0B | Llama 4 Community | S1-S14, multimodal | Dense model pruned from Llama 4 Scout, single GPU. The card defers prompt-injection detection to Prompt Guard 2 ([card](https://huggingface.co/meta-llama/Llama-Guard-4-12B)). |
| ShieldGemma 2B/9B/27B | 2.61B (2B) | Gemma terms (gated) | P(Yes) from logits | 4 harm policies. Highly sensitive to how the policy is worded ([card](https://huggingface.co/google/shieldgemma-2b)). |
| Granite Guardian 3.2-5B / 3.2-3B-A800M / 3.3-8B / 4.1-8B; HAP-38M | 5.78B / 3.30B / 8B / 8B; 38.5M | Apache-2.0 | `<score>yes/no</score>`, optional `<think>` | Harm, jailbreak, RAG groundedness, function-calling hallucination. 4.1 (Apr 2026) adds **Bring-Your-Own-Criteria** for arbitrary judging rules ([4.1 card](https://huggingface.co/ibm-granite/granite-guardian-4.1-8b), [3.3 card](https://huggingface.co/ibm-granite/granite-guardian-3.3-8b)). |
| Qwen3Guard-Gen 0.6B/4B/8B; Qwen3Guard-Stream 0.6B/4B/8B | 0.75B / 4.41B / 8B; Stream-0.6B 0.60B | Apache-2.0 | Safe/Controversial/Unsafe + 9 categories (incl. Jailbreak, input only) | 119 languages. Released 2025-09-23. The Stream variant uses a per-token classification head. The tech report quotes about 0.5 ms/token and under 300 ms for 512 tokens for Stream-8B ([card](https://huggingface.co/Qwen/Qwen3Guard-Gen-0.6B), [arXiv 2510.14276](https://arxiv.org/abs/2510.14276)). |

**Fit.** Guard models answer "is this content harmful or an injection". They do not answer "is this action aligned with the delegated task". Prompt Guard 2 is relevant to WAAG as a detector for injected A2A messages and for tool responses on egress. Granite Guardian 4.1's BYOC and function-call checks are the closest ready-made "judge a tool call against the request" capability. At 8B, though, it needs a GPU.

### 2.5 Small generative LLMs with constrained decoding

| Model | Total params | License | Notes |
|---|---|---|---|
| Qwen3-0.6B / 1.7B / 4B | 0.75B / 2.03B / 4.02B | Apache-2.0 | Released 2025-04 |
| Qwen3.5-0.8B / 2B / 4B | 0.87B / 2.27B / 4.66B | Apache-2.0 | Released 2026-02. Image-text-to-text pipeline tag. |
| Llama 3.2 1B / 3B Instruct | 1.24B / 3.21B | Llama 3.2 Community (gated) | |
| Gemma 3 1B / 4B | 1.00B / 4.30B | Gemma terms (gated) | |
| Gemma 4 E2B / E4B | 5.12B / 8.00B total ("effective" 2B/4B) | **Apache-2.0** | Released 2026-04-02; license changed from Gemma terms ([Google OSS blog](https://opensource.googleblog.com/2026/03/gemma-4-expanding-the-gemmaverse-with-apache-20.html)). |
| Phi-4-mini-instruct | 3.84B | MIT | |
| Granite 4.0 350M / H-1B | 0.35B / 1.46B | Apache-2.0 | Released 2025-10 |

**Fit.** Maximum flexibility: a free-form rubric, the tool descriptor in the prompt, and a typed output via grammar. It also has maximum attack surface. The judge reads attacker-influenced text as part of its prompt (§6.2).

### 2.6 "Decision model" encoders (Jev-style), for reference

Laya ([NandhaKishorM/laya](https://github.com/NandhaKishorM/laya), Apache-2.0) is ModernBERT-large (421M) with typed `choice`/`score`/`noul` outputs from a single forward pass. **Vendor-reported:** 39.5 ms (EN) / 32.8 ms (multilingual) per question on a T4; ECE 0.466 as shipped, 0.081 after temperature fitting; 0.766 accuracy on its typed-decisions benchmark. It confirms the architecture class of §2.1: an encoder, a head and calibration. It does not change the latency or robustness picture. Its README concedes it is over-confident as shipped. Other WhiteSwan research streams cover Jev, OpenJev and SemIf.

---

## 3. Latency and memory for 256-1024-token inputs

### 3.1 Cited measurements

| Model | Hardware / runtime | Input | Latency | Source (date) | Status |
|---|---|---|---|---|---|
| Prompt Guard 2 22M / 86M | A100 | 512 tok | 19.3 / 92.4 ms | [Meta card](https://huggingface.co/meta-llama/Llama-Prompt-Guard-2-86M) (2025-04) | Vendor |
| Prompt Guard 2 86M | Apple M4 Pro, MPS | tool outputs, 510-tok windows | p50 about 19 ms; p95 92 ms truncated, 264 ms chunked | [briankhoi/llama-prompt-guard-2-benchmark REPORT](https://github.com/briankhoi/llama-prompt-guard-2-benchmark) (scored 2026-09-24) | Independent, small |
| Prompt Guard 2 86M | CPU, torch, 2 replicas × 4 threads on 16 vCPU | ≤512 tok | 28 req/s at p50 280 ms. One replica: 11 req/s at p50 787 ms with concurrency 8 | [tinfoilsh/confidential-llama-guard](https://github.com/tinfoilsh/confidential-llama-guard) | Independent (under load) |
| 3 × mmBERT-32K (270M, FP16) | CPU, ONNX Runtime | 512 tok | 803 ms end-to-end (3 concurrent classifiers) | [vLLM Semantic Router, arXiv 2603.12646](https://arxiv.org/html/2603.12646v1) (2026-03-13) | Paper |
| Same, GPU + FlashAttention | AMD MI300X | 8K tok | 50 ms end-to-end. Router footprint under about 800 MB | same | Paper |
| DeBERTa-v3-base (LoRA) / Gemma-2-2B (LoRA) / Mamba-130M | GPU (A100 per paper) | 256 tok | 27.5 / 48.5 / 24.3 ms | [arXiv 2512.19011v3](https://arxiv.org/html/2512.19011v3) (2026-06) | Paper |
| TF-IDF+SVM / LightGBM | 32-core CPU | 256 tok | 3.4-5.3 / 49.4 ms | same | Paper |
| Laya (ModernBERT-large) | T4 | single question | 33-40 ms | [Laya README](https://github.com/NandhaKishorM/laya) | Vendor |
| Qwen3Guard-Stream-8B | GPU | 512 tok | about 0.5 ms/token, under 300 ms total (vs. over 2 s iterative generative) | [arXiv 2510.14276](https://arxiv.org/abs/2510.14276) (2025-10) | Vendor paper |
| 7B LLM, Q4 GGUF (llama.cpp) | Xeon Platinum 8480+ | 256-tok prompt | about 1.5 s prompt processing (6.5 s on E5-2695 v2) | [Malakhov, CEUR Vol-4164](https://ceur-ws.org/Vol-4164/paper11.pdf) (2025) | Workshop paper |
| Llama-3.2-1B / Gemma-3-1B / Phi-4-mini, Q4 | Xeon 8480+ | decode | 120 / 100 / 80 tok/s. Q4 sizes 0.5 / 0.6 / 1.9 GB | same | Workshop paper |
| Qwen3-0.6B / 1.7B / 4B | H20 GPU, transformers | memory | BF16 1.39 / 3.41 / 7.97 GB (4B AWQ-INT4: 2.92 GB) | [Qwen speed benchmark](https://qwen.readthedocs.io/en/latest/getting_started/speed_benchmark.html) | Vendor |

### 3.2 Back-of-envelope for CPU (estimate, not measured)

I found no clean, citable single-request CPU number for a base encoder at 512 tokens. The numbers below are my estimate and are consistent with the loaded-CPU data points above.

- **Compute.** A base encoder's forward pass costs about 2 × (non-embedding params) × tokens. ModernBERT-base (about 110M non-embedding) at 512 tokens is roughly 115-130 GFLOP. A single DeBERTa-xsmall-class model (PG2-22M backbone) is roughly a quarter of that.
- **FP32 on 4-8 modern x86 cores** (hundreds of GFLOP/s sustained): about **60-250 ms** for a base model and **15-60 ms** for a 22M-class model.
- **INT8 (VNNI) or BF16/INT8 (AMX on Sapphire Rapids and later)** typically gives a 3-5x throughput gain with small accuracy loss:
  - 2.95x INT8 vs FP32 with ORT+OpenVINO EP on Ice Lake ([Microsoft OSS blog, 2023-01-25](https://opensource.microsoft.com/blog/2023/01/25/improve-bert-inference-speed-by-combining-the-power-of-optimum-openvino-onnx-runtime-and-azure/));
  - up to 5.8x on Xeon ([arXiv 2608.18182](https://arxiv.org/abs/2608.18182), 2026-08).
  - That gives an estimated **20-70 ms** for a base model at 512 tokens.
- **256 tokens** is roughly half of these figures. **1024 tokens** is roughly double or a bit more (attention is quadratic). ModernBERT's local attention reduces that growth.
- **Small LLM on CPU:** scaling the 7B/256-token measurement (1.5 s) by parameters and length suggests about 0.3-0.6 s for a 1B model at 512 tokens, and 1-3 s for 4B. A classification-by-logit design (one forward pass, read P("yes")) avoids decode. A JSON output adds tens of decode steps, each 8-12 ms at 1B on a modern Xeon (from the 80-120 tok/s rates above).

### 3.3 What this means for WAAG

| Tier | Plausible per-hop add (512 tok) | Versus WAAG (governance 12 ms; downstream 1.4-6.9 s p50) |
|---|---|---|
| Embedding small (33M), in-JVM CPU INT8 | about 5-20 ms (est.) | Negligible end to end; about doubles governance overhead |
| Base encoder, in-JVM CPU INT8 | about 20-70 ms (est.) | 1-5% of hop time. Takes 1-4 cores per concurrent inference. |
| Base encoder, GPU (T4/L4/A10 sidecar) | about 5-30 ms plus RPC | Negligible |
| 0.6-1.7B LLM judge, GPU sidecar | about 30-100 ms | Acceptable on A2A, borderline on chatty MCP chains |
| 4B LLM judge, GPU | about 80-200 ms | Acceptable only for flagged or high-risk hops |
| Any LLM judge on CPU | 0.3-3 s | Not viable inline on every hop. Only for rare escalations. |

**Key operational point: throughput, not latency.** WAAG blocks one Tomcat thread per A2A hop for the whole subtree. CPU inference runs on the same host and shares the same cores. An inline CPU model therefore lowers the concurrency ceiling (about 33 journeys, inferred) more than it lowers per-request latency. Mitigations:
- a bounded inference executor with its own core budget;
- batching (ORT supports batching; Tomcat request handling does not batch naturally);
- a separate inference pod.

---

## 4. Serving from Java 17 / Spring Boot

### 4.1 In-process (JVM) options

- **ONNX Runtime Java.** `com.microsoft.onnxruntime:onnxruntime` for CPU and `onnxruntime_gpu` for CUDA; Java 8+; the CPU artifact covers Windows/Linux/macOS x64, and the GPU artifact Windows/Linux x64 ([ORT Java docs](https://onnxruntime.ai/docs/get-started/with-java.html)). Maven Central search shows 1.22.0 (2025-05); that index may lag.
  - Sessions are thread-safe after construction. Concurrent `Run()` on one session is supported except with DirectML ([ORT discussion #10107](https://github.com/microsoft/onnxruntime/discussions/10107), [#9441](https://github.com/microsoft/onnxruntime/discussions/9441)).
  - ORT allocates **native (off-heap) memory**. Container memory limits must cover model plus arena, not just `-Xmx` (design note).
- **Tokenizers.** DJL's `ai.djl.huggingface:tokenizers` wraps the Rust HF tokenizers over JNI and loads `tokenizer.json` from a local path ([DJL docs](https://docs.djl.ai/master/extensions/tokenizers/index.html); the docs cite 0.38.0, Maven search shows 0.33.0, index may lag). Tokenizer parity with training matters for robustness. The PG2 hardening was a tokenization change.
- **Spring AI `TransformersEmbeddingModel`** already combines ORT, DJL and HF tokenizers for embeddings. It supports `file:` URIs for offline use and a GPU device id. Its default fetches over https and caches under `java.io.tmpdir` ([Spring AI docs](https://docs.spring.io/spring-ai/reference/api/embeddings/onnx.html)). For air-gapped installs, pin `file:` or `classpath:` resources and disable remote fetch.
- **ONNX Runtime GenAI** has a Java API (`ai.onnxruntime.genai`) for generative models. It lists constrained decoding and structured output as features ([ORT GenAI Java](https://onnxruntime.ai/docs/genai/api/java.html)). The Java path is less mature than Python and should be proven before relying on it.
- **java-llama.cpp** (`de.kherud:llama`, MIT) is a JNI binding to llama.cpp. It supports GBNF grammars, embeddings and CUDA/Metal when built for them. It tracks llama.cpp build b4916; the last Maven release is 4.2.0 (2025-06-20) ([repo](https://github.com/kherud/java-llama.cpp)). **Risk:** it lags upstream, so newer architectures (Qwen3.5, Gemma 4) and GGUF-parser security fixes arrive late. Native crashes take down the gateway JVM.

### 4.2 Sidecar options (HTTP/gRPC on localhost or in-pod)

| Engine | Strength | Caveats |
|---|---|---|
| llama.cpp `llama-server` | CPU-first, GGUF, GBNF/JSON-schema grammars, small footprint | GGUF parser has had RCE-class bugs: Talos/Databricks 2024, CVSS 8.8 ([Talos TALOS-2024-1913](https://talosintelligence.com/vulnerability_reports/TALOS-2024-1913), [Databricks](https://www.databricks.com/blog/ggml-gguf-file-format-vulnerabilities)). Further integer-overflow advisories came in 2026 ([GHSA-vgg9-87g3-85w8](https://github.com/ggml-org/llama.cpp/security/advisories/GHSA-vgg9-87g3-85w8)). Load only signed models. |
| Ollama | Simplest operations. JSON-schema `format` structured outputs since 2024-12-06 ([blog](https://ollama.com/blog/structured-outputs)). | Pulls from a registry by default. History of API-server RCE, "Probllama" CVE-2024-37032, fixed in 0.1.34, with over 1,000 exposed instances found ([Wiz](https://www.wiz.io/blog/probllama-ollama-vulnerability-cve-2024-37032)). Bind to localhost and never expose it. |
| vLLM | Best GPU throughput. Structured outputs (choice/regex/JSON/grammar/structural tag) via xgrammar/guidance/outlines. Old `guided_*` fields removed in v0.12.0 ([docs](https://docs.vllm.ai/en/latest/features/structured_outputs.html)). | GPU-oriented. Prefix-cache timing side channels in multi-tenant use (§7.4). |
| HF TGI | n/a | **In maintenance mode since 2026-03-21.** HF points users to vLLM, SGLang, llama.cpp or MLX ([repo](https://github.com/huggingface/text-generation-inference)). Do not adopt. |
| NVIDIA Triton | Multi-backend (ORT, TensorRT, Python), dynamic batching, mature metrics | Heavy for a gateway appliance. Best when the customer already runs it. |

**`choice` decoding.** For WAAG, a constrained `choice` over a small label set (vLLM `structured_outputs.choice`), or reading logits of a single next token, gives an ALLOW/FLAG/DENY-shaped label. It costs little more than one prefill.

### 4.3 WAAG integration seam: requirements

1. **Implement `PolicyContextBuilder.CustomAttributeProvider`**, and extend its signature so it receives the descriptor (tool/skill description, inputSchema), `traceId`, `corr_id` and verified tenant (grounding §13c).
2. **Fail closed explicitly.**
   - The SPI swallows exceptions, and the PDP treats missing attributes as false even for `!=` (grounding §6.2, §13c).
   - So the provider must always emit something like `context.intentStatus` ∈ {`OK`,`TIMEOUT`,`ERROR`,`SKIPPED`} and `context.intentRisk` as a long.
   - Ship paired `forbid` guardrails, for example `forbid ... when { context.intentStatus == "ERROR" }`, for the capabilities where the tenant opts into fail-closed.
3. **Deadline.** Put a hard per-call timeout (e.g. 150 ms GPU, 80 ms CPU encoder) on the inference call, with a circuit breaker. On timeout, emit `TIMEOUT` and let tenant policy decide.
4. **Deny-only semantics.** Model attributes should appear only in `forbid` policies, or as a narrowing `&&` on a permit that already stands on deterministic grounds. They should never be the sole basis of a permit. WAAG's engine silently ignores some head forms and so widens grants (grounding §14 #1). A permit that relies on a model boolean is doubly fragile.
5. **Audit.** Record model id, weights digest, input hash, score, threshold and latency in `pdp_audit_log`, so every decision can be replayed.

---

## 5. Packaging, air-gap, supply chain, licensing

### 5.1 Packaging and air-gap

- Ship weights as **safetensors or ONNX**, never pickle.
  - A Trail of Bits audit (published 2023-05-23) found no arbitrary-code-execution flaw in safetensors ([HF blog](https://huggingface.co/blog/safetensors-security-audit)).
  - Pickle-based models on hubs have carried reverse shells that evaded Picklescan ("nullifAI", ReversingLabs, Feb 2025) ([The Hacker News](https://thehackernews.com/2025/02/malicious-ml-models-found-on-hugging.html)).
  - GGUF is not code, but its parsers have had memory-safety bugs (§4.2).
- **Distribution.** Either bake weights into the gateway or sidecar image, or ship them as an OCI artifact:
  - the CNCF ModelPack spec, with KitOps as an implementation ([modelpack/model-spec](https://github.com/modelpack/model-spec), [KitOps](https://github.com/kitops-ml/kitops));
  - this reuses the customer's existing container registry, mirroring and scanning, which suits air-gapped installs.
- **Offline runtime.** No hub calls at startup: pin `file:` resources in Spring AI, and use local `tokenizer.json`. Ollama needs a local GGUF plus Modelfile import rather than `ollama pull`.
- **Gated models** (Llama, Gemma 3, ShieldGemma, EmbeddingGemma) need manual license acceptance on the hub. WhiteSwan would redistribute under those terms, or the customer must fetch the weights themselves. Apache and MIT models avoid this.

### 5.2 Provenance and signing

- **OpenSSF Model Signing (OMS) v1.0** launched 2025-04-04. It signs a manifest of (file path, digest) pairs. It supports Sigstore keyless, private keys, PKI certificates and PKCS#11 ([OpenSSF blog](https://openssf.org/blog/2025/04/04/launch-of-model-signing-v1-0-openssf-ai-ml-working-group-secures-the-machine-learning-supply-chain/), [sigstore/model-transparency](https://github.com/sigstore/model-transparency)). NVIDIA has signed all NVIDIA-published NGC models with OMS since March 2025 ([NVIDIA blog](https://developer.nvidia.com/blog/bringing-verifiable-trust-to-ai-models-model-signing-in-ngc)).
- **Recommended practice:**
  1. Verify the OMS signature in CI when building the WhiteSwan model bundle, with WhiteSwan as signer. The OMS tooling I saw is a CLI and library outside the JVM.
  2. Embed the expected SHA-256 manifest in the gateway release.
  3. Recompute the hashes in Java at load and refuse to start the intent tier on mismatch.
  4. For customer-fine-tuned weights, the customer signs them. The gateway accepts a configured trust root.
- **Provenance is not safety.** A signed checkpoint can still be backdoored during training. Poisoning needs a near-constant, small number of documents: about 250 backdoored 600M-13B models (Anthropic, UK AISI, Turing Institute, Oct 2025, [Anthropic](https://www.anthropic.com/research/small-samples-poison)). Prefer vendors with published training-data practices, and run a WhiteSwan acceptance evaluation per model version.

### 5.3 Licensing summary

| License | Models | Friction for an embedded commercial product |
|---|---|---|
| Apache-2.0 | ModernBERT, GLiClass, ModernBERT-zeroshot, Qwen3 / Qwen3.5, Qwen3Guard, Qwen3-Embedding, Granite Guardian (all), Granite 4.0, Gemma 4 | Low: notice and patent terms |
| MIT | DeBERTa-v3, deberta-v3-large-zeroshot, bge/e5/gte-small, Phi-4-mini, mmBERT | Lowest |
| Llama 3.2 / Llama 4 Community | Llama 3.2 1B/3B, Llama Guard 3-1B/4, Prompt Guard 2 | Must show "Built with Llama". Derivative model names must start with "Llama". Acceptable-use policy is incorporated. The license travels with redistribution. A separate license is needed above 700M MAU ([Llama 4 license](https://github.com/meta-llama/llama-models/blob/main/models/llama4/LICENSE)). Gated download. |
| Gemma terms | Gemma 3, ShieldGemma, EmbeddingGemma, Gemma 3 270M | Prohibited-use policy flows down to users. Gated. |

**Product take.** An all-Apache/MIT stack is achievable: ModernBERT or DeBERTa fine-tune + bge-small + Qwen3Guard or Granite Guardian + Qwen3/3.5 or Gemma 4 as the judge. That removes the attribution and naming obligations from WhiteSwan's product. The main trade-off is Prompt Guard 2, whose injection-detection precision is good (§6.1) but which carries Llama 4 terms.

---

## 6. Robustness: adversarial and prompt-injection attacks, calibration

### 6.1 Evidence

- **Trivial evasion of injection classifiers.**
  - Prompt-Guard-86M (v1): inserting spaces between letters took attack success from under 3% to nearly 100% (Robust Intelligence / Cisco, July 2024) ([Cisco blog](https://blogs.cisco.com/security/bypassing-metas-llama-classifier-a-simple-jailbreak), [llama-models #50](https://github.com/meta-llama/llama-models/issues/50)).
  - PG2 hardened tokenization against this class of trick (Meta card).
  - Character injection and adversarial-ML evasion reach up to 100% evasion across six guard systems, including Azure Prompt Shield and Meta Prompt Guard ([Hackett et al., arXiv 2504.11168](https://arxiv.org/abs/2504.11168), 2025).
- **Adaptive attackers win.** Adaptive attacks bypassed all 12 published jailbreak and prompt-injection defenses tested (Nasr, Carlini et al., [arXiv 2510.09023](https://arxiv.org/abs/2510.09023), Oct 2025; USENIX Security '26).
- **Scope gap, even without adaptation.** An independent run on 2026-09-24 ([REPORT](https://github.com/briankhoi/llama-prompt-guard-2-benchmark)) found:
  - PG2-86M caught about 50-55% of InjecAgent and AgentDojo indirect injections at the default 0.5 threshold, with 0 false positives on 312 benign outputs;
  - it caught essentially 0% of plain imperative injections that lack "ignore previous instructions" wording;
  - **truncation at 512 tokens caught 0 of 350 injections placed after token 510**, and chunking restored this to 58%;
  - PG2-22M was near-useless at the default threshold;
  - adding the user task as context did not help.
  - The study is small and self-labelled, and the author states these caveats.
- **LLM judges are manipulable.**
  - Punctuation-only or "Thought process:" responses produced false-positive rates of up to 35% (GPT-4o) and 60-90% (Llama3-70B, Qwen2.5-72B) as judges ([One Token to Fool LLM-as-a-Judge, arXiv 2507.08794](https://arxiv.org/abs/2507.08794), Jul 2025).
  - Llama Guard 3-1B's card says it is susceptible to prompt injection. ShieldGemma's card says it is highly sensitive to policy wording.
- **Calibration.** Across 9 LLM guard models and 12 benchmarks, guards were overconfident and badly miscalibrated under jailbreaks ([Liu et al., ICLR 2025, arXiv 2410.10414](https://arxiv.org/abs/2410.10414)). In a CPU-classifier study, confident miscalibration hit out-of-distribution attacks: TF-IDF classifiers fell to F1 about 0.38-0.42 while emitting high-confidence "benign", so they never escalated. A 2B LoRA LLM fell to F1 0.687 on obfuscated inputs ([arXiv 2512.19011v3](https://arxiv.org/html/2512.19011v3)). Laya's README reports ECE 0.466 before temperature fitting (vendor).
- **Non-determinism.** At temperature 0, 1,000 runs of one prompt gave 80 distinct completions, mainly from batch-size variance in serving kernels (Thinking Machines, Sep 2025, [blog](https://thinkingmachines.ai/blog/defeating-nondeterminism-in-llm-inference/)). For authorization, the same request can get different verdicts under load unless batch-invariant kernels are used. Encoders at fixed batch=1 on CPU are much more reproducible.

### 6.2 WAAG-specific attack surfaces

- **The judged text is attacker-reachable.** The A2A message is written by the delegating agent's LLM (grounding §12). If that agent read a poisoned document, the attacker writes the input to the intent model, which is the classic "LLM judge reads the injection" setup. The injection can address the judge directly, for example a note that the reviewer should mark the request on-task.
- **Window and truncation games.**
  - `argumentsFlat` cuts top-level strings at 2000 chars, and key order is non-deterministic.
  - Encoders cut at 512 tokens (PG2) or 1-8K (ModernBERT).
  - Padding can push the payload out of the scored window, as the PG2 truncation result shows.
  - Score overlapping windows and take the max. Also score the exact bytes forwarded downstream.
- **Parse differentials.**
  - `metadata.arguments.input` overwrites the A2A text (grounding §14 #21).
  - Only the text part is lifted into `input`.
  - If a downstream agent also consumes other parts (DataParts, files) that the model never scored, the model is bypassed.
  - The scored text must equal what the recipient acts on.
- **Tenant spoofing of the model's context.** `X-WS-Tenant` overrides the verified tenant claim (grounding §14 #3). Any per-tenant model, threshold or memory selected by that header can be switched by the caller.
- **Cheap probing.** A caller with a valid token can send variants and watch ALLOW/DENY to learn the classifier's boundary. Rate-limit denials attributed to the model and alert on bursts of near-threshold scores.

### 6.3 Design rules that follow

1. Monotonic use: the model may only raise risk or deny/escalate, never grant (§4.3). A successful evasion then falls back to baseline deterministic authorization, not to escalation of privilege.
2. Treat the score as one signal among several. Combine it with deterministic features the attacker does not control: act_chain depth, root type, capability class, argument bounds, per-trace counts. The ensemble in arXiv 2512.19011 had only 23% error overlap between classifier types.
3. Calibrate per tenant on held-out data. Use an abstain band that maps to REQUIRE_APPROVAL, which needs a PDP change because the engine has only ALLOW/DENY (grounding §13b).
4. Red-team every model version with adaptive attacks (character, paraphrase, multilingual, window-padding, judge-addressed) before shipping. Publish the evaluation in the customer's evidence pack.
5. Canonicalize before scoring: NFKC normalization, zero-width character removal, homoglyph folding, whitespace collapse.

---

## 7. What "memory" could mean, and its risks

The CEO's proposal includes "use memory concept with the LLM" (source 03). There are four different things this can mean.

| Meaning | Mechanism in WAAG | Value | Risks | Controls |
|---|---|---|---|---|
| **7.1 Chain/session context** (deterministic) | Read the parent hop's text via `corr_id` from `InFlightRequestRegistry` or `pdp_audit_log`, plus per-trace call history. Feed "root request → this delegation → this tool call" to the model or to rules. | Highest. It fixes the biggest blind spot (grounding §13f-g). No training needed. | Parent text is still LLM-written. Async ledger lag is untested under load (§13f). The registry is per-JVM. | Read from in-memory first, then the DB. Carry a signed parent-text hash in the OBO so a child can verify it. Treat it as evidence, not authority. |
| **7.2 Retrieval memory** (vector store of past approved intents, examples, tenant policies) | Embed requests and retrieve nearest "known-good" cases as few-shot context or kNN votes. | Adapts to a tenant without fine-tuning. Explainable ("similar to approved case X"). | **Poisoning:** AgentPoison reached over 80% attack success with under 1% benign impact at under 0.1% poison rate ([arXiv 2407.12784](https://arxiv.org/abs/2407.12784), NeurIPS 2024). **Embeddings leak text:** Vec2Text recovered 92% of 32-token inputs exactly, including names from clinical notes ([arXiv 2310.06816](https://arxiv.org/abs/2310.06816), EMNLP 2023). **Cross-tenant bleed** if the index or cache is shared. WAAG's policy assistant already has one global metadata cache (grounding §14 #10). | Per-tenant indexes keyed by the *verified* tenant claim. Write path limited to human-approved or adjudicated outcomes. TTL and deletion. Embeddings treated as personal data, encrypted at rest. Provenance per entry. |
| **7.3 Fine-tuning / online learning** from decisions and feedback | Periodic LoRA or head retraining on the tenant's labelled traffic. | Best accuracy per domain. | **Feedback-loop poisoning:** an attacker who can generate "allowed" traffic teaches the model to allow. About 250 poisoned documents backdoored 600M-13B models (§5.2). Memorization and extraction of PII. Erasure requests are hard to honor once data is in weights. Silent model drift breaks audit replay. | No online learning. Offline, reviewed retraining with signed, versioned checkpoints. Holdout and red-team gates. Pin model version per decision in audit. |
| **7.4 Serving caches** (KV/prefix cache in vLLM or llama.cpp) | Automatic in LLM servers. | Latency. | Timing side channels. PROMPTPEEK, EarlyBird and InputSnatch reached up to 100% success against unprotected vLLM/SGLang ([arXiv 2608.09225](https://arxiv.org/abs/2608.09225), 2026). | One sidecar per tenant, or `cache_salt` per tenant/principal ([vLLM RFC #16016](https://github.com/vllm-project/vllm/issues/16016)). Or disable prefix caching. |

This matches the Jev CEO's principle, quoted to him from TealTiger/Dakera: storage serves as evidence and continuity, not as authority (source 03). **Recommendation:** ship 7.1 first. Offer 7.2 as a per-tenant option with strict write-gating. Do not do 7.3 online. Enforce 7.4 isolation whenever a shared LLM sidecar exists.

---

## 8. Options table

Latencies are for a 512-token input, single request, and are marked estimate where not measured (§3). Footprint is weights only, excluding runtime overhead.

| # | Option | Where it runs | Latency added per hop | Footprint | Robustness (adversarial / OOD) | Ops cost | Best use in WAAG |
|---|---|---|---|---|---|---|---|
| 0 | **No model.** Deterministic chain context + rules (parent-text link, per-trace counters, argument bounds, capability class, allow-lists) | In-JVM | under 1-2 ms | none | Highest. Not evadable by wording. Blind to semantics. | Low | Baseline for every hop. It closes most gaps in §13g without ML. |
| 1 | Keyword/regex + TF-IDF linear model | In-JVM | under 5 ms (3.4-5.3 ms measured for SVM) | under 50 MB | Fair on obfuscation, poor OOD, with confident misses | Low | Cheap pre-filter; the existing EgressClassifier pattern |
| 2 | Small embedding similarity (bge/e5/gte-small, 33M) | In-JVM ORT/Spring AI | about 5-20 ms CPU (est.) | 133 MB FP32 / about 35 MB INT8 | Low. Similarity can be padded. | Low | "Off-topic delegation" flag vs. skill/tool description or parent text. Deny/escalate only. |
| 3 | Fine-tuned encoder classifier (ModernBERT-base / DeBERTa-v3-base) | In-JVM ORT (CPU) or GPU sidecar | CPU INT8 about 20-70 ms (est.). GPU 10-30 ms (27.5 ms measured, DeBERTa, 256 tok). | 150-600 MB | Moderate in-distribution. Weak OOD without adversarial training. Deterministic at batch 1. | Medium: labelled data, calibration, retraining cadence, eval harness | Primary "intent label" and "injected/on-task" signal on A2A text |
| 4 | Zero-shot NLI / GLiClass (ModernBERT-large) | In-JVM or GPU | NLI: N × (40-200 ms CPU est.). GLiClass: one pass. | 0.6-1.7 GB | Weak. Label-wording sensitive. | Low-medium | Bootstrapping labels. Not for enforcement. |
| 5 | Prompt Guard 2 (22M / 86M) | In-JVM ORT or sidecar | 19 / 92 ms A100 (vendor). 86M: 19 ms p50 on M4 Pro. CPU loaded p50 280 ms. | 283 MB / 1.1 GB FP32 | Good precision, recall gaps (about 0% on plain-request injections). Known bypass history. | Low-medium. Llama 4 license. | Injection flag on A2A text and on tool outputs (egress) |
| 6 | Small guard LLM: Qwen3Guard-Gen-0.6B, Llama Guard 3-1B | GPU sidecar (CPU possible, slow) | GPU about 30-60 ms (est.). CPU 0.3-0.8 s (est.). | about 1.5-3 GB BF16; about 0.5-1 GB Q4 | Content-harm taxonomy, not intent. Injectable (LG3 card). | Medium-high | Content-safety of A2A text. Low priority for authorization. |
| 7 | BYOC guard (Granite Guardian 4.1-8B; 3.2-5B / 3B-A800M) | GPU sidecar | about 100-300 ms (est.) | 6-16 GB BF16 | Moderate. Custom criteria are still prompt-driven. | High (GPU) | "Does this tool call fit the request?" judge for high-risk capabilities |
| 8 | Small LLM judge with constrained decoding (Qwen3/3.5 0.6-4B, Gemma 4 E2B/E4B, Phi-4-mini, Llama 3.2 1-3B) | GPU sidecar (vLLM/llama.cpp). Ollama only for PoC. | GPU about 30-150 ms (est.). CPU 0.3-3 s. | Q4: 0.5-2.9 GB. BF16: 1.4-8 GB. | Lowest. Reads attacker text. Master-key tokens. Non-deterministic under batching. | High: GPU, sidecar patching (GGUF/Ollama CVEs), prompt/version management, eval | Opt-in escalation tier for flagged or high-value A2A hops. Never the only gate. |
| 9 | Remote hosted model (Anthropic etc.) | Outside customer env | 0.5-3 s | none on-prem | As for 8 | Low ops, but data leaves customer env | Excluded by the CEO's premise. Admin-time only, as today. |

### Recommended staging (architect + product view)

1. **Now: no model.** Implement option 0.
   - Link parent to child via `corr_id`.
   - Expose chain features as `context.*` longs and strings.
   - Add REQUIRE_APPROVAL to the PDP.
   - Fix the SPI's fail-open behaviour.

   This delivers most of the "intent drift" story honestly. It is the "smallest primitive" answer.
2. **Next: optional CPU tier** (options 2+3, or 5), in-JVM through ORT.
   - Ship Apache/MIT weights, signed and in INT8.
   - Scores are used only in `forbid` or escalate rules.
   - Per-tenant thresholds with an abstain band.
   - Budget: at most 50 ms p95 on 2 dedicated cores.
3. **Later: opt-in GPU sidecar** (option 8 or 7) for customers who already have GPUs.
   - Used on a small fraction of hops: those flagged by stage 2, or capabilities tagged high-risk.
   - Output via `choice` constrained decoding.
   - Per-tenant cache salting.
   - Full audit of model id, digest, prompt hash and score.
4. **Never:** online learning, a model output as sole permit basis, a shared cross-tenant cache or index, or unsigned or pickle weights.

---

## 9. Verified facts vs. vendor claims

**Verified (primary metadata or independent sources).** HF API parameter counts, licenses, gating and dates in §2. ORT Java artifacts. DJL tokenizers. Spring AI ONNX embedding config. TGI maintenance mode. OMS v1.0. The Llama 4 license terms. GGUF and Ollama CVEs. Hackett et al. The Nasr/Carlini adaptive-attack paper. Liu et al. calibration. AgentPoison. Vec2Text. The "250 documents" result. KV-cache side-channel papers. The independent PG2 benchmark (small, self-labelled). The tinfoil CPU deployment numbers. The CEUR CPU paper.

**Vendor claims (not independently reproduced).**
- Meta PG2 AUC, recall and A100 latency.
- ModernBERT "2x faster than DeBERTa".
- GLiClass "cross-encoder quality in one pass".
- Laya latency, ECE and accuracy, and its 25.4k GitHub stars (reported by WebFetch; the GitHub API was rate-limited, so unverified).
- Qwen3Guard-Stream 0.5 ms/token.
- Granite Guardian F1s.
- Reva's "p90 below 40 ms" and "98% accuracy" (source 01).

**Estimates (mine).** All CPU single-request figures marked "est." in §3.2, §3.3 and §8.

## 10. Open questions

1. What hardware do target customers actually run: x86 generation (AMX?), Arm/Graviton, GPU availability, Kubernetes vs. VM appliance? This decides between the CPU in-JVM tier and a GPU sidecar.
2. Measure on WAAG's own box: ORT Java INT8 ModernBERT-base and PG2-86M at 256/512/1024 tokens, p50/p95, 1-8 threads, under the demo's concurrency. No citable single-request CPU number exists for exactly this setup.
3. What labelled data can WhiteSwan obtain for an "on-task / off-task / injected" A2A classifier (synthetic from the four sample agents, customer shadow-mode logs)? Who labels it?
4. Can the console or callers be persuaded to forward the root human request (A2A metadata or an OBO claim)? Without it, every model judges a paraphrase.
5. Does any real caller send A2A DataParts or files that downstream agents consume but WAAG does not score (parse-differential risk)?
6. Should the PDP gain a REQUIRE_APPROVAL outcome and obligations before any model ships? Without an abstain path, calibration bands have nowhere to go.
7. Licensing: is Prompt Guard 2's precision worth carrying Llama 4 attribution and naming terms in the product, or should WhiteSwan fine-tune its own ModernBERT (Apache) injection head?
8. Multi-instance deployment: per-JVM in-flight registries and caches (grounding §15 Q2) affect chain-context memory as well as any model cache.
9. Maintenance of java-llama.cpp (lagging upstream) vs. a sidecar process boundary. Crash isolation argues for the sidecar.

## Sources (accessed 2026-09-26)
- WhiteSwan internal: `docs/others/gateway-grounding.md` §§6, 12-15; sources 01-05 in `intent-research/sources`.
- Model cards: [PG2-86M](https://huggingface.co/meta-llama/Llama-Prompt-Guard-2-86M), [Llama Guard 3-1B](https://huggingface.co/meta-llama/Llama-Guard-3-1B), [Llama Guard 4](https://huggingface.co/meta-llama/Llama-Guard-4-12B), [ShieldGemma-2B](https://huggingface.co/google/shieldgemma-2b), [Granite Guardian 3.3-8B](https://huggingface.co/ibm-granite/granite-guardian-3.3-8b), [Granite Guardian 4.1-8B](https://huggingface.co/ibm-granite/granite-guardian-4.1-8b), [Qwen3Guard-Gen-0.6B](https://huggingface.co/Qwen/Qwen3Guard-Gen-0.6B), [GLiClass](https://huggingface.co/knowledgator/gliclass-modern-base-v2.0-init), HF model API for all parameter/license rows.
- [ModernBERT blog (2024-12-19)](https://huggingface.co/blog/modernbert); [Gemma 4 Apache-2.0 (Google OSS blog, 2026)](https://opensource.googleblog.com/2026/03/gemma-4-expanding-the-gemmaverse-with-apache-20.html); [Laya](https://github.com/NandhaKishorM/laya).
- Latency: [vLLM Semantic Router arXiv 2603.12646](https://arxiv.org/html/2603.12646v1); [arXiv 2512.19011v3](https://arxiv.org/html/2512.19011v3); [PG2 indirect-injection benchmark](https://github.com/briankhoi/llama-prompt-guard-2-benchmark); [tinfoilsh/confidential-llama-guard](https://github.com/tinfoilsh/confidential-llama-guard); [CEUR Vol-4164 paper11](https://ceur-ws.org/Vol-4164/paper11.pdf); [Qwen speed benchmark](https://qwen.readthedocs.io/en/latest/getting_started/speed_benchmark.html); [Qwen3Guard report](https://arxiv.org/abs/2510.14276); [MS OSS blog ORT+OpenVINO (2023-01-25)](https://opensource.microsoft.com/blog/2023/01/25/improve-bert-inference-speed-by-combining-the-power-of-optimum-openvino-onnx-runtime-and-azure/); [arXiv 2608.18182](https://arxiv.org/abs/2608.18182).
- Serving: [ORT Java](https://onnxruntime.ai/docs/get-started/with-java.html); [ORT thread-safety discussion](https://github.com/microsoft/onnxruntime/discussions/10107); [ORT GenAI Java](https://onnxruntime.ai/docs/genai/api/java.html); [DJL tokenizers](https://docs.djl.ai/master/extensions/tokenizers/index.html); [Spring AI ONNX embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/onnx.html); [java-llama.cpp](https://github.com/kherud/java-llama.cpp); [vLLM structured outputs](https://docs.vllm.ai/en/latest/features/structured_outputs.html); [Ollama structured outputs (2024-12-06)](https://ollama.com/blog/structured-outputs); [TGI maintenance notice](https://github.com/huggingface/text-generation-inference).
- Supply chain: [OMS v1.0 (2025-04-04)](https://openssf.org/blog/2025/04/04/launch-of-model-signing-v1-0-openssf-ai-ml-working-group-secures-the-machine-learning-supply-chain/); [model-transparency](https://github.com/sigstore/model-transparency); [NVIDIA NGC signing](https://developer.nvidia.com/blog/bringing-verifiable-trust-to-ai-models-model-signing-in-ngc); [safetensors audit](https://huggingface.co/blog/safetensors-security-audit); [nullifAI](https://thehackernews.com/2025/02/malicious-ml-models-found-on-hugging.html); [Talos GGUF](https://talosintelligence.com/vulnerability_reports/TALOS-2024-1913); [Databricks GGUF](https://www.databricks.com/blog/ggml-gguf-file-format-vulnerabilities); [llama.cpp GHSA-vgg9-87g3-85w8](https://github.com/ggml-org/llama.cpp/security/advisories/GHSA-vgg9-87g3-85w8); [Probllama](https://www.wiz.io/blog/probllama-ollama-vulnerability-cve-2024-37032); [ModelPack](https://github.com/modelpack/model-spec); [KitOps](https://github.com/kitops-ml/kitops); [Llama 4 license](https://github.com/meta-llama/llama-models/blob/main/models/llama4/LICENSE).
- Robustness: [Cisco/Robust Intelligence PG bypass](https://blogs.cisco.com/security/bypassing-metas-llama-classifier-a-simple-jailbreak); [Hackett et al. 2504.11168](https://arxiv.org/abs/2504.11168); [Attacker Moves Second 2510.09023](https://arxiv.org/abs/2510.09023); [One Token to Fool 2507.08794](https://arxiv.org/abs/2507.08794); [Guard calibration 2410.10414](https://arxiv.org/abs/2410.10414); [Thinking Machines nondeterminism](https://thinkingmachines.ai/blog/defeating-nondeterminism-in-llm-inference/).
- Memory: [AgentPoison 2407.12784](https://arxiv.org/abs/2407.12784); [Vec2Text 2310.06816](https://arxiv.org/abs/2310.06816); [Anthropic small-samples poisoning](https://www.anthropic.com/research/small-samples-poison); [KV-cache governance 2608.09225](https://arxiv.org/abs/2608.09225); [vLLM cache_salt RFC #16016](https://github.com/vllm-project/vllm/issues/16016).
