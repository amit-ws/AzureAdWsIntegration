# Academic research on task alignment, privilege control and injection defense: what an inline gateway can use

*Dossier for the WAAG intent-aware authorization research. Compiled 2026-09-26. Every external fact has a URL. Numbers are **author-reported** unless marked otherwise: they are verified as stated in the paper, not reproduced independently. Direct quotes are kept under 15 words.*

## Executive summary (10 lines)

1. The research has moved from "detect bad text" to **"authorize the action against the task"**. The robust designs keep a deterministic enforcer and let an LLM only *propose* policy (CaMeL, Progent, Conseca, DRIFT, IGAC, IntentCap).
2. Detectors and LLM judges that work on content (PromptGuard, spotlighting, MELON and similar) score near-zero attack success on static benchmarks. Adaptive attackers take them back to **50–100%** ([2503.00061](https://arxiv.org/abs/2503.00061), [2510.09023](https://arxiv.org/abs/2510.09023)).
3. Deterministic "out-of-band" enforcement held up in the one adaptive test published so far (Progent: 4.2% → 2.6%). That is a single, weak data point ([2606.26479](https://arxiv.org/abs/2606.26479), June 2026).
4. The best-performing systems (CaMeL, FIDES, IPIGuard, DRIFT, MELON, AlignmentCheck) run **inside the agent**: they need its planner, its context window or its chain of thought. WAAG cannot run them, but it can copy their *invariants*.
5. Four ideas transfer to an external gateway with high value. (1) A **task-scoped envelope that can only narrow** (Progent/IGAC/IntentCap), bound into the per-hop OBO so a child never exceeds its parent.
6. (2) **Trace-level taint with the "Rule of Two"** (a coarse FIDES/CaMeL analogue): a trace that has read untrusted content cannot take a consequential action without step-up.
7. (3) A **learned per-workflow behavior automaton** (Praetor pDFA, about 2.2 ms per call, no LLM; Skynet graph model, under 1% FPR) built from the audit ledger WAAG already keeps.
8. (4) A **small-model or LLM intent extractor used only at the trusted ingress**, emitting typed intent plus confidence. It is advisory or triggers step-up and is never the only thing that allows a call. ASTRA and IGAC show that intent-to-scope matching is the bottleneck (recall falls from 0.99 to 0.57 as the number of scopes grows).
9. A key finding for WAAG: after hop 1, the NL text in `argumentsFlat` is **written by an agent that may already be compromised**. It has low integrity. Intent must be captured at the trusted root and carried forward, not re-inferred downstream.
10. These ideas need PDP primitives WAAG lacks today: OR/NOT, set containment over arguments, per-trace history attributes, and **obligations** (STEP_UP/HITL). The next step: evaluate against AgentDojo/ASB/MSB wrapped as MCP servers, with adaptive attacks.

---

## 1. Scope, method and the "gateway lens"

**Method.**
- Paper metadata and abstracts came from the arXiv export API (exact text).
- Detailed numbers came from the arXiv HTML full texts through targeted WebFetch questions.
- Blog and standards items came from WebSearch/WebFetch.
- No code was cloned, installed or run.
- Where the WebFetch digest was internally inconsistent (IPIGuard), this is flagged.

**The gateway lens.** Every idea is judged by what WAAG can actually observe. The source is `docs/others/gateway-grounding.md` §6 and §13.

| WAAG sees at decision time | WAAG does **not** see |
|---|---|
| Tool/skill name, structured arguments (sanitized, top-level strings cut at 2000 chars), identities, verified `act_chain` rooted at the human, `trace_id`, parent `corr_id` (present but unread), parent `scope` (unread) | The agent's context window, system prompt, chain of thought, retrieved documents, and the tool *outputs* the agent read before choosing this call (except responses that crossed WAAG itself, which reach only the async classifier) |
| On A2A only: the delegating agent's natural-language message (`argumentsFlat` = `input=...`) | The **human's original words** on the console path. The console LLM paraphrases them before hop 1 (grounding §12.4) |
| Tool descriptions and inputSchema (in memory, not passed to the PDP) | MCP `_meta` (dropped); any purpose field (none exists in any envelope) |
| Its own audit ledger (`gateway_audit_log`, `pdp_audit_log`), written async, keyed by trace/correlation | Per-trace history at decision time (nothing reads it today) |

**PDP constraints that shape transfer:**
- The operators are `==`, `!=`, integer compare, `like`, `.contains` and `&&`. There is no OR/NOT.
- The output is ALLOW/DENY only, with no obligations.
- Some head forms are ignored, which widens grants.
- Governance overhead is about 12–13 ms p50 per hop, and every call is synchronous and blocking.

**Transfer ratings used below:**
- **High**: can run at the gateway with data it has or can cheaply obtain.
- **Medium**: needs a new ingress signal, token claim or history store.
- **Low**: needs control of the agent's planner or context; only the *principle* transfers.

---

## 2. A map of the field: where each defense runs

| Layer | What it controls | Representative work | Can an external gateway host it? |
|---|---|---|---|
| **A. Model** (training/prompting) | How the LLM treats embedded instructions | Spotlighting, StruQ, SecAlign/MetaSecAlign, RETA | No. Also the class most broken by adaptive attacks |
| **B. Agent architecture** (planner/executor split, IFC inside the agent) | Untrusted data cannot change control flow | CaMeL, FIDES, f-secure, IsolateGPT, ACE, IPIGuard, DRIFT, Design Patterns | No, but the *invariants* can be enforced coarsely at hop granularity |
| **C. In-agent monitors / LLM judges** | Is this action aligned with the user task? | Task Shield, LlamaFirewall AlignmentCheck, MELON, GuardAgent, ShieldAgent | Partly. The judge could run at the gateway, but it loses the context it depends on |
| **D. Deterministic reference monitors at the tool boundary** | Allowed tool/argument sets, rules, types | Progent, Conseca, AgentSpec, AgentArmor, RTBAS, MiniScope, HCP | **Yes.** This is exactly the gateway position |
| **E. Delegation and intent tokens** | Carry authority, lineage and intent across hops | A-JWT, IGAC intent certificates, IntentCap leases, SAGA, HDP, IPP, ASTRA/TBAC | **Yes.** WAAG already mints the per-hop token |
| **F. Behavioral anomaly detection** | Is this trajectory normal for this workflow? | Praetor (pDFA), Skynet (graph), SentinelAgent, temporal assertions | **Yes.** Needs per-trace history at decision time |
| **G. Agent-to-agent message firewalls** | Project inter-agent text onto task-relevant structure | Firewalls for agentic networks (Language Converter / Data Abstraction) | **Yes, on A2A.** It is a gateway-shaped design |

---

## 3. Master comparison table

ASR = attack success rate. UA = utility under attack. BU = benign utility.

| System (venue/date) | Runs where | Inputs it needs | Stops | Reported ASR / utility | Latency / cost | Gateway transfer |
|---|---|---|---|---|---|---|
| **CaMeL** (Google/DeepMind/ETH, 2025) | Inside agent (P-LLM + Q-LLM + interpreter) | Trusted user query; agent-generated code; capability tags on values | IPI changing control flow; exfiltration via policy | AgentDojo: 77% tasks solved with provable security vs 84% undefended (abstract) | 2.82× input, 2.73× output tokens | Low (principle: data-flow policy at tool call) |
| **FIDES** (Microsoft, 2025) | Inside agent planner | Confidentiality and integrity labels on every tool result | IPI → consequential action; leaks to unauthorized readers | GPT-4o: 156 → 1 successful attack with policy; utility cost significant | Not quantified | Low–Medium (coarse labels per hop/trace) |
| **f-secure** (2024) | Inside agent (planner + monitor + executor) | Trusted/untrusted source labels | IPI into planning | InjecAgent: 0% ASR | Monitor cost ~0.0001× of planning | Low |
| **IsolateGPT** (NDSS'25) | Inside LLM app platform (hub-and-spoke) | App boundaries | Cross-app compromise | Qualitative; "no loss of functionality" | <30% overhead on 75% of queries | Low (principle: isolation per app ≈ per-hop scoping) |
| **ACE** (NDSS'26) | Inside app system | Abstract plan from trusted info; flow constraints | Planning/execution integrity attacks | Secure on InjecAgent/ASB | Not given in abstract | Low–Medium (plan-then-verify) |
| **IPIGuard** (EMNLP'25) | Inside agent (tool dependency graph) | User instruction, tool specs | Unplanned (injected) tool calls | GPT-4o-mini, important-instructions: ASR 27.19% → 0.64% (digest; see §4.1) | ~2.4× input tokens; 13.9 s vs 7.1 s per task | Low (principle: plan-bounded tool set) |
| **DRIFT** (NeurIPS'25) | Inside agent (planner + validator + isolator) | User query; JSON-schema checklist | IPI, memory-persistent injection | GPT-4o-mini AgentDojo: ASR 30.7% → 1.4%; BU 63.6% → 57.3% | ~1.9× tokens | Medium (per-call checklist = envelope) |
| **Progent** (Berkeley, 2025/2026) | Tool-call boundary (deterministic) + LLM policy generator | User task (benign context), tool schemas | Unauthorized tool calls and arguments | AgentDojo 39.9% → 1.0%; ASB 70.3% → 3.9%; manual policies → 0% on ASB | Not reported; Z3 check per update | **High** |
| **Conseca** (Google, HotOS'25) | Deterministic enforcer + LLM policy generator | Task + trusted context only; tool docs | Contextually inappropriate actions | 20 synthetic tasks: utility 60% vs 61% permissive / 0% restrictive | Policy generation "seconds" | **High** |
| **MiniScope** (Berkeley, Dec 2025) | Middleware between agent and services | Task, tool→OAuth-scope map | Over-privilege (not in-scope attacks) | LLM baselines only 70–83% optimal; 1.04–2.19× over-privilege | 1–6% overhead | **High** (scope hierarchy + ILP) |
| **IGAC** (Accentrust, Jun 2026) | **Server-side gateway** | Trusted request → intent certificate | Authority beyond request; unsafe effects | No completed unsafe executions; residual accepted authority 0.09–0.27 | Median 0.18–0.25 s | **High** |
| **IntentCap** (Sep 2026) | MCP adapters + OS (eBPF) + delegation | Four labeled sources → capability lease | Scope widening by lower-trust sources | 0 unsafe accepts / 3,746 events; 2,554 / 2,556 benign checks | Latency not measured | **High** |
| **ASTRA / semantic task-to-scope** (Cisco, Oct 2025) | Authorization server | Task text + requested scopes | Over-scoped tokens | GPT-4o: F1 0.96 (1 tool) → 0.67 (3 tools) | Not given | **High** (as evidence of limits) |
| **Task Shield** (Penn State, Dec 2024) | Inside agent loop (LLM judge) | User msgs, assistant msgs, tool calls, outputs | Actions not serving user goals | GPT-4o: ASR 2.07%, UA 69.79% | Not reported ("high cost") | Medium (A2A text only) |
| **AlignmentCheck** (Meta LlamaFirewall, May 2025) | Inside agent loop | User goal + full trace incl. reasoning | Goal hijack | AgentDojo ASR 17.6% → 2.9% (with PromptGuard: 1.75%); utility 47.7% → 42.7% | Large model; latency not given | Low–Medium (no CoT at gateway) |
| **MELON** (ICML'25) | Inside agent (re-execution) | Full trajectory + ability to re-run the LLM | Tool calls independent of the user task | GPT-4o: ASR 0.24%, UA 58.8% | ~2× model calls | Low; **broken 76–95%** adaptively |
| **GuardAgent** (ICML'25) | Guard agent generating guard code | Guard requests + target agent I/O | Access-control and safety violations | 98% (EICU-AC), 83% (Mind2Web-SC) accuracy | LLM per check | Medium |
| **ShieldAgent** (2025) | Guard agent over trajectories | Policy documents + action trajectory | Policy violations | ~90% accuracy, 4.8% FPR | 31–34 s per sample | Low (too slow inline) |
| **AgentSpec** (ICSE'26) | Agent framework hook (LangChain) | Rules: trigger / predicate / enforce | Unsafe actions | >90% unsafe code executions prevented | ~1–3 ms | **High** (rule DSL shape) |
| **AgentArmor** (2025) | Trace → program graph + type system | Runtime trace + tool/data property registry | IPI data/control flows | AgentDojo ASR → 3%, utility −1% | Not given in abstract | Medium |
| **RTBAS** (2025) | Agent + IFC screeners | Dependencies (LLM judge or attention) | Injection and privacy leaks | AgentDojo: all targeted attacks blocked, −2% utility | Not given | Low–Medium |
| **SAGA** (2025) | Central Provider + agent tokens | Registry + contact policies | Unauthorized agent-to-agent contact | n/a (not intent) | ~ms crypto; <25 ms amortized | Medium (already largely in WAAG) |
| **Agentic JWT** (Sep 2025) | Client shim + IdP + resource server | Registered workflow id/step; agent checksum | Step bypass, scope inflation, replay | Self-reported "100%" blocked; no benchmark | Sub-ms claimed | **High** (token-claim design) |
| **Praetor** (Apr 2026) | **Sidecar gateway at tool dispatch** | Benign tool-call telemetry | Off-trajectory calls, multi-step exfiltration | ASB macro ASR 5.6% (2.2% structured); BTFR 2.0% | **2.2 ms p50** | **High** |
| **Skynet** (Sep 2026) | Workflow monitor | Tool calls, inter-agent messages, events (no model internals) | Anomalous workflows | Recall 89.4% at 0.89% FPR combined; indirect-PI recall 74% | ~5–105 ms per step | **High** (async / near-line) |
| **Firewalls for agentic networks** (TMLR 2026) | Boundary between agents | Task context; rules learned from demonstrations | Manipulation and over-disclosure across agents | Privacy 84% → 10% (GPT-5); security 60% → 3% | Not in abstract | **High for A2A** |

---

## 4. Paper-by-paper notes

Each entry covers the core idea, where it runs, inputs, what it stops, numbers, cost, and transfer to WAAG.

### 4.1 Architectural isolation (the agent is rebuilt)

**CaMeL: "Defeating Prompt Injections by Design"** ([arXiv 2503.18813](https://arxiv.org/abs/2503.18813), Debenedetti, Shumailov, … Tramèr; v2 June 2025)
- **Idea.** A privileged LLM sees only the trusted user query and writes a restricted-Python program. A quarantined LLM parses untrusted data with no tool access. A custom interpreter tracks provenance and allowed-readers "capabilities" on every value, and it checks security policies (Python functions) before each tool call.
- **Inputs.** Trusted user query; tool set; developer-written policies.
- **Stops.** Untrusted data changing control flow; exfiltration through unauthorized data flows.
- **Does not stop.** Text-to-text manipulation (for example, a misleading summary), phishing-style impersonation, side channels (indirect inference, exception leaks, timing). The user prompt is assumed trusted ([HTML](https://arxiv.org/html/2503.18813v2)).
- **Numbers.** 77% of AgentDojo tasks solved with provable security vs 84% undefended (abstract). The digest reports successful attacks going from 300 to 0 for one model.
- **Cost.** Median 2.82× input and 2.73× output tokens.
- **Transfer: Low.** WAAG does not own the planner. **Borrowable:** the policy shape "argument X's *provenance* must be user/trusted before tool Y may send data to recipient Z" becomes, at a gateway, a check on whether the data could have come from an untrusted hop in this trace (see §7, idea 2).

**FIDES: "Securing AI Agents with Information-Flow Control"** ([arXiv 2505.23643](https://arxiv.org/abs/2505.23643), Microsoft Research, Costa, Köpf, … Zanella-Béguelin; v2 Sep 2025; [tutorial](https://github.com/microsoft/fides))
- **Idea.** A planner tracks a lattice of confidentiality and integrity labels by dynamic taint tracking. Two generic policies apply:
  - *trusted action*: consequential tools only run in a trusted context;
  - *permitted flow*: data goes only to allowed readers.
- **Primitives.** Data can be *hidden* in variables so the context label stays clean. A constrained "inspect" query can return bool/enum, so labels stay small.
- **Numbers** ([HTML](https://arxiv.org/html/2505.23643v2)). GPT-4o on AgentDojo: 156 successful attacks (basic) → 1 with policies.
- **Utility cost.** Checks cost up to about 40% utility for a basic planner; FIDES mitigates this to about 24%. Reasoning models (o1/o3) recover more.
- **Limits.** Explicit secrecy only, not non-interference; conservative label propagation through the LLM.
- **Transfer: Low–Medium.** The label algebra is directly usable at hop granularity. Each capability gets an integrity label on its outputs ("reads untrusted content") and a consequence label on its effects ("state-changing/egress"). The label is joined across the trace.

**f-secure LLM system** ([arXiv 2409.19091](https://arxiv.org/abs/2409.19091), Wu, Cecchetti, Xiao; Sep 2024)
- **Idea.** An IFC-based split into planner, security monitor, structured executable plan and rule-based executor. The monitor filters untrusted inputs out of planning.
- **Numbers.** InjecAgent ASR 0% across tested models vs 51.6–67.4% for vanilla ReAct (GPT-3.5). Monitor overhead is tiny ([HTML](https://arxiv.org/html/2409.19091v2)).
- **Transfer: Low.** The same "labels join to the least trusted source" rule applies at trace level.

**IsolateGPT** ([arXiv 2403.04960](https://arxiv.org/abs/2403.04960), NDSS 2025)
- **Idea.** Hub-and-spoke execution isolation between third-party LLM apps.
- **Numbers.** Under 30% overhead on three-quarters of queries.
- **Transfer: Low.** WAAG already isolates per hop through per-capability tokens. **ACE** ([arXiv 2504.20984](https://arxiv.org/abs/2504.20984), NDSS 2026) shows attacks on IsolateGPT and adds an abstract-plan-then-concretize design with static flow verification.

**IPIGuard** ([arXiv 2508.15310](https://arxiv.org/abs/2508.15310), EMNLP 2025)
- **Idea.** Plan a Tool Dependency Graph before touching external data. During execution, allow only argument estimation, read-only node expansion and "fake" invocation of injected write calls.
- **Numbers.** GPT-4o-mini, important-instructions attack: ASR 27.19% → 0.64% and UA 49.92% → 57.07%, per the WebFetch digest of Table 1. A second digest pass gave inconsistent averages, so confirm against the PDF before quoting externally.
- **Cost.** About 2.4× input tokens; 13.88 s vs 7.13 s per task.
- **Transfer: Low.** The principle that transfers is that write tools must be *in the plan*. A gateway can hold a declared plan (tool set) per trace and deny unplanned write-class capabilities.

**DRIFT** ([arXiv 2506.12104](https://arxiv.org/abs/2506.12104), NeurIPS 2025)
- **Idea.** A secure planner emits a minimal function trajectory and a JSON-schema parameter checklist. A dynamic validator allows deviations for read functions but checks write/execute deviations for privilege and intent. An injection isolator masks conflicting instructions in memory.
- **Numbers.** GPT-4o-mini AgentDojo: ASR 30.7% → 1.4%; benign utility 63.6% → 57.3%; utility under attack 48.3% → 50.9%.
- **Cost.** About 1.9× tokens, reported cheaper than CaMeL and Progent ([HTML](https://arxiv.org/html/2506.12104v3)).
- **Transfer: Medium.** The **read vs write/execute split** is directly usable. A gateway can auto-allow read-class deviations from an envelope and escalate write-class ones.

**Design Patterns for Securing LLM Agents** ([arXiv 2506.08837](https://arxiv.org/abs/2506.08837), IBM, Invariant Labs, ETH, Google, Microsoft et al., June 2025)
- **Patterns.** Action-Selector, Plan-Then-Execute, LLM Map-Reduce, Dual LLM, Code-Then-Execute, Context-Minimization.
- **Core rule.** Once an agent has ingested untrusted input, that input must be unable to trigger consequential actions ([HTML](https://arxiv.org/html/2506.08837v3)).
- **Transfer: High as a governance vocabulary.** A gateway can *require* a pattern per agent class. Example: agents that read untrusted content may call only Action-Selector-style fixed capabilities.

**Meta "Agents Rule of Two"** ([Meta AI blog, 2025-10-31](https://ai.meta.com/blog/practical-ai-agent-security/); [Willison commentary](https://simonwillison.net/2025/Nov/2/new-prompt-injection-papers/))
- **Rule.** In one session, an agent should have at most two of: [A] processing untrusted input, [B] access to sensitive data or systems, [C] changing state or communicating externally. With all three, it needs supervision such as human-in-the-loop.
- **Transfer: High.** These are three capability labels plus a trace-level join, which is a deterministic policy (idea 2).

### 4.2 Privilege control and context-generated policy (the gateway's natural home)

**Progent: "Securing AI Agents with Privilege Control"** ([arXiv 2504.11703](https://arxiv.org/abs/2504.11703), Shi, He, … Dawn Song; v3 2026-05-14)
- **Idea.** A policy is a set of symbolic allow/forbid rules per tool, with conditions over arguments (comparisons, membership, regex, arrays) expressed as JSON Schema. Fallbacks are terminate, ask the user, or return an error.
- **Generation.** An LLM generates the initial policy from the user task (benign context) and proposes updates as information arrives.
- **Monotonic confinement.** A Z3 SMT check classifies each update. Narrowing is applied automatically; expansion needs approval. Only 6% of updates needed approval.
- **Numbers** ([HTML](https://arxiv.org/html/2504.11703v3)). AgentDojo ASR 39.9% → 1.0%; ASB 70.3% → 3.9% with utility comparable to no defense. Manual policies take ASB to 0%.
- **Cost.** No latency figures reported.
- **Adaptive evaluation.** An independent test ([arXiv 2606.26479](https://arxiv.org/abs/2606.26479), June 2026) measured Qwen2.5-7B at 25.8% undefended → 4.2% with Progent → 2.6% under a hand-crafted adaptive attack. The authors stress this is one weak black-box attack.
- **Transfer: High.** This is the closest academic analogue to "WAAG PDP + capability profile + per-trace envelope". The novel pieces for WAAG are (a) **argument-level conditions**, (b) **per-task policy generation from trusted context only**, and (c) the **narrowing-only update rule**. Rule (c) is exactly the product's P8 "monotonic down-scoping" promise, which is unimplemented today.

**Conseca: "Contextual Agent Security: A Policy for Every Purpose"** ([arXiv 2501.17070](https://arxiv.org/abs/2501.17070), Tsai and Bagdasarian, Google; HotOS 2025)
- **Idea.** Generate a just-in-time, human-readable policy per task. The generator sees only the task and *trusted* context (usernames, file structure, timestamps) plus tool docs, never untrusted content.
- **Policy form.** Per-tool allow flags and regex constraints on arguments, each with a rationale. A deterministic enforcer checks them.
- **Numbers.** 20 synthetic tasks × 5 trials: utility 12.0/20 vs 12.2 permissive-static vs 0.0 restrictive-static vs 14.0 no policy ([HTML](https://arxiv.org/html/2501.17070v3)).
- **Cost.** Policy generation takes "seconds".
- **Limits.** Only as good as the generator; the evaluation is synthetic.
- **Transfer: High.** The *rationale* field is valuable to WAAG's CISO/audit story, because every generated constraint explains itself. Trusted-context-only generation is the key safety property.

**MiniScope** ([arXiv 2512.11147](https://arxiv.org/abs/2512.11147), Berkeley/IBM, Popa; Dec 2025)
- **Idea.** Rebuild each service's permission hierarchy from OAuth scope→method maps. Choose the minimal scope set for a task with an ILP. Ask the user mobile-style (always / once / this session / deny).
- **Deployment.** Middleware "firewall" between agent and services.
- **Numbers** ([HTML](https://arxiv.org/html/2512.11147v1)):
  - LLM-chosen permissions were only 70–83% optimal (proprietary models) and 20–34% (open models), 1.04–2.19× over-privileged;
  - MiniScope adds 1–6% latency;
  - the LLM baseline cost $0.063 per request;
  - simulated user confirmation rates were 18–60%.
- **Limits.** Nothing inside the granted scope is prevented.
- **Transfer: High.** WAAG's capability id (`protocol:type:server:name`) plus a scope hierarchy per MCP server would let the gateway compute least privilege deterministically rather than asking an LLM. The evidence says LLMs over-grant.

**IGAC: "Intent-Governed Tool Authorization for AI Agents"** ([arXiv 2606.22916](https://arxiv.org/abs/2606.22916), Zhu and Wang, Accentrust; v4 2026-09-18)
- **Idea.** A **server-side gateway** turns a *trusted* request into a short-lived **intent certificate**. The certificate is bound to subject, tenant, session and policy version, with intent classes, resource and effect bounds, a confidence score, a review mode (allow / draft / preflight / confirm / deny / clarify), an expiry and an audit digest.
- **Enforcement.** The certificate narrows the visible tool manifest and checks each proposed effect before execution. It can **only reduce** authority below static policy.
- **Who produces it.** Rules, a classifier, a hybrid or a router, all treated as *untrusted* proposers ([HTML](https://arxiv.org/html/2606.22916v4)).
- **Numbers.**
  - Deterministic runtime comparison: the unsafe-authority indicator falls from 1.0 to 0.
  - 306 end-to-end LLM trials: no completed unsafe executions, but accepted unsafe authority of 0.0909–0.2727, all as non-executed drafts.
  - A normalizer removes the residual at "substantial utility cost".
  - Median latency 0.18–0.25 s (p95 0.36–0.47 s); about 76% of tools hidden by intent filtering.
- **Stated bottleneck.** Certificate precision.
- **Transfer: High.** This is the most gateway-shaped intent design in the literature. Its review modes map onto the **obligations** WAAG's PDP lacks.
- **Caveat.** It comes from a small vendor-affiliated team, and most evaluation is synthetic.

**IntentCap: "LLM Agent Capabilities Should Follow Task Intent and Context Source"** ([arXiv 2609.14631](https://arxiv.org/abs/2609.14631), Zheng et al., 2026-09-13)
- **Idea.** Compose a short-lived capability *lease* from four sources with **field-level ownership**:
  - user intent: goal, objects, destinations, approvals;
  - workflow instructions: procedure;
  - tool schemas: interface and credential scope;
  - runtime: observed values.
- **Rules.** No source can fill another's fields. The lease can only narrow the user's authority, and a delegated subtask gets a strictly narrower lease. An LLM compiles the lease; a deterministic `check_and_consume` decides.
- **Numbers** ([HTML](https://arxiv.org/html/2609.14631v1)). 0 unsafe accepts across 3,746 security-sensitive events; 2,554/2,556 benign checks pass. Collapsing source ownership causes 94% false accepts.
- **Not yet measured.** Latency and adaptive attacks.
- **Transfer: High.** Field ownership answers a WAAG-specific question: **which parts of a hop's authority may come from the downstream agent's text and which only from the human root?** A destination or recipient should never come from a tool schema or tool output.

**Semantic task-to-scope matching / ASTRA** ([arXiv 2510.26702](https://arxiv.org/abs/2510.26702), Cisco, Oct 2025)
- **Idea.** An authorization server semantically inspects the task and issues tokens for the minimal scopes. The authors call for Task-Based Access Control (TBAC).
- **Numbers** ([HTML](https://arxiv.org/html/2510.26702v1)). GPT-4o reasoning matcher: F1 0.96 for single-tool tasks, 0.85 for two tools, 0.67 for three, with recall falling from 0.99 to 0.57. An embedding matcher reached F1 0.71 (precision 0.99, recall 0.55).
- **Transfer: High as a warning.** Pure semantic intent-to-scope matching under-scopes (breaking utility) as tasks get richer. It needs a deterministic floor and a human or step-up path.

**Task-conditioned least-privilege learning** ([arXiv 2608.18351](https://arxiv.org/abs/2608.18351), Aug 2026)
- **Idea.** Post-train a 4B model (Qwen3.5-4B) to choose task-conditioned authority in terminal and MCP settings.
- **Numbers.** Safe success 64.4% → 98.5%; excess-authority events 4.56% → 0.79%.
- **Authors' conclusion.** It complements permission gates but does not replace them.
- **Transfer: Low** (agent-side), but it supports "gates first, model second".

### 4.3 LLM task-alignment monitors (judges)

**Task Shield** ([arXiv 2412.16682](https://arxiv.org/abs/2412.16682), Penn State, Dec 2024)
- **Idea.** Extract the user's task instructions. For every instruction and tool call, an LLM scores (fuzzy, 0–1) whether it *contributes to* a user goal. The same model family is the checker.
- **Numbers** ([HTML](https://arxiv.org/html/2412.16682v1)). GPT-4o on AgentDojo: ASR 2.07% and UA 69.79%, vs 47.69% and 50.08% undefended. Tool filter gives 6.84% / 56.28%; PI detector 7.95% / 21.14%. GPT-4o-mini: 2.23% / 54.53%.
- **Cost.** Not reported; the authors cite high LLM cost. They list adaptive attacks as a limitation.
- **Transfer: Medium, A2A only.**
  - The "contributes to the root task" test is exactly what an A2A hop could ask of `argumentsFlat`.
  - Task Shield assumes user messages are trusted. At WAAG hop ≥2 the text is written by an agent and is therefore *untrusted*.
  - The root task is not available unless captured at ingress.

**LlamaFirewall AlignmentCheck** ([arXiv 2505.03574](https://arxiv.org/abs/2505.03574), Meta, May 2025)
- **Idea.** A few-shot chain-of-thought auditor sees the user goal, the execution trace *including reasoning*, and the latest action, and it flags goal hijacking.
- **Numbers** ([HTML](https://arxiv.org/html/2505.03574v1)):
  - AgentDojo: ASR 17.63% → 2.89% with Llama 4 Maverick; utility 47.7% → 43.1%;
  - with PromptGuard 2: 1.75% ASR / 42.7% utility;
  - internal benchmark: >80% recall at <4% FPR.
- **Model size.** Needs large models; smaller ones lack the reasoning. The authors call it experimental and note guardrail injection risk.
- **PromptGuard 2 latency.** 92.4 ms (86M) / 19.3 ms (22M) on an A100 for 512 tokens.
- **Transfer: Low–Medium.** The gateway has no reasoning trace, which is AlignmentCheck's main signal. PromptGuard-style classifiers *can* run on A2A text but are the class broken by adaptive attacks (see §6).

**MELON** ([arXiv 2502.05174](https://arxiv.org/abs/2502.05174), ICML 2025)
- **Idea.** Re-run the agent with the user task masked. If it still proposes similar tool calls (embedding similarity > 0.8), those calls come from injected content.
- **Numbers.** GPT-4o: ASR 0.24%, UA 58.78% (MELON-Aug: 0.32% / 68.72%).
- **Cost.** About 2× model calls ([HTML](https://arxiv.org/html/2502.05174v4)).
- **Adaptive result.** Adaptive ASR **76–95%** ([Attacker Moves Second, 2510.09023](https://arxiv.org/html/2510.09023v1)).
- **Transfer: Low.** It needs to re-run the agent's LLM, and it is broken adaptively.

**RETA** ([arXiv 2606.15441](https://arxiv.org/abs/2606.15441), June 2026)
- **Idea.** An RL-trained defender reasons at each tool output about consistency with the user task, trained against a diversity-rewarded simulated attacker.
- **Numbers.** Per-attack ASR stays below 10% across six black-box adaptive attacks (average 2.92% / 3.75%).
- **Transfer: Low** (model-side), but it shows that training on attack *diversity* is what makes task-alignment judges hold up.

**GuardAgent** ([arXiv 2406.09187](https://arxiv.org/abs/2406.09187), ICML 2025)
- **Idea.** An LLM turns natural-language guard requests into a plan and then **executable guard code**, with memory of past cases.
- **Numbers.** About 98% accuracy on healthcare access control (EICU-AC) and about 83% on web safety (Mind2Web-SC).
- **Transfer: Medium.** This is structurally the same as WAAG's policy assistant (NL → policy). The research lesson is to *execute* the generated guard deterministically and to test it.

**ShieldAgent** ([arXiv 2503.22738](https://arxiv.org/abs/2503.22738), 2025)
- **Idea.** Extract verifiable rules from policy documents into probabilistic "rule circuits"; verify trajectories with tools and formal checks.
- **Numbers.** About 90% accuracy, 4.8% FPR; 91.1% on ST-WebAgentBench vs GuardAgent's 84.0%.
- **Cost.** 31–34 s and about 10 queries per sample ([HTML](https://arxiv.org/html/2503.22738v2)).
- **Transfer: Low inline, Medium offline.** Useful for turning regulations into policies at authoring time, not at request time.

### 4.4 Deterministic runtime rule languages and trace analysis

**AgentSpec** ([arXiv 2503.18666](https://arxiv.org/abs/2503.18666), ICSE 2026)
- **Idea.** A DSL of rules with a *trigger* (before_action / state_change / agent_finish), *predicates*, and *enforcement* (user_inspection / llm_self_examine / invoke_action / stop), hooked into LangChain.
- **Numbers** ([HTML](https://arxiv.org/html/2503.18666v3)):
  - >90% of unsafe code executions prevented;
  - 100% hazardous embodied actions eliminated;
  - parse about 1.4 ms and predicate evaluation 1.1–2.8 ms;
  - o1-generated rules: 95.56% precision and 70.96% recall (embodied), 87.26% of risky code caught.
- **Transfer: High (shape).** The enforcement vocabulary (inspect / stop / invoke) is the **obligation set** WAAG's PDP lacks. The measured recall of LLM-generated rules argues for human review before enabling. That matters for WAAG, where `/chat/save` enables LLM policies immediately (grounding §6.12).

**AgentArmor** ([arXiv 2508.01249](https://arxiv.org/abs/2508.01249), 2025)
- **Idea.** Rebuild the runtime trace into CFG/DFG/PDG graphs, attach a registry of tool and data properties, and type-check for policy violations.
- **Numbers.** AgentDojo ASR down to 3% with 1% utility drop.
- **Transfer: Medium.** A gateway sees the inter-hop graph (act_chain + trace), so a coarse "program dependency graph of hops" is feasible. Intra-agent data flow is not visible.

**RTBAS** ([arXiv 2502.08966](https://arxiv.org/abs/2502.08966), 2025)
- **Idea.** IFC for tool-based agents, with dependency screeners (LM-as-judge or attention saliency) deciding which tool calls preserve integrity and confidentiality. The user is asked only when needed.
- **Numbers.** Prevents all targeted AgentDojo attacks with about 2% utility loss under attack.
- **Transfer: Low–Medium.** The pattern "ask the user only when the safeguard can't be ensured" is the product pattern WAAG wants for step-up.

**Temporal expressions for agent correctness** ([arXiv 2509.20364](https://arxiv.org/abs/2509.20364), Aug 2025)
- **Idea.** Temporal-logic assertions over sequences of tool calls and agent handoffs. They flagged regressions when smaller models were swapped in.
- **Transfer: High.** This is sequence policy over exactly what WAAG observes. Related industry work: AWS Dogwood adds temporal policies to Cedar (per source 04; covered by another research area).

**HCP: execution-control invariants for MCP-style runtimes** ([arXiv 2606.29073](https://arxiv.org/abs/2606.29073), June 2026)
- **Eight invariants.** Metadata non-authority, grant-backed approval, canonical resources, principal binding, scoped capability invocation, source-and-target data-flow authorization, deny-path audit, explicit protocol state.
- **Numbers.** Naive MCP runtime permits 10/10 modeled attacks; a linting/approval baseline permits 6/10; HCP blocks 10/10 with sub-ms operations.
- **Transfer: High (checklist).** *Metadata non-authority* (tool descriptions must never grant authority) matters for WAAG if descriptions are ever fed to a judge. Tool description poisoning reaches about 100% ASR on GPT-4o in [MCP-TDP](https://arxiv.org/abs/2605.24069).

### 4.5 Delegation, identity and intent tokens

**Agentic JWT (A-JWT)** ([arXiv 2509.13597](https://arxiv.org/abs/2509.13597), Goswami, Sep 2025)
- **Idea.** A dual intent token. Its claims ([HTML](https://arxiv.org/html/2509.13597v1)):
  - `workflow_id`, `workflow_step`, `initiated_by`, `executed_by`;
  - `delegation_chain`, `step_sequence_hash`, `execution_context`;
  - `agent_checksum` (hash of prompt, tools and config);
  - a PoP key in `cnf`.
- **Intent form.** Intent is a **registered workflow step**, not free text.
- **Verification.** The IdP checks the agent checksum and whether the workflow step is authorized. The resource server checks PoP and workflow/step/chain.
- **Claims.** 12 threat classes blocked, "100%" in its own framework, sub-ms overhead. Full evaluation is deferred to a journal version.
- **Author.** Single author, no institutional affiliation listed.
- **Transfer: High (design), low evidence.** The **workflow_id / step** idea gives WAAG a deterministic intent anchor with no NLP: registered workflows ("financial analysis for symbol X") that a trace declares at ingress. The `agent_checksum` idea maps to WAAG's agent registry.

**SAGA** ([arXiv 2504.21034](https://arxiv.org/abs/2504.21034), Northeastern, 2025)
- **Idea.** A central Provider registers agents and user-defined contact policies (pattern + budget). One-time keys derive access-control tokens with expiry and quota for agent-to-agent channels. ProVerif-verified.
- **Scope.** It does **not** inspect message content or intent ([HTML](https://arxiv.org/html/2504.21034v2)).
- **Overhead.** Amortized <25 ms per request at quota ≥100.
- **Transfer: Medium.** WAAG already has registry + per-hop tokens. SAGA adds **per-pair contact quotas**, a cheap behavioral bound.

**HDP (Human Delegation Provenance)** ([arXiv 2604.04522](https://arxiv.org/abs/2604.04522), Apr 2026; [IETF draft-helixar-hdp-agentic-delegation-00](https://datatracker.ietf.org/doc/draft-helixar-hdp-agentic-delegation/)) and **IPP (Intent Provenance Protocol)** ([draft-haberkamp-ipp-00](https://www.ietf.org/archive/id/draft-haberkamp-ipp-00.html), March 2026, Informational, KH Sovereign)
- **HDP.** An append-only, Ed25519-signed hop chain binding a human authorization event to a session, verifiable offline.
- **IPP.** Defines an "Intent Token" that travels with every agentic action.
- **Transfer: Medium.** WAAG's `act_chain` already provides human-rooted lineage. These drafts show a standards trend toward carrying **intent/scope alongside lineage**, which WAAG's OBO does not do today (no purpose claim; grounding §13(g)).
- **Status.** Both are individual drafts, not working-group documents.

**Firewalls to Secure Dynamic LLM Agentic Networks** ([arXiv 2502.01822](https://arxiv.org/abs/2502.01822), Abdelnabi, Gomaa, Bagdasarian, Kristensson, Shokri; TMLR 2026, v7 June 2026)
- **Idea.** Each task defines a context. A *Language Converter Firewall* projects incoming agent messages onto a closed, domain-specific structured protocol with deterministic validation, so persuasive framing and embedded instructions have no channel. A *Data Abstraction Firewall* projects outgoing data to task-appropriate granularity. Rules are learned from demonstrations in a trusted environment.
- **Numbers.** Over 864 attacks (ConVerse): privacy ASR 84% → 10% (GPT-5); security ASR 60% → 3%; task quality maintained or improved.
- **Transfer: High for A2A.** WAAG terminates and re-originates A2A. It could project `message/send` text onto the target skill's typed input (from the skill card or inputSchema), so free-form instructions never reach the downstream agent. This is architecture, not a detector.

### 4.6 Behavioral anomaly detection

**Praetor: "Enforcing Benign Trajectories: A Behavioral Firewall for Structured-Workflow AI Agents"** ([arXiv 2604.26274](https://arxiv.org/abs/2604.26274), Dang, Apr 2026)
- **Idea.** Compile verified benign tool-call telemetry into a parameterized DFA. A state is a tool plus the preceding 3 calls. Parameters are bounded as follows:
  - numeric: widened min/max;
  - string: embedding centroid + radius (MiniLM);
  - categorical and sensitive: exact-match whitelist.
- **Runtime.** A transparent sidecar does O(1) lookups and needs no agent internals ([HTML](https://arxiv.org/html/2604.26274v1)).
- **Numbers.**
  - ASB macro ASR 5.6%; 2.2% in structured workflows, but 12.6% for an open-ended 64-tool research agent;
  - multi-step exfiltration 0% vs 35% for a stateless scanner (Aegis);
  - benign task failure 2.0% (0.2% with 5,000 training traces);
  - **2.2 ms median, 3.1 ms p95.**
- **Weaknesses.**
  - Synonym substitution evades string bounds 18% of the time.
  - Replacing 20% of tools drives benign failure to 24% until humans update the model (about 8.75 analyst-hours).
  - Telemetry poisoning during profiling is possible.
- **Transfer: High.** WAAG already writes per-trace audit rows with tool/skill names and arguments. It needs a decision-time read of "previous N capabilities in this trace" plus an offline compiler.

**Skynet: Workflow-Level Anomaly Detection** ([arXiv 2609.06835](https://arxiv.org/abs/2609.06835), Sep 2026)
- **Idea.** Build directed workflow graphs (delegation, tool invocation, data flow). A dual-branch GNN, trained only on benign workflows, scores distance to benign anchors. It does not need model internals ([HTML](https://arxiv.org/html/2609.06835v2)).
- **Numbers.** Combined recall 89.36% at 0.89% FPR. Recall is only 74% on indirect prompt injection, which stays near the benign manifold.
- **Latency.** Per step about 5–105 ms mean (p95 up to 0.57 s).
- **Transfer: High (near-line).** WAAG's trace-chain view is already such a graph. Use it asynchronously to raise risk or trigger revocation on the next hop, not as the only inline gate.

**SentinelAgent** ([arXiv 2505.24201](https://arxiv.org/abs/2505.24201), May 2025)
- **Idea.** Model multi-agent interactions as execution graphs, check anomalies at node/edge/path level, and add an LLM oversight agent. Validated by case studies only.
- **Transfer: Medium.** Conceptual support for chain-level scoring (compare Reva's "a hop cannot outscore its compromised ancestors", source 01).

**Authorization propagation** ([arXiv 2605.05440](https://arxiv.org/abs/2605.05440), May 2026)
- **Framing.** Multi-agent authorization is a workflow-level property with three sub-problems (transitive delegation, aggregation inference, temporal validity) and seven structural requirements. The field is converging but has no complete architecture.
- **Transfer: framing.** "Aggregation inference" (harmless reads that combine into a sensitive result) is not covered by per-hop checks and needs trace-level state.

### 4.7 Uses of the term "intent-based access control"

| Where | What it means there | Note |
|---|---|---|
| [DePLOI / IBAC-DB, arXiv 2402.07332](https://arxiv.org/abs/2402.07332) (2024) | Organizational *policy intents* compiled into database grants by an LLM, with a benchmark (IBACBench) | Different meaning: admin intent → policy, not agent task intent. Relevant to WAAG's policy assistant |
| [IGAC, arXiv 2606.22916](https://arxiv.org/abs/2606.22916) (2026) | Request → intent certificate → narrowed manifest + effect checks | The academic reference implementation of the agent sense |
| [ASTRA, arXiv 2510.26702](https://arxiv.org/abs/2510.26702) | "Intent-aware authorization" / TBAC via semantic scope matching | Cisco |
| [Intent-based management authorization, arXiv 2510.19324](https://arxiv.org/abs/2510.19324) | 6G network intent-based management | Unrelated sense ("network intents") |
| [CSA blog, 2026-07-14](https://cloudsecurityalliance.org/blog/2026/07/14/when-who-are-you-is-no-longer-enough-the-case-for-intent-based-access-control-in-the-age-of-ai-agents); [PlainID](https://www.plainid.com/intent-based-access-control-for-ai-agents/); [C1 press release](https://www.c1.ai/news/press-release/c1-launches-agent-runtime-governance); Reva IBAC whitepaper (source 01) | Industry framing of IBAC for agents | Marketing / position pieces, not evaluated research (covered by other areas) |
| [Toward a Science of Intent, arXiv 2604.25000](https://arxiv.org/abs/2604.25000) | "Intent compilation" into inspectable artifacts; "delegation envelopes" as pre-authorized regions of action space | Conceptual; no evaluation in abstract |

---

## 5. Benchmarks: what they measure and how usable they are for WAAG

| Benchmark | What it is | Headline facts | Usable to evaluate a gateway? |
|---|---|---|---|
| **AgentDojo** ([2406.13352](https://arxiv.org/abs/2406.13352), ETH; [code](https://github.com/ethz-spylab/agentdojo)) | Extensible environment: workspace, banking, travel and Slack suites; the de facto IPI benchmark | 97 tasks, 629 security test cases; supports adaptive attacks | **Yes**, if its tool suites are wrapped as MCP servers behind WAAG. Most defenses above report on it, which gives a common yardstick |
| **InjecAgent** ([2403.02691](https://arxiv.org/abs/2403.02691)) | Single-turn IPI test cases | 1,054 cases, 17 user tools, 62 attacker tools; ReAct GPT-4 attacked 24% of the time, about double with a "hacking prompt" | Partly (single-step; weak for multi-hop) |
| **ASB** ([2410.02644](https://arxiv.org/abs/2410.02644)) | 10 scenarios, 10 agents, 400+ tools, 27 attack/defense methods, 13 LLM backbones; includes memory poisoning and PoT backdoor | Highest average ASR 84.30%; defenses weak | **Yes**; Progent and Praetor both report on it |
| **WASP** ([2504.18575](https://arxiv.org/abs/2504.18575)) | End-to-end web-agent prompt-injection benchmark | Attacks partially succeed in up to 86% of cases, but agents often fail to complete the attacker goal ("security by incompetence") | Low (browser agents, not MCP/A2A) |
| **τ-bench** ([2406.12045](https://arxiv.org/abs/2406.12045)) | Tool-agent-user conversations with domain policies; end-state DB comparison; pass^k reliability | GPT-4o <50% success; pass^8 <25% in retail | **Utility / policy-following yardstick**: shows how often agents break domain rules *without* attack, i.e. the "mistaken but authorized" case the LinkedIn comment raised (source 02) |
| **MSB** ([2510.15994](https://arxiv.org/abs/2510.15994)) | MCP-specific: 12 attacks incl. name collision, description injection, out-of-scope parameters, user-impersonating responses; real MCP tools | 2,000 attack instances, 405 tools; stronger models more vulnerable | **Yes, the most MCP-native**; complements AgentDojo |
| **MCP-TDP** ([2605.24069](https://arxiv.org/abs/2605.24069)) | Tool-description poisoning | GPT-4o near 100% ASR in six high-risk scenarios; prompt guardrails ineffective or counter-productive | Yes, for registry/metadata controls |
| **TAB** ([2605.12233](https://arxiv.org/abs/2605.12233), May 2026) | Terminal tasks with a needed cue + a plausible distractor | Six IPI defenses suppress needed cues along with distractors | A caution that blanket blocking hurts utility |

---

## 6. Robustness reality check: adaptive attacks

- **Content-level defenses fail under adaptive attack.**
  - [Zhan et al. 2503.00061](https://arxiv.org/abs/2503.00061) bypassed all eight tested defenses on InjecAgent with >50% ASR. The set included detectors, perplexity filters, prompt-level and fine-tuning defenses.
  - [The Attacker Moves Second, 2510.09023](https://arxiv.org/abs/2510.09023) (Oct 2025) bypassed 12 defenses, most at >90% ASR: spotlighting, sandwiching, RPO, Circuit Breakers, StruQ, MetaSecAlign, ProtectAI, PromptGuard, PIGuard, Model Armor, Data Sentinel and MELON (76–95%). Most had originally reported near-zero ASR.
  - Methods: gradient, RL, search and human red-teaming.
- **Deterministic out-of-band defenses look structurally stronger but are under-tested.**
  - [2606.26479](https://arxiv.org/abs/2606.26479) (June 2026) is the first independent adaptive probe of this class. Progent held (25.8% → 4.2% → 2.6% adaptive) on a 7B open model with one black-box template.
  - Open attack surfaces it names:
    - (1) poisoning the LLM **policy generator**;
    - (2) tasks where untrusted data *must* drive an authorized action (approval fatigue);
    - (3) implicit flows and side channels;
    - (4) wrong provenance assumptions (for example, the user pastes untrusted text);
    - (5) text-to-text harms;
    - (6) attacks that stay **inside the authorized envelope**.
- **Implications for WAAG.**
  - Any intent classifier or LLM judge on the request path must be assumed bypassable by a motivated attacker. It may **raise** friction (step-up or deny) but should never be the only thing that **grants** authority.
  - The deterministic envelope is the security boundary. The model is a precision and UX tool.
  - Attack surface (6) is why behavioral and trajectory bounds (§4.6) and value/rate limits still matter inside a correct envelope.

---

## 7. The ideas with the highest transfer value for WAAG

The selection criteria:
- works with what an inline gateway sees;
- deterministic at the enforcement point;
- fits a budget of 12 ms overhead against seconds of downstream time;
- evidence of robustness;
- directly answers buyer questions (Netskope Q10/Q11/Q15, Zscaler "readiness for intent-aware authZ"; Product Brief §9.2).

### Idea 1: A task-scoped, narrow-only authority envelope bound into the per-hop token

*Sources: Progent, Conseca, IGAC, IntentCap, A-JWT, MiniScope.*

- **What.** At the trusted ingress (hop 1), derive an **envelope**, a structured object with:
  - allowed capability ids or classes;
  - argument constraints (sets, ranges, patterns), e.g. `symbol ∈ {AAPL}`, `amount ≤ 250`, recipients ⊆ the user's stated recipients;
  - effect class (read / write / egress);
  - budget (calls, value);
  - expiry;
  - optionally a registered `workflow_id` (A-JWT style) instead of free text.
- **Where it lives.** It rides in the OBO (for example an `intent_env` hash plus a server-side record keyed by `trace_id`). Each child hop's envelope must be a **subset** of its parent's, checked deterministically. This is Progent's narrowing rule, and it is also WAAG's unimplemented P8 "monotonic down-scoping" (the parent `scope`/`corr_id` are carried today but unread).
- **Who writes it.** Three tiers, following the Jev CEO's "smallest primitive" framing (source 03):
  - (a) static templates per workflow;
  - (b) a small model or rule extractor at the front door;
  - (c) an LLM proposer. The proposer only *proposes* from trusted input, as in Conseca and IGAC. Expansions need approval (Progent: 6% of updates).
- **Why high value.**
  - It is the only intent mechanism with strong, deterministic enforcement and a monotonic safety property that survives LLM error ("cannot exceed static policy": IGAC; "only narrows": IntentCap).
  - It answers "scope to the task, not the user's full rights" (Netskope Q10).
- **Gaps it exposes in WAAG.**
  - No front-door intent capture: the console paraphrases the human.
  - No purpose claim in the OBO.
  - The PDP lacks set containment over structured arguments, OR/NOT, and obligations.
- **Evidence limits.** Policy-generation poisoning is the known attack. ASTRA shows semantic scoping loses recall as tasks widen, so utility will suffer without a clarify/step-up path.

### Idea 2: Trace-level integrity taint plus the "Rule of Two" as deterministic policy attributes

*Sources: FIDES, CaMeL, f-secure, Design Patterns, Meta Rule of Two.*

- **What.** Label every registered capability once (admin- or LLM-suggested, human-approved) on three axes:
  - **ingests untrusted content** (web, email, news, third-party agent replies);
  - **touches sensitive data**;
  - **state-changing / external egress**.
- **Taint rule.** Keep a per-trace join, as in FIDES: once any hop in the trace has returned untrusted content to an agent, the trace is "low integrity".
- **Policy.** A low-integrity trace calling a state-changing or egress capability with sensitive data in scope gets DENY or STEP_UP. The rule is a few deterministic attributes: `context.traceTainted`, `resource.effectClass`, `resource.sensitivity`.
- **Why high value.**
  - It is the closest a gateway can get to CaMeL/FIDES guarantees without owning the planner.
  - It is cheap (one lookup per hop) and explainable to a CISO.
  - It is not an NLP judgment, so it is not subject to the adaptive-attack collapse.
- **Fit with existing plans.** It fits the existing deferred plan for "provenance/taint (advisory-first)" in the post-processor (team memory #54), but moves it to the **request side**.
- **Limits.** Coarse. An agent that read one web page taints the whole trace, so utility falls unless combined with Idea 1's envelope (tainted but in-envelope reads are allowed). Implicit flows are not covered.

### Idea 3: A learned per-workflow behavior automaton from WAAG's own ledger

*Sources: Praetor, Skynet, temporal assertions, SAGA quotas.*

- **What.** Offline, compile benign traces from `gateway_audit_log` (tool/skill sequence per agent or workflow, argument categories, fan-out, depth, per-pair contact counts) into a pDFA with exact-match whitelists for sensitive parameters. Inline, do an O(1) check of "is this capability a valid next state given the last N in this trace?"
- **Near-line.** Run a Skynet-style graph score after each hop; a high score raises the risk attribute for the *next* hop or revokes the trace.
- **Why high value.**
  - No LLM; measured at about 2.2 ms per call, inside WAAG's budget.
  - It needs only data WAAG already writes.
  - It covers the "attacks inside the authorized envelope" gap and multi-step exfiltration (0% vs 35% stateless).
  - It delivers the "behavior" half of the IBAC pitch with evidence rather than a vendor claim (Reva's "98% drift accuracy" is unverified; source 01).
- **Gaps it exposes.**
  - No per-trace history at decision time: the ledger is written async and nothing reads it inline (grounding §13(f)).
  - OBO-only traffic, so the corpus is thin.
  - Open-ended agents (Praetor 12.6% ASR on the 64-tool agent) and tool churn need analyst maintenance.
  - Profiling must use curated traffic to avoid telemetry poisoning.

### Idea 4: A typed intent extractor at the trusted ingress, and an advisory alignment check on A2A, both feeding step-up and never granting

*Sources: ASTRA, IGAC certificate producers, Task Shield, Firewalls/Language Converter, the Jev CEO's typed decision model (source 03).*

- **At ingress.** Where WAAG can see the human's words (it cannot on the console path today, so this needs a console or front-door change), a small model maps text to **typed intent + confidence**. The output fills Idea 1's envelope; low confidence → clarify or approval.
- **At A2A hops ≥2.**
  - **Structural control.** Project the delegating agent's free text onto the target skill's typed inputs, following the Language Converter pattern, so embedded instructions have no channel. This is deterministic.
  - **Advisory scoring.** Optionally score "does this message contribute to the root envelope?" (Task Shield style). A low score yields STEP_UP or a trace flag, never ALLOW.
- **Why this ranking and these limits.**
  - Every LLM-judge defense is adaptively breakable (§6).
  - AlignmentCheck needs large models and the agent's reasoning, which WAAG lacks.
  - ASTRA recall falls to 0.57 at three scopes.
  - The value is precision and UX (fewer blanket denials), not the security boundary.
- **Transferable pattern.** IGAC treats the classifier as an untrusted principal whose output the policy layer re-checks.
- **Latency.** A judge on every A2A hop adds hundreds of ms and holds a blocking Tomcat worker (grounding §13(d)). Scope it to A2A and to consequential capabilities.

### What does *not* transfer (and why)

| Idea | Why not at the gateway | What to borrow anyway |
|---|---|---|
| CaMeL / FIDES / IPIGuard / DRIFT planners | Need control of the agent's planner and context | Invariants: plan-bounded writes, taint joins, read/write asymmetry |
| MELON | Needs to re-run the agent's LLM; adaptively broken | Nothing inline |
| AlignmentCheck | Needs chain of thought; big models | Offline evaluation of traces |
| ShieldAgent | 30+ s per check | Offline: regulation → candidate policies |
| Model-level defenses (StruQ, SecAlign, RETA) | Inside the model | Customer guidance: pair WAAG with hardened agent models |

---

## 8. What these ideas require from WAAG (derived from §7 + grounding)

| Requirement | Why the research needs it | WAAG today (grounding) |
|---|---|---|
| **Obligations / tri-state decisions** (ALLOW / DENY / STEP_UP / CLARIFY) | Progent approval of expansions, IGAC review modes, AgentSpec user_inspection, Rule of Two supervision | ALLOW/DENY only (§6.3) |
| **Structured argument predicates** (set membership, numeric bounds, per-field) | Progent/Conseca argument rules; Praetor sensitive-parameter whitelists | Only the flat `argumentsFlat` string; no structured access (§13(b)) |
| **OR / NOT, and no silent fragment drop** | Any non-trivial envelope | No OR/NOT; dropped fragments widen permits (§6.2) |
| **Per-trace history attributes at decision time** (previous capabilities, taint bit, counts, value sums) | Praetor, Idea 2 taint, Dogwood-style temporal checks, SAGA quotas | PDP consults no history; parent `corr_id` unread; ledger async (§13(f)) |
| **Intent/envelope claim in the OBO + child ⊆ parent check** | Progent monotonicity, IntentCap delegation, A-JWT, HDP/IPP trend | No purpose claim; parent `scope` never read (§13(g); Brief P8) |
| **Front-door intent capture** | All "trusted-context-only" generators assume access to the real user request | Human words never reach the gateway on the console path (§12.4) |
| **Capability labels** (untrusted-ingest, sensitivity, effect class) | FIDES/CaMeL/Rule of Two | Not stored; MCP annotations not even stored (§13(g)) |
| **Human review before enabling generated policy** | AgentSpec o1 rules had about 71% recall; policy-generator poisoning | `/chat/save` enables LLM policies immediately (§6.12) |

---

## 9. A research view of the CEO's "light local LLM + memory" idea (source 03)

- **Where the literature agrees with it.**
  - Local, cheap models *can* do typed extraction (ASTRA's matcher; IGAC's classifier producers).
  - Keeping data in the customer environment is reasonable.
- **Where the literature warns.**
  - (a) Judging alignment needs large models and the agent's context (AlignmentCheck); small models "lack the reasoning" per Meta.
  - (b) Any model on the path is adaptively attackable (§6), and the attacker writes the A2A text the model reads.
  - (c) Intent-to-scope precision is the measured bottleneck (IGAC, ASTRA), so the model must not be the authority. The Jev CEO makes the same point: the model says what is asked, the policy engine decides.
  - (d) "Memory" in the research sense is best a **behavioral baseline** (Praetor/Skynet), not LLM conversational memory. LLM memory is itself an attack surface: ASB includes memory poisoning, DRIFT adds an isolator for it, and Praetor warns of telemetry poisoning.
- **Recommended reading.**
  - Deterministic envelope and taint first (Ideas 1–2).
  - Behavior automaton second (Idea 3).
  - A small model only for typed ingress extraction and advisory A2A scoring (Idea 4).

---

## 10. Suggested evaluation protocol (so claims to buyers are credible)

1. Wrap AgentDojo suites and MSB tools as MCP servers behind WAAG. Drive them with the sample agents (single and multi-hop).
2. Report **ASR, benign utility, utility under attack, added p50/p95 latency and thread occupancy** per idea, following the reporting conventions of Progent, DRIFT and IPIGuard.
3. Include **adaptive attacks** aimed at each component: policy-generator poisoning, in-envelope attacks, synonym substitution against string bounds, A2A text crafted against the judge. Follow the [2510.09023](https://arxiv.org/abs/2510.09023) and [2606.26479](https://arxiv.org/abs/2606.26479) methodology.
4. Use τ-bench-style end-state checks to measure the "mistaken but authorized" rate that permission checks cannot see (the LinkedIn comment, source 02).

---

## 11. Open questions

1. Can the console or front door send the human's original request (or a registered workflow id) to WAAG at hop 1, and is it acceptable to store it? Every "trusted-context" design depends on this.
2. For hop ≥2 A2A text, does the product treat it as untrusted (low integrity) by default? The research implies it should.
3. Who labels capabilities (untrusted-ingest / sensitivity / effect class), and can MCP tool annotations be stored and used as *hints* only (HCP's "metadata non-authority")?
4. Is there enough benign OBO traffic per workflow to learn a behavior automaton (Praetor used 500–5,000 traces), and how is it kept current as tools change?
5. What is the acceptable added latency on A2A hops when threads block for the whole subtree? This decides whether any LLM judge is inline or near-line.
6. No independent reproduction exists for IGAC, IntentCap, Praetor or Skynet (all 2026). Their numbers are author-reported on largely synthetic workloads.
7. Has anyone published a strong white-box adaptive attack against Progent/CaMeL-class envelopes, and specifically against LLM policy generators? None was found as of 2026-09-26.
8. IPIGuard's exact averaged table values need confirming from the PDF (the WebFetch digests disagreed).
