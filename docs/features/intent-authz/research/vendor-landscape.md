# Vendor landscape: intent-aware and behavior-aware authorization for AI agents (as of 26 Sept 2026)

Research dossier for the WhiteSwan Agentic Auth Gateway (WAAG) intent-aware authorization effort. Written 2026-09-26.

## Executive summary (10 lines)

1. "Intent" means five different things in this market: (a) a **signed or declared task scope** (AP2 mandates, transaction-token `scope`, C1, Oasis, Keycard); (b) an **LLM judge of whether a tool call fits the conversation** (Microsoft Task Adherence, Google Semantic Governance, Meta AlignmentCheck, LangChain/SemIf, Lasso, Reva); (c) **malicious-content "intent" detectors** on prompts and responses (Zscaler AI Guard, Lakera, Model Armor, Prompt Shields); (d) **behavior and sequence rules or baselines** (AWS Dogwood temporal policies, Invariant flow rules, Entra ID Protection, Permit fingerprint drift, Zenity); (e) **identity and delegation only**, sold as "intent" (Okta Agent Gateway, Aembit, Auth0, Descope, Cerbos).
2. Only vendors that own the **agent runtime or a platform hook** get the human's words. Copilot Studio sends the user message, chat history and planner "thought" to an external webhook. Google's Agent Gateway judge reads the prompt and history. Anthropic Inference Hooks send the whole transcript. Pure network brokers (Zscaler, Netskope, Okta Agent Gateway) do not, and neither does WAAG today.
3. The closest like-for-like threat is **Google Agent Gateway** (GA), which combines SPIFFE agent identity, IAM allow-lists, Model Armor and LLM-evaluated **Semantic Governance Policies** (Preview; ALLOW/DENY with a stated reason). It is limited to Google's Agent Runtime and Gemini Enterprise.
4. The strongest *deterministic* behavior control is **AWS AgentCore Policy + Dogwood** (Cedar-compatible temporal rules such as `formerly within`, `count` and `sum`, enforced at AgentCore Gateway). Its limits: the caller supplies the session id, there are 20 temporal policies per engine, the window is 24 h, and it works only within one account and region.
5. Published latency budgets are tight. Copilot Studio waits under 1,000 ms and then **fails open**. Anthropic hooks allow 1–10,000 ms (default 5 s) with a choice of fail-open or fail-closed. Meta's PromptGuard 2 runs at 19–92 ms. LLM-judge vendors publish **no latency**, except vendor claims of under 40–50 ms (Reva, Lasso, Lakera).
6. Independent accuracy evidence is thin and sobering. AlignmentCheck cut the AgentDojo attack success rate to 2.89% but left utility at about 43%. Academic work shows alignment checks can be **bypassed** by control-flow hijacking (Jha et al.), and current benchmarks saturate (Bhagwatkar et al.). Figures like 98% or 99.83% are unverified marketing.
7. Outcomes are mostly **ALLOW/DENY**. Human approval as a first-class runtime result is rare: Dogwood approval events, Permit HITL, C1 "hold", Auth0 CIBA, LangChain `cancel_run`, and Reva "Defer". Redaction exists only in content products (Zscaler, C1, Model Armor, Netskope DLP).
8. No vendor verifiably does all of the following at once: **heterogeneous multi-vendor MCP *and* A2A multi-hop**, a **verified human-rooted delegation chain**, a **per-hop down-scoped token**, **chain-wide (trace-scoped) history rules**, and **graduated outcomes**. Hyperscalers do parts of this inside their own walls. IdPs do identity without intent. SSE vendors do content without delegation.
9. WAAG's defensible white space is to be the **neutral, chain-aware decision point**. It would carry a signed "intent envelope" (root task scope that is narrowed at each hop, AP2/transaction-token style), run Dogwood-style history rules keyed on the gateway-owned `trace_id`, and accept intent evidence from platform hooks (Copilot Studio, Claude Code, Anthropic). Any small model stays an advisory signal inside deterministic bounds.
10. Precondition: the market's credibility test is determinism. WAAG's PDP currently ignores some head forms (which widens grants) and has no OR/NOT and no obligations. Those must be fixed before intent is layered on, or intent becomes a marketing claim on a leaky base.

---

## 0. Method, labels and scope

- **Sources.** Primary vendor documentation, press releases, arXiv papers and IETF datatracker pages were fetched between 2026-09-26 and the date above. Five captured inputs sit in `../sources/` (Reva IBAC whitepaper, LangChain/SemIf LinkedIn post, WhiteSwan CEO/Jev chat, Reva on Dogwood, Reva on Anthropic Inference Hooks). WAAG facts come from `docs/others/gateway-grounding.md` §13 and `docs/others/Agentic-Gateway-Product-Brief.md`.
- **Labels used throughout:**
  - **[V]** verified from a primary document (vendor docs, spec, paper) at the cited URL.
  - **[C]** a vendor claim: marketing numbers, "first", "patent-pending", or availability statements not otherwise confirmed.
  - **[S]** a secondary source (press coverage, review site, analyst note). Treat it as indicative.
- **Limits.** GitHub API calls were rate-limited during this session, so star counts come from WebFetch page digests and are approximate. Several vendor pages returned truncated content (Netskope product page and blog). Where a fact could not be confirmed, it is listed under Open questions.
- **Quotes** are kept under 15 words. Everything else is paraphrased.

---

## 1. A working taxonomy

### 1.1 What vendors mean by "intent" or "behavior"

| Code | Meaning | Deterministic? | Typical examples |
|---|---|---|---|
| **I-1 Declared/signed task scope** | The human (or a trusted surface) states or signs what the task allows. Downstream checks confirm each action stays inside it. | Yes (after capture) | AP2 Intent/Cart (v0.1), Open/Closed mandates (v0.2); OAuth Txn-Token `scope`/`tctx`; C1 "governed scope"; Oasis intent→JIT creds; Keycard task-scoped tokens; CSA "declared intent" |
| **I-2 Inferred alignment (LLM/SLM judge)** | A model compares the proposed tool call with the user request or conversation and flags misalignment. | No (probabilistic) | MS Task Adherence; Google Semantic Governance; Meta AlignmentCheck; LangChain+SemIf "evaluate" tier; Lasso; Reva; Zenity; IndyKite "Intent Agent" |
| **I-3 Content "intent" detectors** | Classifiers judge whether *text* is malicious (injection, jailbreak, exfiltration, off-topic). This is not task alignment. | No | Zscaler AI Guard "intent-based detectors"; Check Point/Lakera Guard; Google Model Armor; MS Prompt Shields; PromptGuard 2; Prisma AIRS |
| **B-1 Sequence/temporal rules** | Declarative rules over the history of actions: order, counts, sums, required prior approval. | Yes | AWS Dogwood / AgentCore temporal policies; Invariant Guardrails `->` flows; Zenity Boundaries (taints) |
| **B-2 Behavioral baselines/anomaly** | Statistical or ML deviation from an entity's usual behavior. | No | Entra ID Protection for agents; Permit "agent fingerprint" drift; Astrix/Oso anomaly; Reva drift; CrowdStrike (analyst-assessed) |
| **ID-only** | Verified agent + user identity, delegation, scoped short-lived credentials. Sometimes marketed as intent. | Yes | Okta Agent Gateway/XAA; Auth0; Aembit; Descope; Stytch; Scalekit; Arcade; Cerbos; Permit ReBAC |

### 1.2 Where the signal is computed

| Placement | Sees human's words? | Sees tool call? | Who controls it | Examples |
|---|---|---|---|---|
| **In-agent / framework middleware** | Yes | Yes | App developer | LlamaFirewall, NeMo Guardrails, LangChain middleware + SemIf, Invariant (local), CaMeL |
| **Platform hook / webhook** (agent platform calls out to a security service) | Yes (platform forwards it) | Yes | Platform vendor defines schema; security vendor answers | Copilot Studio external threat detection; Anthropic Inference Hooks; Claude Code / Cursor hooks; Foundry guardrails |
| **Protocol gateway / broker** (inline on MCP/A2A/HTTP) | Only if the platform is the same vendor, or the caller forwards it | Yes | Security or identity vendor | Google Agent Gateway; AWS AgentCore Gateway; Okta Agent Gateway; Zscaler AI Broker; Netskope Agentic Broker; Prisma AIRS AI Gateway; Lasso; Permit; Aembit; C1; **WAAG** |
| **Central PDP / control plane** (called by any of the above) | Whatever the PEP sends | Whatever the PEP sends | Authorization vendor | Reva; PlainID; Cerbos; IndyKite AgentControl; Oso |
| **IdP token issuance** | No | No (only audience/scope) | IdP | Entra Conditional Access; Okta XAA / ID-JAG |

**Key structural finding.** Intent judgment (I-2) needs the user's request. Vendors that do I-2 at a *gateway* either own the agent runtime (Google), or rely on a *platform hook* that ships the conversation (Microsoft Copilot Studio → Defender or partner; Anthropic → customer server). A third-party network gateway that only sees protocol messages is in the same position as WAAG today. It sees structured MCP arguments and, on A2A, LLM-written message text, but not the human's original words (grounding §13(a), §13(g)).

---

## 2. Master comparison table

Legend for maturity: GA / Preview / Beta / EA (early access) / Announced / Research / OSS. Latency and accuracy show only published figures, with labels.

| Vendor / product | Intent/behavior meaning | Where computed | Rules vs model | Outcome | Deployment | Maturity (date) | Published latency / accuracy |
|---|---|---|---|---|---|---|---|
| **Microsoft Foundry – Task Adherence** | I-2: tool call vs user intent | Cloud API; Foundry guardrail at tool-call point | Model (undisclosed) | Boolean `taskRiskDetected` + reason; guardrail "annotate and block" | Azure cloud; data may route to US/EU | Preview (`2025-09-15-preview`) [V] | None published |
| **Microsoft Foundry – Prompt Shields / guardrails** | I-3 on user input, tool call, tool response, output | Cloud (Content Safety) | Classifiers | Annotate / annotate+block (agents: block only) | Foundry Agent Service only | Agent guardrails Preview [V] | None published |
| **Copilot Studio external threat detection** | Hook that lets a vendor judge each planned tool call | Partner endpoint called by orchestrator | Partner's choice | approve/block; **fail-open after 1,000 ms** | Webhook, Entra auth | Preview [V] | <1,000 ms budget [V] |
| **Defender real-time protection (Copilot Studio)** | I-3 (UPIA/XPIA) + "intent and destination" of action | Defender via the same webhook | Model | Block + alert | Microsoft cloud | Documented; blog Jan 2026 [V/S] | 1 s timeout then allow [S] |
| **Entra Agent ID + Conditional Access / ID Protection** | ID-only + B-2 (offline agent risk) | IdP at token issuance | Rules + offline ML detections | Block/allow token; risk-based CA | Entra; needs Agent 365 licence | CA for agents documented; risk detections all offline [V] | None |
| **Google Agent Gateway** | ID + I-3 + I-2 | Managed gateway (ingress/egress), MCP & A2A aware | IAM rules + Model Armor + LLM (Semantic Gov.) | ALLOW/DENY with reason | Google Cloud; Agent Runtime / Gemini Enterprise | Gateway GA; Semantic Governance **Preview** [V] | None published |
| **Google Model Armor** | I-3 on prompts/responses and MCP tool calls | Cloud API / floor settings / gateway | Classifiers | Block / de-identify | Google Cloud | GA product [V] | None found |
| **Google AP2** | I-1: signed mandates | Merchant / credential provider / network verify | Deterministic (VCs, signatures) | Transaction proceeds or not | Open protocol (Apache-2.0) | v0.1 (Sep 2025) → v0.2 [V] | n/a |
| **AWS AgentCore Policy + Dogwood** (added) | B-1 temporal, Cedar-compatible | AgentCore Gateway | Rules | permit/forbid; approval via prior event; LOG_ONLY mode | AWS, same account and region | Documented; Dogwood OSS Aug 2026 [V] | `TemporalLatency` metric exists; no figure [V] |
| **Anthropic Inference Hooks** (added) | Transcript-level policy hook (DLP, custom engines) | Anthropic servers call customer server | Customer's choice | allow/deny only | Claude Enterprise only | Beta, launched 5 Aug 2026 [V] | 1–10,000 ms, default 5 s; fail-open or fail-closed [V] |
| **Okta Agent Gateway / Agent SSO (XAA)** | ID-only (+ "intent" in positioning) | Identity-native MCP gateway; IdP | Rules | allow/deny; credential brokering; kill switch | Okta SaaS; MCP Bridge on-prem via services | Agent SSO GA 24 Aug 2026; Gateway planned GA Q3 2026 [V/C] | None |
| **Auth0 for AI Agents** | ID-only + async human approval | App SDK + Auth0 | Rules | Allow; CIBA async approval | SaaS | GA 19 Nov 2025; XAA EA 31 Aug 2026 [V/S] | None |
| **Zscaler AI Broker + AI Guard** | ID/allow-list (Broker) + I-3 "intent-based detectors" (Guard) | Zero Trust Exchange inline; detection API | SLM detectors + policy engine | allow, detect/log, block, redact, coach | Zscaler cloud | Announced 9 Jun 2026; GA not stated [V/C] | None published; "no performance tradeoffs" [C] |
| **Netskope One Agentic Broker / AI Guardrails** | Access control on MCP + DLP; I-3 guardrails | Netskope inline / client | Rules + classifiers | allow/block; DLP | Netskope SSE | GA 11 Mar 2026 [S] | None found |
| **Palo Alto Prisma AIRS (AI Gateway, Agent Runtime Security)** | I-3 + tool misuse; "permitted but dangerous" | AI Gateway; API intercept; Google gateway extension | Models | block / inspect | PANW cloud | AIRS 3.0 Mar 2026; AI Gateway GA 16 Jul 2026 [V] | None published |
| **CrowdStrike Falcon Guardian (AIDR) + Continuous Identity** | I-3 + causal chain; intent assessed by *analysts*; dynamic authz (SGNL) | Endpoint sensor, browser, future AI Gateway | Models + human analysts | block, redact, contain; grant/revoke | Falcon | Announced 1 Sep 2026; gateway "will" [V] | None |
| **Check Point AI Guardrails (Lakera Guard)** | I-3 incl. tool calls, responses, descriptions; "off-policy agent behavior" | SaaS or self-hosted API | Models | Flags; app decides | SaaS / self-hosted | Acquired Sep 2025 [S] | sub-50 ms, 98%+, 0.01% FPR [C] |
| **Meta LlamaFirewall (AlignmentCheck)** | I-2 on reasoning trace | In-agent library | Large LLM judge (Llama 4 Maverick / 3.3 70B) | Block/flag (library result) | OSS; judge via hosted API | "Experimental" in paper [V] | AgentDojo ASR 2.89%, utility 43.1% [V]; PromptGuard 2: 19.3/92.4 ms [V] |
| **NVIDIA NeMo Guardrails** | I-3 + dialog "canonical form" user intents + execution rails | In-agent / sidecar server | Embeddings + LLM + Nemotron NIMs | Refuse / modify | OSS (Apache-2.0) | v0.24.1 [S] | None in README |
| **Invariant (Snyk)** | B-1 trace/flow rules with ML detectors | Gateway proxy or local | Rules + detectors | Block / flag | OSS + hosted | Acquired by Snyk Jun 2025 [S] | None |
| **LangChain + SemIf** | I-2 on "evaluate" tier + request risk | In-agent middleware; SemIf via LangSmith gateway | Deterministic tiers + decision model | allow / block_tool / block_model / cancel_run | LangChain cloud (SemIf) | Demo + gateway feature, Sep 2026 [V] | None published |
| **Lasso** | I-2 + B-2 "intent-aware" | Proxy / API / AI gateway; OSS MCP gateway | Models | Block | SaaS / OSS gateway | Shipping [C] | <50 ms, 99.83% [C] |
| **Zenity** | I-2/B-1 "intent-aware" step-level; Boundaries (taints) | Platform hooks (Copilot Studio, Foundry, Copilot, Codex, OTel) | Rules (taints) + models | Inline prevention | SaaS | Runtime GA 17 Mar 2026 [S] | None |
| **Aembit** | ID-only (Blended Identity) | MCP Identity Gateway | Rules | allow/deny; credential injection | SaaS + edge | GA 2026 [S] | None |
| **Astrix** | ID + B-2; Agent Policies | ACP (location unclear) | Rules | allow / flag / block | SaaS | Announced 23 Mar 2026 [V] | None |
| **Oasis AAM** | I-1: request → structured intent → JIT creds | Policy engine + hooks (Cursor) | Undisclosed parser + deterministic policy | allow / warn / step-up / deny; ephemeral creds | SaaS | Shipping [C]; Zscaler integration Jun 2026 [S] | None |
| **Permit.io MCP Gateway** | ID/ReBAC + B-2 "agent fingerprint" drift | MCP gateway | Rules + interrogation fingerprint | allow/deny; downgrade trust; re-consent; HITL (Enterprise) | Hosted / VPC / on-prem | Shipping [V] | None |
| **Cerbos** | ID/ABAC for MCP & A2A | PDP (sidecar) | Rules | allow/deny | OSS + hub | Shipping [V] | sub-ms [S] |
| **Oso** | ID + anomaly for coding agents | Platform | Rules + detection | alerts / rules | SaaS | Jan 2026 [S] | None |
| **Descope / Stytch / Scalekit / Arcade / Keycard** | ID-only (XAA, OAuth for MCP, task-scoped tokens) | IdP / auth server / MCP runtime | Rules | allow/deny; step-up | SaaS | Shipping (various dates) [V/S] | None |
| **C1 (formerly ConductorOne)** | I-1 "governed scope" (explicitly *not* inferred) | Identity-aware AI gateway | Rules (lethal-trifecta conditions) | block / hold for approval / redact | SaaS | GA Jul 2026 [C] | None |
| **PlainID** | "Intent-based" PBAC at prompt, data, tool, response | PDP + authorizers (LangChain, MCP gateways) | Rules | allow/deny/mask | SaaS | Blog 26 Jun 2026 [V] | None |
| **IndyKite** | I-2 → I-1: "Intent Agent" converts NL to structured authz statements | AgentControl | Model + policy (unspecified) | Undisclosed | SaaS | Blog 18 Sep 2026 [V] | None |
| **Reva** | I-2 + B-2 + identity continuity ("Deterministic IBAC") | Central PDP behind gateways/hooks (Kong, LiteLLM, TrueFoundry, Copilot Studio, Claude Code) | SLM/LLM-as-judge inside Cedar/OPA bounds | Allow / Deny / Defer (HITL) | SaaS; customer SLMs | Shipping [C] | p90 <40 ms; 98% drift detection [C] |

---

## 3. Vendor profiles

### 3.1 Microsoft

**Entra Agent ID, Conditional Access and ID Protection for agents.**
- Conditional Access (CA) for agents is evaluated when Entra ID issues or refreshes a token. It can target agent identities, agent blueprints, or custom security attributes, and allow, block or require controls based on risk, network and device. For OBO flows the *user* is the policy subject. It needs Entra P1/P2 plus a Microsoft Agent 365 licence per user [V] ([MS Learn, CA for agents, updated 2026-07-01](https://learn.microsoft.com/en-us/entra/identity/conditional-access/agent-id)).
- There is no intent signal. CA does not apply when an agent uses an API key, because nothing passes through Entra [V] (same page).
- ID Protection for agents lists eight detection types, including unfamiliar resource access, sign-in spike, failed access attempt, reconnaissance and early-life malicious activity. **All agent risk detections are offline.** In OBO flows risk is attributed to the *user*, not the agent. High agent risk can drive a CA block [V] ([MS Learn, ID Protection for agents, 2026-06-17](https://learn.microsoft.com/en-us/entra/id-protection/concept-risky-agents)).
- Meaning: ID-only plus offline B-2. It is enforced at token issuance, not per call.

**Microsoft Foundry guardrails (Content Safety), incl. Prompt Shields and Task Adherence.**
- Foundry guardrails have four intervention points: user input, tool call (Preview), tool response (Preview) and output. Agent guardrails are in Preview. For agents the only action is "annotate and block". They apply only to agents built in Foundry Agent Service, not to other agents in the Foundry control plane [V] ([MS Learn, Guardrails overview, 2026-07-31](https://learn.microsoft.com/en-us/azure/foundry/guardrails/guardrails-overview)).
- **Task Adherence** is the most explicit "intent" product from a hyperscaler.
  - Input: the tool list (name and description) plus the message transcript, including tool calls and tool results.
  - Output: `taskRiskDetected` (boolean) and a text reason.
  - REST: `contentsafety/agent:analyzeTaskAdherence?api-version=2025-09-15-preview` [V] ([quickstart](https://learn.microsoft.com/en-us/azure/ai-services/content-safety/quickstart-task-adherence)).
  - Documented examples flag a read request that turns into a write, such as a data-usage question leading to `change_data_plan()`, or a draft that becomes `send_email()`.
  - Microsoft recommends blocking or human-in-the-loop (HITL) review on a positive result.
  - Limits: tested in English; data may be processed in US/EU regions outside the chosen region [V] ([concept page](https://learn.microsoft.com/en-us/azure/ai-services/content-safety/concepts/task-adherence)).
  - The model, latency and accuracy are **not published**.
- Meaning: I-2 (cloud model) plus I-3 (Prompt Shields).

**Copilot Studio external threat detection (the most important integration surface).**
- The orchestrator calls a security provider's `POST /analyze-tool-execution` before *every* tool invocation [V] ([MS Learn developer interface, updated 2026-01-23](https://learn.microsoft.com/en-us/microsoft-copilot-studio/external-security-webhooks-interface-developers)). The payload contains:
  - `plannerContext`: `userMessage`, the planner's `thought`, `chatHistory` and `previousToolOutputs`;
  - `toolDefinition`: name, description, input and output parameters;
  - `inputValues`;
  - `conversationMetadata`: agent, user, trigger, `conversationId`, `planId`, `planStepId`.
- The response is `blockAction` true/false with optional reason and diagnostics. **If no reply arrives within 1,000 ms, the agent treats it as allow** (fail-open) [V].
- Auth uses Entra ID app registration. The integration is in Preview ([enable page](https://learn.microsoft.com/en-us/microsoft-copilot-studio/external-security-provider)).
- Microsoft Defender uses this path to block tool calls when it detects direct or indirect prompt injection. Secondary coverage says Defender weighs "intent and destination" of each action [S] ([MS Learn Defender real-time protection](https://learn.microsoft.com/en-us/defender-xdr/security-for-ai/ai-agent-real-time-protection); [MS Security blog, 2026-01-23](https://www.microsoft.com/en-us/security/blog/2026/01/23/runtime-risk-realtime-defense-securing-ai-agents/)). Reva, CrowdStrike and Zenity also integrate here (Reva source 05; [CrowdStrike blog](https://www.crowdstrike.com/en-us/blog/falcon-aidr-protects-copilot-studio-agents-and-claude-code/)).
- **Why it matters to WAAG:** this is a public, documented schema that hands a third party the human's words *and* the planned tool call. Any vendor, WAAG included, could implement it (see §6).

**Entra Internet Access prompt injection protection.** Global Secure Access (the network "AI gateway") parses traffic to known GenAI endpoints and applies Prompt Shields inline [S] ([MS Learn how-to](https://learn.microsoft.com/en-us/entra/global-secure-access/how-to-ai-prompt-injection-protection)). Meaning: I-3 at the network layer.

### 3.2 Google

**Agent Gateway (Gemini Enterprise Agent Platform) — GA** [V] ([docs](https://docs.cloud.google.com/gemini-enterprise-agent-platform/govern/gateways/agent-gateway-overview)).
- A managed "entry and exit point" for agent traffic, in two modes: client-to-agent (ingress) and agent-to-anywhere (egress). It handles HTTP, including MCP and A2A, and parses MCP for policy.
- It enforces:
  - Agent Identity (SPIFFE IDs, mTLS, DPoP);
  - Agent Registry;
  - IAM Unified Access Policies (deny-by-default agent→tool bindings);
  - Model Armor;
  - **Semantic Governance Policies**;
  - custom engines via Service Extensions. Palo Alto uses this path to send every MCP tool call and response to Prisma AIRS [S] ([SiliconANGLE, 2026-09-24](https://siliconangle.com/2026/09/24/ai-agent-security-palo-alto-networks-googlecloudaiagentsinaction/)).
- Runtimes covered: Agent Runtime (ingress and egress) and Gemini Enterprise (egress only).

**Semantic Governance Policies — Preview** [V] ([overview](https://docs.cloud.google.com/gemini-enterprise-agent-platform/govern/policies/semantic-governance-overview); [best practices](https://docs.cloud.google.com/gemini-enterprise-agent-platform/govern/policies/best-practices), both updated 2026-09-25).
- Administrators write **Natural Language Constraints** at agent-wide or per-tool/parameter scope.
- An LLM evaluates each *proposed tool invocation* against: the current user prompt, chat history, the suggested tool calls, the tool manifest with descriptions, and the constraints.
- It returns ALLOW or DENY. A DENY carries a human-readable rationale back to the agent.
- A dry-run mode exists. The feature is not VPC-SC compatible. Tool descriptions are capped at 1,000 chars.
- Google itself warns that verdicts may be inaccurate, and that the judge acts only at tool invocation.
- The model, latency, fail-open/closed behavior and pricing are **not documented**. There is no approval outcome.
- Meaning: I-2 at a gateway. It works because Google owns the runtime that supplies the prompt and history.

**Model Armor** screens prompts and responses, and via floor settings MCP tool calls and responses, for injection, jailbreak, sensitive data (with de-identification) and harmful content [V] ([Model Armor MCP integration](https://docs.cloud.google.com/model-armor/model-armor-mcp-google-cloud-integration)). Meaning: I-3.

**AP2 (Agent Payments Protocol).**
- Announced 16–17 Sep 2025. v0.1 described three **Mandates**, each a verifiable credential signed by the user:
  - **Intent Mandate**: the rules of engagement, e.g. price limits and timing for human-not-present tasks;
  - **Cart Mandate**: exact items and price, signed on approval in human-present flows;
  - **Payment Mandate** [V] ([Google Cloud blog](https://cloud.google.com/blog/products/ai-machine-learning/announcing-agents-to-payments-ap2-protocol)).
- The current site shows **v0.2** with "Open" (user-approved constraints) and "Closed" (agent-signed, transaction-bound) Checkout and Payment Mandates, and mentions a FIDO Alliance donation. The merchant verifies checkout constraints; the credential provider and network verify payment constraints [V] ([ap2-protocol.org](https://ap2-protocol.org/); [spec](https://ap2-protocol.org/ap2/specification/)).
- The repo is Apache-2.0, about 3.2k stars, last push 2026-06-17 [V] (GitHub API, read-only).
- Meaning: the clearest **I-1** design in the market. It captures intent once, signs it, then verifies deterministically at each counterparty. It is limited to payments.

**CaMeL-derived products.** CaMeL (Google DeepMind, arXiv 2503.18813; SaTML 2026) separates control flow from data flow using a privileged and a quarantined LLM plus a capability-tracking interpreter. It solves 77% of AgentDojo tasks with provable security, against 84% undefended [V] ([arXiv](https://arxiv.org/abs/2503.18813); [code](https://github.com/google-research/camel-prompt-injection)). **No shipping Google or third-party product verifiably based on CaMeL was found.** A Feb 2026 review says real implementations remain limited [S] ([NeuralTrust](https://neuraltrust.ai/blog/camel-prompt-injection)). Research continues, e.g. CaMeLoT, arXiv 2609.18674.

### 3.3 AWS (added: the most relevant *behavior* precedent)

**AgentCore Policy temporal policies in Dogwood** [V] ([AWS docs](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/policy-temporal.html)).
- Dogwood is open source and a superset of Cedar: every Cedar policy is valid Dogwood.
- It adds `formerly within`, `since within`, `count` and `sum` over prior session events. Events match on action, principal, and input/output fields, and can be correlated with the current request, e.g. "a sale only if a matching approval returned `approved:true` within 1 h".
- Enforcement is at the **AgentCore Gateway perimeter**, outside the agent's code. There is a `LOG_ONLY` mode for observing before promoting to `ENFORCE`.
- Hard limits:
  - 20 temporal policies per engine, 3 temporal operators per policy, 24 h maximum window.
  - The **session id is supplied by the caller** in the `x-amzn-bedrock-agentcore-policy-session-id` header.
  - AWS itself notes that per-session rate limits reset when a caller starts a new session.
  - Changing temporal policies invalidates open sessions (HTTP 409).
  - Only same-account, same-region chains are covered. The Workload Access Token must be forwarded through any non-AgentCore hop.
- Meaning: B-1, fully deterministic. Reva's view (source 04) is that Dogwood moves authorization to "history-aware", with intent and behavior layered on top by a control plane.

### 3.4 Anthropic (added: a new hook surface)

**Inference Hooks (Claude Enterprise) — beta, launched 5 Aug 2026** [V] ([docs](https://platform.claude.com/docs/en/manage-claude/inference-hooks); [blog](https://claude.com/blog/claude-enterprise-inference-hooks)).
- Before each governed inference, Anthropic POSTs the conversation transcript, including tool calls and results, to the customer's server and waits for allow or deny.
- The only event today is `prompt`. There is no redaction. System prompts and tool definitions are never sent.
- Verdict timeout is 1–10,000 ms (default 5 s). The organization picks fail-open or fail-closed. There is a circuit breaker, a shadow mode and percentage rollout.
- It covers claude.ai, Cowork, Claude Code and Claude Tag. It is not on Bedrock or Vertex.
- Netskope, Palo Alto, Zscaler and Proofpoint are named integrators [S] ([TNW](https://thenextweb.com/news/anthropic-inference-hooks-dlp-claude-enterprise)).
- Claude Code pre-tool hooks are a separate, agent-local control point (Reva source 05).

### 3.5 Okta / Auth0

- **Agent SSO / Cross App Access (XAA)** was generally available on 24 Aug 2026 and is included in core Okta SSO [S] ([WorkOS, 2026-09-04](https://workos.com/blog/cross-app-access-converged-in-eight-days); [startwithidentity](https://startwithidentity.com/blog/2026-08-24-okta-agent-sso-cross-app-access-general-availability/)).
  - With XAA the enterprise IdP decides, by admin policy, whether a requesting app (agent) may reach a resource app for a user. It then mints an **ID-JAG** assertion, which can be scoped down.
  - ID-JAG is IETF `draft-ietf-oauth-identity-assertion-authz-grant` (-04 in 2026) [V] ([datatracker](https://datatracker.ietf.org/doc/draft-ietf-oauth-identity-assertion-authz-grant/)).
  - The XAA/ID-JAG material reviewed carries **no intent or purpose claim** [S] (WorkOS).
- **Okta Agent Gateway** [V] ([Okta blog, 2026-07-23](https://www.okta.com/blog/product-innovation/agent-gateway-runtime-governance/)).
  - It is an identity-native gateway in the tool-call path. It verifies agent and user, controls which tools the agent may use, holds credentials, brokers short-lived credentials (XAA or brokered OAuth), aggregates tools as a virtual MCP server, and logs every call.
  - It works with Claude Code, Copilot, Agentforce and AgentCore. There is no code change. MCP Bridge can be deployed on-prem via professional services.
  - The Oktane press release of 22 Sep 2026 lists Agent Gateway as **planned GA Q3 2026** and a runtime kill-switch as Q4 2026 [C] ([Okta PR](https://www.okta.com/newsroom/press-releases/ai-innovations-oktane-2026/)).
  - Okta's Oktane messaging frames the future as authorization tied to delegated authority and **intent** [S] ([Investing.com transcript summary](https://www.investing.com/news/transcripts/okta-at-oktane-call-2026-investor-summit-push-to-secure-ai-agents-93CH-4913663)). No shipped intent evaluation was found.
  - Also announced: the Blueprint for the Secure Agentic Enterprise and the Blueprint Alliance of twelve vendors, including AWS, Google Cloud and CrowdStrike, on 22 Sep 2026 [S].
- **Auth0 for AI Agents** (GA 19 Nov 2025) [V] ([Auth0 blog](https://auth0.com/blog/auth0-for-ai-agents-generally-available/)) includes user auth, Token Vault, **asynchronous authorization (CIBA)** with rich authorization details in the consent prompt, and FGA for RAG. XAA requesting-app support moved to early access on 31 Aug 2026 [S] (WorkOS).
- Meaning: ID-only, with HITL approval as a real outcome. It is Okta's closest overlap with WAAG and is heavily distributed through the SSO base.

### 3.6 Zscaler (named competitor)

- **Zenith Live, 9 Jun 2026:** Zscaler announced **AI Broker** (inline MCP and A2A brokers with an integrated **Agent Registry** for per-agent, function-specific permissions), **AI Access Graph** (identity/data lineage from the Symmetry Systems acquisition), Endpoint AI Security, and AI Protect updates. Those updates include "intent-based guardrails for multi-turn conversations" and prompt extraction across 250+ GenAI apps [V] ([press release](https://www.zscaler.com/press/zscaler-unveils-new-product-innovations-secure-agentic-ai); [blog](https://www.zscaler.com/blogs/product-insights/how-zscaler-secures-the-agentic-ai-era)). **No GA date or technical detail on how AI Broker treats delegation, OBO or intent was published** [V].
- **AI Guard "intent-based detectors"** (whitepaper, 2026) [V as vendor doc] ([PDF](https://www.zscaler.com/resources/white-papers/zscaler-ai-guard-detectors-inline-protection-for-ai.pdf)):
  - Task-specific **SLM** detectors run in parallel: injection/jailbreak (including manipulating tools), toxicity, IP, PII, topic/off-topic, secrets, URLs, gibberish, code, regulated advice, custom regex.
  - A policy engine composes the results, with actions allow, detect/log, block, redact, coach.
  - Two deployments: inline at the edge for users → public GenAI, and an API detection endpoint for apps → LLMs.
  - Zscaler advises rolling out in detect mode first.
  - "Intent" here means *what the text is trying to do* (I-3), not task alignment. No latency or accuracy figures; "no performance tradeoffs" is a claim [C].
- **Oasis integration** (10 Jun 2026) adds NHI and agent lifecycle governance to the Zero Trust Exchange [S] ([PR Newswire](https://www.prnewswire.com/news-releases/oasis-security-announces-integration-with-zscaler-to-extend-zero-trust-to-non-human-and-agentic-identities-302796207.html)).
- **Analyst gaps (Futurum):** governance depends on agents being registered and classified first. East-west cloud agent traffic may bypass Zscaler. Delegation and intent controls are not detailed [S] ([Futurum](https://futurumgroup.com/insights/zscaler-bets-on-agentic-ai-security-at-zenith-live-2026/)).
- Zscaler's six prep questions to WhiteSwan included "readiness for intent-aware authZ" (Product Brief §9). This reads as a gap they are looking to fill or partner for.

### 3.7 Netskope (live WhiteSwan deal)

- **Netskope One AI Security** (11 Mar 2026) includes Agentic Broker (visibility and control of sanctioned and unsanctioned MCP), AI Guardrails (injection, jailbreak, moderation), AI Gateway (private apps/LLMs), AI Red Teaming and AgentSkope. These are reported GA [S] ([Netskope IR](https://investors.netskope.com/news-releases/news-release-details/netskope-unveils-netskope-one-ai-security-delivering-high)).
- Docs describe Agentic Broker as MCP visibility plus access control (per-server or block-all), with DLP as an add-on licence [V] ([Netskope docs](https://docs.netskope.com/en/agentic-broker)). **A2A coverage and intent features were not found.**
- The **Aembit partnership** (Mar 2026) has Aembit inject agent identity and credentials so Netskope's AI Gateway can apply identity-based policy [V] ([Aembit blog](https://aembit.io/blog/announcing-the-aembit-netskope-partnership-for-agentic-ai-security/)).
- Netskope is a named Anthropic Inference Hooks integrator [S].
- Netskope's RFI to WhiteSwan asked about "adaptive access by risk/intent" (Q15), which WhiteSwan answered "Partially" (Product Brief §9.2).

### 3.8 Palo Alto Networks — Prisma AIRS

- **AIRS 3.0** (23 Mar 2026) covers agent runtime security (tool misuse, memory manipulation, adversarial instructions), an **AI Agent Gateway** (governs tool calls, model access and external connections), agent identity, artifact scanning, agent red teaming and posture management [V] ([PANW blog](https://www.paloaltonetworks.com/blog/2026/03/prisma-airs-3-0-autonomous-ai/)).
- **AI Gateway GA 16 Jul 2026** [V] ([PANW blog](https://www.paloaltonetworks.com/blog/2026/07/announcing-general-availability-of-prisma-airs-ai-gateway/)).
- AI Red Teaming added a **Privilege Misuse** category in June 2026. It tests role-claiming, cross-user data access, confused-deputy use of the agent's own privileges, and permanent elevation [S] ([release notes](https://docs.paloaltonetworks.com/ai-runtime-security/new-features/by-date/prisma-airs/june-2026)).
- Google Agent Gateway integration via service extension inspects MCP calls and responses and aims at "permitted but operationally dangerous" actions [S] (SiliconANGLE).
- No latency or accuracy figures found. Meaning: I-3 plus tool-misuse detection.

### 3.9 CrowdStrike

- **Falcon Guardian (AIDR)** was announced 1 Sep 2026 [V] ([press release](https://www.crowdstrike.com/en-us/press-releases/crowdstrike-unveils-falcon-guardian-ai-agent-security/)).
  - It works endpoint-first, linking prompt → identity → tool call → system action into an execution graph.
  - "Intent" is assessed by **CrowdStrike analysts** (managed service), not by an automated authorizer.
  - An AI Gateway is described in future tense.
- **SGNL acquisition** (announced 8 Jan 2026, about $740M) [V] ([CrowdStrike PR](https://www.crowdstrike.com/en-us/press-releases/crowdstrike-to-acquire-sgnl-to-transform-identity-security-for-ai-era/)) and **Continuous Identity for AI Agents** (15 Jun 2026) [S] provide dynamic grant and revoke by real-time risk.
- Meaning: B-2 plus dynamic authorization. Strong on detection and response; enforcement at the protocol layer is still arriving.

### 3.10 Check Point (Lakera)

- Check Point agreed to acquire Lakera in Sep 2025 [S] ([Check Point PR](https://www.checkpoint.com/press-releases/check-point-acquires-lakera-to-deliver-end-to-end-ai-security-for-enterprises/)).
- Lakera Guard / AI Guardrails screens the whole agent workflow, including tool calls, tool responses and tool descriptions, for prompt attacks, leakage, content, malicious links and "off-policy agent behavior". It *flags*; the application decides. It is available as SaaS or self-hosted [V] ([docs](https://docs.lakera.ai/guard)).
- Claimed: sub-50 ms, 98%+ detection, 0.01% false-positive rate [C] (reported via [appsecsanta](https://appsecsanta.com/lakera)).

### 3.11 Meta LlamaFirewall (AlignmentCheck)

- The OSS library ([PurpleLlama](https://github.com/meta-llama/PurpleLlama/tree/main/LlamaFirewall), about 4.4k stars [S]) contains PromptGuard 2, **AlignmentCheck**, CodeShield and regex/custom scanners.
- **AlignmentCheck** audits the agent's chain-of-thought and actions against the user goal to catch goal hijacking. It uses a large model (Llama 4 Maverick or Llama 3.3 70B), which the repo reaches via a hosted API key [V].
- Paper results ([arXiv 2505.03574](https://arxiv.org/abs/2505.03574), [HTML](https://arxiv.org/html/2505.03574)) [V]:
  - On AgentDojo, AlignmentCheck alone gives **ASR 2.89% with 43.1% utility**; with PromptGuard 2 it gives **1.75% ASR, 42.7% utility**. Baseline ASR is reported as 17.6%.
  - On Meta's internal goal-hijack set: over 80% recall with under 4% false positives.
  - The paper calls AlignmentCheck **experimental** and notes significant compute overhead.
  - PromptGuard 2 latency: **19.3 ms (22M)** and **92.4 ms (86M)** per classification.
- Counter-evidence [V]:
  - **Jha, Triedman, Wagle, Shmatikov (arXiv 2510.17276, rev. Mar 2026)** show control-flow-hijacking attacks evade alignment checks of inter-agent messages, even with strong judge LLMs. They propose **ControlValve**, a control-flow-integrity-style allowed-graph plus per-invocation rules ([arXiv](https://arxiv.org/abs/2510.17276)).
  - **Bhagwatkar et al. (arXiv 2510.05244)** find a simple tool-input minimizer plus tool-output sanitizer reaches near-perfect scores on AgentDojo, ASB, InjecAgent and tau-Bench. They conclude the benchmarks are too weak ([arXiv](https://arxiv.org/abs/2510.05244)).
- Meaning: I-2 in-agent. It is the best-documented public accuracy data, and it shows a large utility cost and known bypasses.

### 3.12 NVIDIA NeMo Guardrails

- OSS, Apache-2.0, v0.24.1, about 7.2k stars [S] ([GitHub](https://github.com/NVIDIA/NeMo-Guardrails)).
- Rail types: input, dialog, retrieval, **execution** (custom action inputs and outputs) and output. Dialog rails map user messages to **canonical-form user intents** using embedding similarity plus an LLM, and drive Colang flows.
- Safety models: Nemotron Content Safety, Topic Control and Jailbreak Detect, packaged as NIMs [S] ([docs](https://docs.nvidia.com/nemo/guardrails/home)).
- No latency benchmarks in the README. It is an in-agent or sidecar deployment. "Intent" here means *dialog intent classification* for conversation control, not authorization.

### 3.13 Invariant Labs (Snyk)

- Snyk acquired Invariant Labs in June 2025 [S] ([SiliconANGLE](https://siliconangle.com/2025/06/24/snyk-acquires-invariant-labs-expand-ai-agent-security-capabilities/)).
- **Invariant Guardrails** is a Python-like rule language over agent **traces**, with a `->` operator for tool-call sequences, e.g. read inbox followed by send-email to an unknown address. It can call ML detectors such as `prompt_injection()` inside rules.
- It deploys as a **gateway proxy** for LLM/MCP traffic or locally. The repo is Apache-2.0, about 460 stars [S] ([GitHub](https://github.com/invariantlabs-ai/invariant)). MCP-Scan covers tool poisoning and rug-pulls.
- Meaning: B-1 sequence rules. It is the closest OSS analog to "trace policies at a gateway".

### 3.14 LangChain (SemIf guardrails)

- The demo (source 02, about 24 Sep 2026) is layered, all in the agent harness:
  1. ModelGuardrailMiddleware classifies incoming requests with SemIf into `allow` / `block_model` / `cancel_run`.
  2. ToolGuardrailMiddleware uses an **agent card** that tags each tool **allow / evaluate / deny**. Allow and deny are deterministic. Only **evaluate** tools go to SemIf, with user intent, prior guardrail decisions and tool metadata, producing `allow` / `block_tool` / `cancel_run`.
  3. A compact `GuardrailState` avoids sending the full history.
- LangSmith LLM Gateway serves decision models [V] ([LangChain docs](https://docs.langchain.com/langsmith/llm-gateway-decision-models)):
  - **SemIf** (`semif-qwen3.5-4b`), free until 28 Sep 2026 for US orgs on some plans;
  - TypeSafe **Jev** (`typesafe/jev-1.13.0`, BYOK);
  - typed outputs `noul`, `choice`, `score`.
- No latency or accuracy figures are published. A commenter on the post points out that allow/block covers malicious actions but not *authorized-but-wrong* actions (source 02).
- **This is the pattern the Jev CEO recommended to WhiteSwan** (source 03): deterministic first, a small typed model only where needed. Detailed evaluation of SemIf/Jev/Laya belongs to the sibling dossier.

### 3.15 Lasso Security

- Lasso markets "Intent-Aware Governance". It says it analyzes the intent behind each agent action for policy misalignment, indirect injection and memory poisoning, enforced "at the proxy, API, or AI Gateway layer". It also offers an **OSS MCP Gateway** plugin proxy [V as vendor page] ([Lasso](https://www.lasso.security/use-cases/ai-agent-governance); [MCP gateway](https://github.com/lasso-security/mcp-gateway)).
- Claimed: **under 50 ms** and **99.83%** detection accuracy (another page says 98.6%) [C].
- A search digest described an "intent deputy" component; this was **not found** on the fetched page, so it is unverified.

### 3.16 Zenity

- Zenity monitors agent execution step by step and prevents inline via platform integrations: Copilot Studio, Microsoft Foundry, and, since August 2026, GitHub Copilot, OpenAI Codex and custom OTel/gen_ai agents [V] ([Aug 2026 updates, pub. 2026-09-16](https://zenity.io/blog/august-2026-product-updates)).
- **Boundaries** are "intent-based runtime security rules". New system *taints* classify actions as read or mutative. There is AI-assisted authoring and a policy-as-code CLI.
- Zenity defines intent as the goal an agent pursues given memory, role, tools and workflow history [V] ([academy, 2026-04-07](https://zenity.io/academy/ai-intent-detection)). Runtime security was reported GA on 17 Mar 2026 [S].
- No numbers published.

### 3.17 Identity/NHI specialists: Aembit, Astrix, Oasis

- **Aembit.** "IAM for Agentic AI" (announced Oct 2025, GA 2026 [S]) has two parts: **Blended Identity** (agent plus the human it represents, evaluated together) and the **MCP Identity Gateway** (authenticates, applies policy, exchanges tokens, injects credentials never exposed to the agent) [V] ([docs](https://docs.aembit.io/ai-guide/blended-identity)). It added Okta XAA support on 22 Sep 2026 [S] ([GlobeNewswire](https://www.globenewswire.com/news-release/2026/09/22/3366438/0/en/aembit-launches-support-for-okta-cross-app-access-extending-enterprise-identity-controls-to-ai-agents.html)). No intent evaluation. Meaning: ID-only.
- **Astrix.** The Agent Control Plane (Sep 2025) gives JIT scoped credentials. **Agent Policies** (23 Mar 2026) are allow/flag/block rules by user, department, platform and resource type, evaluated before an action executes, plus anomaly monitoring [V] ([PR Newswire](https://www.prnewswire.com/news-releases/astrix-security-delivers-the-most-comprehensive-ai-agent-discovery-and-enhances-security-with-agent-policy-enforcement-302719653.html)). The enforcement location is not disclosed.
- **Oasis.** Agentic Access Management converts each request (prompt, tool call or plan) into **structured intent**, applies deterministic policy, and issues **ephemeral, task-scoped** identities that are torn down afterwards [V as vendor page] ([Oasis](https://www.oasis.security/blog/introducing-oasis-agentic-access-management)).
  - The Cursor integration enforces allow / warn / step-up / deny through Cursor Hooks before MCP or command execution [V] ([Oasis+Cursor, updated 2026-05-01](https://www.oasis.security/blog/cursor-oasis-governing-agentic-access)).
  - How intent is parsed (rules or a model) is not disclosed.
  - $120M Series B in March 2026 and the Zscaler integration are reported [S].
  - Meaning: I-1 (intent → JIT scope). Oasis is the closest identity-vendor analog to an "intent-scoped credential".

### 3.18 Authorization engines: Permit.io, Cerbos, Oso

- **Permit.io MCP Gateway** [V] ([docs](https://docs.permit.io/permit-mcp-gateway/overview/); [product page](https://www.permit.io/mcp-gateway)).
  - Tools are classed by trust level: read / write / destructive.
  - Users consent up to an admin ceiling, enforced with ReBAC across human → agent → server.
  - On **Enterprise**, high-risk calls require human approval.
  - A distinctive feature is **agent interrogation**: only an `identify_self` tool is exposed until the agent identifies itself. Later behavior is compared with that fingerprint, and drift (e.g. a changed system prompt or model) can downgrade trust, force re-consent, or block.
  - Deployment is hosted, customer VPC, or fully on-prem (air-gap capable).
  - Meaning: ID/ReBAC + B-2 + HITL.
- **Cerbos** is an OSS PDP with YAML RBAC/ABAC and documented MCP, FastMCP and A2A integrations [V] ([Cerbos MCP](https://www.cerbos.dev/ecosystem/mcp), [A2A](https://www.cerbos.dev/ecosystem/a2a)). Sub-millisecond decisions are reported [S]. No intent. It is a deterministic building block.
- **Oso** launched "Oso for Coding Agents" (Jan 2026 [S]) and "Oso for Agents": discovery, monitoring, anomaly/policy-violation detection, task-scoped permissions [S] ([osohq.com](https://www.osohq.com/)).

### 3.19 Developer auth platforms: Descope, Stytch, Scalekit, Arcade, Keycard

- **Descope.** Agentic Identity Hub 2.0 (Jan 2026) and 2.5. **XAA/ID-JAG validation and issuance** was announced 1 Sep 2026, with per-organization scope policies and step-up for sensitive agent actions [V] ([Descope PR](https://www.descope.com/press-release/cross-app-access-xaa-support)).
- **Stytch** (Twilio completed the acquisition in Nov 2025 [S], [Twilio](https://www.twilio.com/en-us/blog/company/news/twilio-to-acquire-stytch)). Connected Apps turns an app into an OAuth 2.1/OIDC provider for agents and MCP, with RBAC scopes [V] ([Stytch](https://stytch.com/connected-apps)).
- **Scalekit** provides MCP OAuth 2.1 auth, CIMD support (June 2026), and virtual MCP servers that limit tools per agent role [S] ([Scalekit updates](https://www.scalekit.com/product-updates)).
- **Arcade.dev** is an "MCP runtime": per-user OAuth brokering, credential injection at execution, authorization at the intersection of user and agent rights, and governance hooks. A $60M Series A (Jun 2026) is reported [S] ([Arcade docs](https://docs.arcade.dev/en/get-started/about-arcade); [third-party profile](https://github.com/api-evangelist/arcade-dev)).
- **Keycard** (May 2026) uses RFC 8693 token exchange that **narrows permissions at each agent handoff**, with task-scoped, session-expiring, revocable tokens across mixed delegation chains [S] ([Help Net Security](https://www.helpnetsecurity.com/2026/05/15/keycard-for-multi-agent-apps/)). This is conceptually the closest to WAAG's per-hop down-scoped OBO, but it runs in the token layer, not as an inline gateway.
- None of these five evaluate intent. All are ID-only.

### 3.20 Startups explicitly selling "intent-based access control"

- **Reva** (sources 01, 04, 05).
  - "Deterministic IBAC" asks three questions at every hop: intent alignment, behavioral consistency, and identity chain of custody. It uses LLM-as-judge and Reva-provided **SLMs** (with enterprise retrieval) inside Cedar/OPA/Zanzibar policy bounds.
  - Outcomes are Allow / Deny / **Defer (HITL)**. Each decision produces a snapshot.
  - It enforces through Kong, LiteLLM, TrueFoundry, Copilot Studio, Claude Code prehooks, SDKs and webhooks. Products are "Trust Gateway" and "Trust Guardian".
  - Claims: **p90 < 40 ms**, **98%** drift detection in internal testing, "patent-pending", billions of decisions [C].
  - Reva is the most complete articulation of what WhiteSwan is considering, and a direct messaging competitor.
- **C1 (formerly ConductorOne)** "Agent Runtime Governance", GA July 2026 [C] ([C1 blog, 2026-07-29](https://www.c1.ai/blog/introducing-agent-runtime-governance)).
  - It deliberately rejects *inferred* intent. Intent means **governed scope**: each agent gets an identity tied to its task's toolset.
  - An identity-aware AI gateway applies policies triggered by "lethal trifecta" conditions (private data + untrusted content + external channel).
  - Outcomes are block, **hold for approval**, or redact.
- **PlainID** (26 Jun 2026) applies "intent-based access control" as PBAC at four points: prompt, data, tools (including MCP) and response masking, via LangChain, MCP gateways and cloud-AI authorizers [V] ([PlainID](https://www.plainid.com/intent-based-access-control-for-ai-agents/)). It is rule-based.
- **IndyKite** (18 Sep 2026): an "Intent Agent" converts natural-language requests into structured authorization statements, enforced by **AgentControl** [V] ([IndyKite](https://www.indykite.ai/blogs/intent-based-access-control-for-ai-agents)). The model and architecture are not disclosed.
- **TrustLogix** and several Medium/Forbes pieces also use the IBAC label [S].
- **CSA reference framing** (Tuhin Banerjee, 14 Jul 2026) [V] ([CSA](https://cloudsecurityalliance.org/blog/2026/07/14/when-who-are-you-is-no-longer-enough-the-case-for-intent-based-access-control-in-the-age-of-ai-agents)):
  - agents cryptographically **declare** task intent at session start;
  - behavior is matched against the declared task's baseline;
  - permissions are checked against the task minimum;
  - credentials are task-scoped, minutes-long, and carry task context as certificate extensions;
  - drift triggers revocation.

### 3.21 Standards that could carry intent

- **OAuth Transaction Tokens** — `draft-ietf-oauth-transaction-tokens-11` (30 Jul 2026), WG consensus, waiting for write-up. IESG milestone Dec 2026 [V] ([datatracker](https://datatracker.ietf.org/doc/draft-ietf-oauth-transaction-tokens/)).
  - `scope` captures the transaction's purpose as narrowly as possible.
  - `tctx` holds immutable authorization context across the call chain; `rctx` holds environmental context.
  - There is **no separate purpose claim**.
- **Transaction Tokens for Agents** (individual draft, Raut, v06 Apr 2026, now renamed `draft-araut-…`) adds actor (`act`) and principal (`sub`) contexts [V] ([datatracker](https://datatracker.ietf.org/doc/draft-oauth-transaction-tokens-for-agents/)). Also noted: `draft-liu-oauth-a2a-profile-00` (A2A profile for Txn-Tokens) and `draft-oauth-ai-agents-on-behalf-of-user-00` [S].
- **ID-JAG** carries identity and scope, not intent [V].
- **AP2 mandates** are the only deployed standard that signs a user's intent constraints and verifies them downstream [V].

---

## 4. Cross-cutting findings

### 4.1 Who actually sees the human's words (and so can judge I-2)

| Surface | User's words | Planner reasoning | Tool call + args | Tool descriptions | History |
|---|---|---|---|---|---|
| Copilot Studio webhook [V] | Yes (`userMessage`, `chatHistory`) | Yes (`thought`) | Yes | Yes (`toolDefinition`) | Previous tool outputs; plan/step ids |
| Google Semantic Governance [V] | Yes | No (not listed) | Yes | Yes (manifest) | Chat history |
| MS Task Adherence API [V] | Yes (caller sends transcript) | Assistant messages | Yes | Yes | Transcript |
| Anthropic Inference Hooks [V] | Yes (transcript) | No (system prompt excluded) | Yes (in transcript) | No (tool definitions excluded) | Whole conversation |
| AgentCore Dogwood [V] | No | No | Yes (input/output fields) | No | Session events (caller-chosen session) |
| Okta / Zscaler / Netskope gateways [V/S] | No | No | Yes | Via registry (for policy, not judgment) | Not documented |
| **WAAG today** (grounding §13) | **No** (console path) | No | MCP structured args; A2A LLM-written text in `argumentsFlat` | In memory, **not given to PDP** | None at decision time; `corr_id`/`trace_id` present but unread |

### 4.2 Latency and failure behavior (published only)

| Item | Figure | Label |
|---|---|---|
| Copilot Studio external check | must answer <1,000 ms; else **allow** | [V] |
| Anthropic Inference Hooks | 1–10,000 ms, default 5,000 ms; fail-open or fail-closed; circuit breaker | [V] |
| PromptGuard 2 (22M / 86M) | 19.3 ms / 92.4 ms per classification | [V] (paper) |
| AlignmentCheck | "significant" overhead, large LLM; no figure | [V] |
| AgentCore temporal eval | `TemporalLatency` metric exists; no published value | [V] |
| Reva | p90 < 40 ms (standard path) | [C] |
| Lasso / Lakera | < 50 ms | [C] |
| Cerbos | sub-ms | [S] |
| Google Semantic Governance, MS Task Adherence, Zscaler AI Guard, Prisma AIRS | not published | — |
| **WAAG** (grounding §13(d)) | governance ~12–13 ms p50/hop; downstream MCP 1.4 s, A2A skill 6.9 s p50 | internal measurement |

Takeaway: a 20–100 ms synchronous classifier is under 2–5% of a WAAG hop's wall time, since the downstream call takes seconds. Latency is not what rules out a model. Correctness, fail mode and thread cost are (WAAG is fully blocking; grounding §13(d)).

### 4.3 Accuracy: evidence vs claims

- **Independent or peer-reviewed:**
  - AlignmentCheck: ASR 2.89% at 43.1% utility on AgentDojo; 80%+ recall and under 4% FPR on Meta's internal set [V].
  - CaMeL: 77% vs 84% task success with provable security [V].
  - Alignment checks bypassed by control-flow hijacking [V].
  - Benchmarks saturated by simple firewalls [V].
- **Vendor-only:** Reva 98% drift detection; Lasso 99.83%; Lakera 98%+ and 0.01% FPR [C].
- **Nobody publishes** false-positive rates on *benign enterprise traffic* for task-alignment judges. For an authorization product this is the number that matters, because a false deny breaks a business workflow.

### 4.4 Enforcement outcomes offered

| Outcome | Who offers it |
|---|---|
| ALLOW/DENY only | Google Semantic Governance, Anthropic hooks, Copilot Studio webhook, Foundry agents (block), Okta Gateway, Cerbos, **WAAG PDP** |
| Human approval / hold / defer | Dogwood (approval event as a precondition) [V]; Permit HITL (Enterprise) [V]; C1 hold [C]; Auth0 CIBA [V]; Reva Defer [C]; Oasis step-up [V]; LangChain `cancel_run` (kill, not approve) [V]; MS Task Adherence *recommends* HITL [V] |
| Redact / mask / coach | Zscaler AI Guard; Model Armor de-identify; Netskope DLP; C1; PlainID masking; CrowdStrike |
| Credential scoping as outcome | Okta/XAA, Aembit, Oasis, Keycard, Astrix, Arcade (token narrowed or issued JIT) |
| Trust downgrade / re-consent | Permit (fingerprint drift) |

---

## 5. Security-architect reading

1. **An LLM judge is an attack surface.** The judge reads attacker-influenced text: tool outputs, A2A messages, retrieved documents. Jha et al. show alignment checks can be steered. Google states its verdicts "may not be accurate". Treat any model verdict as a *risk signal inside a deterministic boundary* (Reva's framing, and the Jev CEO's advice in source 03), never as the authority.
2. **Fail-open defaults leak.** Copilot Studio allows on timeout. Anthropic lets the customer choose. A security gateway should default to fail-closed for high-risk capabilities and fail-open only for low-risk reads, i.e. a per-capability fail mode.
3. **Caller-chosen session ids weaken history rules.** AgentCore's temporal policies depend on a caller-supplied session header, and AWS notes that per-session limits reset with a new session. WAAG already mints the per-hop OBO and stamps `trace_id`/`corr_id`, so it can scope history to a **gateway-issued trace**. That is structurally stronger, provided WAAG closes its own `contextId` dodge on A2A (a2a-missing-governance-checks.md, gap #2).
4. **Deterministic data-flow beats semantic judgment where it applies.** CaMeL, ControlValve (allowed control-flow graph), Invariant `->` flows and Dogwood all constrain *which sequences* may happen, with no model in the loop. For multi-hop A2A this corresponds to "allowed delegation graph per root task", which WAAG can enforce because it sees every hop.
5. **Intent capture must be tamper-evident.** AP2 signs intent, the CSA framing declares it cryptographically, and Txn-Tokens keep `tctx` immutable. An intent string that downstream agents can rewrite is worthless. WAAG's OBO is the natural carrier, since it is minted by the gateway and down-scoped per hop.
6. **Beware grants that widen silently.** A semantic layer on top of a PDP that ignores `principal in` / `resource in` head forms (grounding §14 item 1) would *appear* to add control while the base grant stays overly broad.

## 6. Product reading

- **Buyers are being told three stories.** Identity vendors (Okta, Aembit, Descope) say "agent identity + scoped tokens is enough". SSE vendors (Zscaler, Netskope, PANW) say "inspect the content inline". IBAC startups (Reva, Lasso, C1, PlainID, IndyKite, Oasis) say "authorize the purpose". Hyperscalers bundle all three *inside their own platforms* (Google Agent Gateway, AWS AgentCore, Microsoft Foundry/Copilot Studio).
- **Distribution risk for WhiteSwan.**
  - Okta will ship Agent Gateway into a huge SSO base at no incremental price for Agent SSO.
  - Google and AWS gateways are "free" within their clouds.
  - Zscaler and Netskope have inline reach and already broker MCP (Zscaler says A2A as well).
  - WhiteSwan cannot win on "we have a gateway" or "we have agent identity".
- **Where buyers are asking questions WhiteSwan can answer:** Netskope Q15 (risk/intent-adaptive access) and Q17/Q18 (multi-agent chain visibility, per-hop JIT). Zscaler asked about "readiness for intent-aware authZ". Both SSE vendors look like **partners/OEMs** for a chain-aware decision layer rather than direct replacements (Product Brief §9).
- **Messaging lesson.** C1 wins credibility by *refusing* to infer intent. Reva wins attention with "deterministic IBAC" and hard numbers, which are unverified. Proof that the system is deterministic, easy to audit and explainable sells better than model accuracy claims to CISOs.

---

## 7. White space an inline agentic auth gateway like WAAG can own

Each item is mapped to what already exists in WAAG (grounding §13) and to who else does it.

| # | White space | Why it is open | WAAG leverage (existing seams) | Nearest competitor and its limit |
|---|---|---|---|---|
| W1 | **Chain-aware authorization across heterogeneous MCP *and* A2A, multi-hop, multi-vendor** | Google and AWS do it only inside their runtimes (AWS: same account and region). Okta, Zscaler and Netskope don't document A2A delegation semantics | Every hop already passes HopOrchestrator; `act_chain` is verified and rooted at the human; per-hop OBO is scoped to one capability | Google Agent Gateway (walled garden); AgentCore (account/region-bound); Keycard (token layer, not inline) |
| W2 | **Signed "intent envelope" carried in the per-hop token** | AP2 proves the pattern (sign intent once, verify deterministically downstream) but only for payments. Txn-Tokens give `scope`/`tctx` but no product applies them to agent chains | The OBO already carries `act_chain`, `trace_id`, `scope`, `corr_id`. Add a root-bound task envelope (allowed capability set, resource constraints, limits, TTL) that can only be narrowed at each hop, checked deterministically by the PDP | AP2 (payments only); C1/Oasis (scope, not chain-propagated); Keycard (narrowing, no inline check of args) |
| W3 | **Trace-scoped temporal/behavior rules owned by the gateway** | Dogwood is the only productized temporal policy, and it relies on caller-supplied session ids | `trace_id`/`corr_id` exist; `pdp_audit_log` and `gateway_audit_log` hold per-trace rows; the in-flight registry holds parent text (grounding §13(f)); nothing reads them yet | AgentCore Dogwood (session header, 24 h, 20 policies) |
| W4 | **Graduated outcomes: ALLOW / DENY / REQUIRE_APPROVAL / STEP-UP / DOWNSCOPE, with the chain paused** | Google, Anthropic and Copilot Studio are allow/deny. Approval exists in only a few products, none chain-aware | PDP has no obligations today (grounding §13(b)); needs an outcome type and an async hold. The existing human-approval gate for identities is a precedent | Permit HITL; Auth0 CIBA; C1 hold; Dogwood approval-event precondition |
| W5 | **Be the "external threat detection" / hook back-end for agent platforms** | Microsoft and Anthropic published schemas that hand the human's words and the planned tool call to *any* vendor. This is the cleanest way for WAAG to get the missing intent evidence without owning the runtime | Implement Copilot Studio `analyze-tool-execution` (<1 s) and an Anthropic Inference Hooks endpoint. Correlate the root intent they reveal with WAAG's `trace_id` on later MCP/A2A hops | Reva, Zenity, CrowdStrike, Defender already plug into Copilot Studio; none ties it to a downstream verified delegation chain |
| W6 | **Deterministic-first, model-optional decision tiers** | The LangChain agent-card pattern (allow / evaluate / deny) and the Jev CEO's "smallest primitive" advice are sound, but no *gateway* ships it for heterogeneous agents | Capability profiles exist (allow-list). Add an "evaluate" tier per capability. Only those hops call a local, customer-hosted classifier via the unused `CustomAttributeProvider` SPI (grounding §13(c)); its output becomes a `context.*` attribute bounded by policy; advisory-first | LangChain+SemIf (in-agent, one framework); Google Semantic Gov. (Google-only, preview, no fail-mode docs) |
| W7 | **Use the tool/skill *descriptions and schemas* as policy inputs** | Google's judge uses the tool manifest; Copilot Studio sends `toolDefinition`. Gateways rarely expose it to deterministic policy (e.g. read vs mutative, as in Zenity's taints) | WAAG already stores descriptions and inputSchema in the registry but never passes them to the PDP (grounding §13(a)). Derive deterministic tags (read/write/destructive/external-egress) at registration | Permit trust levels; Zenity taints (platform-specific) |
| W8 | **Decision receipts as audit evidence** | Reva sells "decision snapshots". AP2 mandates are non-repudiable. The Dakera/TealTiger idea: storage is evidence, not authority (source 03) | `pdp_audit_log` + `gateway_audit_log` per correlation; add intent envelope, evaluated signals and outcome; link parent→child | Reva (claims); AgentCore CloudWatch spans |

**What not to chase:**
- Generic prompt-injection or content classifiers (I-3). Zscaler, Netskope, PANW, Check Point, Google and Microsoft own this, and WAAG's egress classifier is observe-only.
- Endpoint telemetry (CrowdStrike).
- A proprietary LLM judge as the authority. Evidence and research both say it is bypassable, and nobody publishes enterprise false-positive rates.

---

## 8. Open questions

1. **Zscaler AI Broker:** GA date; whether A2A messages are inspected by AI Guard detectors; whether it understands OBO/delegation chains or only per-agent permissions; whether Oasis provides the identity piece.
2. **Google Semantic Governance:** which model, typical latency, fail-open vs fail-closed, pricing, and whether it will support approval outcomes or third-party runtimes.
3. **Okta Agent Gateway:** is it GA as of end of Q3 2026? Does it propagate delegation across agent→agent hops, or only agent→tool? Any plan for intent claims in XAA/ID-JAG?
4. **Dogwood:** is there a Java evaluator, or only the AWS service and a reference implementation? Can WAAG adopt the language independently of AgentCore? (This matters because WAAG's PDP is a regex subset of Cedar, not the Cedar library.)
5. **Microsoft Task Adherence:** model family, latency and false-positive rates. Will it leave preview?
6. **Copilot Studio partner integration:** any certification or listing requirements for a third-party "external threat detection" provider, and whether Entra app registration alone suffices per customer tenant.
7. **Netskope Agentic Broker:** A2A support, and whether Netskope would consume a WAAG decision (OEM/partner) for Q15/Q17/Q18.
8. **Reva claims** (p90 < 40 ms, 98%): methodology, dataset, and whether the SLM runs in the customer environment by default.
9. **Lasso** "intent deputy" and the 99.83% figure: source and methodology.
10. **Oasis** intent parser: rules or model? Where does it run?
11. **AP2 v0.1 → v0.2 naming change** (Intent/Cart → Open/Closed Checkout & Payment Mandates): confirm from the repository changelog, which could not be read due to the API rate limit.
12. Is there **any public false-positive benchmark** for task-alignment judges on benign enterprise workflows? None was found.

---

## 9. Source index (all accessed 2026-09-26)

**Captured inputs:** `../sources/01-reva-ibac-whitepaper.md`, `02-linkedin-langchain-semif-post.md`, `03-ceo-idea-and-jev-ceo-chat.md`, `04-reva-aws-dogwood-post.md`, `05-reva-anthropic-inference-hooks-post.md`.

**Microsoft:**
- https://learn.microsoft.com/en-us/entra/identity/conditional-access/agent-id
- https://learn.microsoft.com/en-us/entra/id-protection/concept-risky-agents
- https://learn.microsoft.com/en-us/azure/foundry/guardrails/guardrails-overview
- https://learn.microsoft.com/en-us/azure/ai-services/content-safety/concepts/task-adherence
- https://learn.microsoft.com/en-us/azure/ai-services/content-safety/quickstart-task-adherence
- https://learn.microsoft.com/en-us/microsoft-copilot-studio/external-security-webhooks-interface-developers
- https://learn.microsoft.com/en-us/microsoft-copilot-studio/external-security-provider
- https://learn.microsoft.com/en-us/defender-xdr/security-for-ai/ai-agent-real-time-protection
- https://www.microsoft.com/en-us/security/blog/2026/01/23/runtime-risk-realtime-defense-securing-ai-agents/
- https://learn.microsoft.com/en-us/entra/global-secure-access/how-to-ai-prompt-injection-protection

**Google:**
- https://docs.cloud.google.com/gemini-enterprise-agent-platform/govern/gateways/agent-gateway-overview
- https://docs.cloud.google.com/gemini-enterprise-agent-platform/govern/policies/semantic-governance-overview
- https://docs.cloud.google.com/gemini-enterprise-agent-platform/govern/policies/best-practices
- https://docs.cloud.google.com/model-armor/model-armor-mcp-google-cloud-integration
- https://cloud.google.com/blog/products/ai-machine-learning/announcing-agents-to-payments-ap2-protocol
- https://ap2-protocol.org/
- https://ap2-protocol.org/ap2/specification/
- https://github.com/google-agentic-commerce/AP2
- https://arxiv.org/abs/2503.18813
- https://github.com/google-research/camel-prompt-injection
- https://neuraltrust.ai/blog/camel-prompt-injection

**AWS:**
- https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/policy-temporal.html
- https://aws.amazon.com/blogs/machine-learning/securing-ai-agents-with-temporal-policies-in-amazon-bedrock-agentcore/

**Anthropic:**
- https://platform.claude.com/docs/en/manage-claude/inference-hooks
- https://claude.com/blog/claude-enterprise-inference-hooks
- https://thenextweb.com/news/anthropic-inference-hooks-dlp-claude-enterprise

**Okta / Auth0 / standards:**
- https://www.okta.com/blog/product-innovation/agent-gateway-runtime-governance/
- https://www.okta.com/newsroom/press-releases/ai-innovations-oktane-2026/
- https://auth0.com/blog/auth0-for-ai-agents-generally-available/
- https://workos.com/blog/cross-app-access-converged-in-eight-days
- https://datatracker.ietf.org/doc/draft-ietf-oauth-identity-assertion-authz-grant/
- https://datatracker.ietf.org/doc/draft-ietf-oauth-transaction-tokens/
- https://datatracker.ietf.org/doc/draft-oauth-transaction-tokens-for-agents/
- https://www.investing.com/news/transcripts/okta-at-oktane-call-2026-investor-summit-push-to-secure-ai-agents-93CH-4913663

**SSE / security platforms:**
- https://www.zscaler.com/press/zscaler-unveils-new-product-innovations-secure-agentic-ai
- https://www.zscaler.com/blogs/product-insights/how-zscaler-secures-the-agentic-ai-era
- https://www.zscaler.com/resources/white-papers/zscaler-ai-guard-detectors-inline-protection-for-ai.pdf
- https://futurumgroup.com/insights/zscaler-bets-on-agentic-ai-security-at-zenith-live-2026/
- https://www.prnewswire.com/news-releases/oasis-security-announces-integration-with-zscaler-to-extend-zero-trust-to-non-human-and-agentic-identities-302796207.html
- https://investors.netskope.com/news-releases/news-release-details/netskope-unveils-netskope-one-ai-security-delivering-high
- https://docs.netskope.com/en/agentic-broker
- https://aembit.io/blog/announcing-the-aembit-netskope-partnership-for-agentic-ai-security/
- https://www.paloaltonetworks.com/blog/2026/03/prisma-airs-3-0-autonomous-ai/
- https://www.paloaltonetworks.com/blog/2026/07/announcing-general-availability-of-prisma-airs-ai-gateway/
- https://docs.paloaltonetworks.com/ai-runtime-security/new-features/by-date/prisma-airs/june-2026
- https://siliconangle.com/2026/09/24/ai-agent-security-palo-alto-networks-googlecloudaiagentsinaction/
- https://www.crowdstrike.com/en-us/press-releases/crowdstrike-unveils-falcon-guardian-ai-agent-security/
- https://www.crowdstrike.com/en-us/press-releases/crowdstrike-to-acquire-sgnl-to-transform-identity-security-for-ai-era/
- https://www.crowdstrike.com/en-us/blog/falcon-aidr-protects-copilot-studio-agents-and-claude-code/
- https://www.checkpoint.com/press-releases/check-point-acquires-lakera-to-deliver-end-to-end-ai-security-for-enterprises/
- https://docs.lakera.ai/guard
- https://appsecsanta.com/lakera

**Guardrail frameworks / research:**
- https://arxiv.org/abs/2505.03574
- https://arxiv.org/html/2505.03574
- https://github.com/meta-llama/PurpleLlama/tree/main/LlamaFirewall
- https://arxiv.org/abs/2510.17276
- https://arxiv.org/abs/2510.05244
- https://github.com/NVIDIA/NeMo-Guardrails
- https://docs.nvidia.com/nemo/guardrails/home
- https://github.com/invariantlabs-ai/invariant
- https://siliconangle.com/2025/06/24/snyk-acquires-invariant-labs-expand-ai-agent-security-capabilities/
- https://docs.langchain.com/langsmith/llm-gateway-decision-models

**AI-security and identity startups:**
- https://www.lasso.security/use-cases/ai-agent-governance
- https://github.com/lasso-security/mcp-gateway
- https://zenity.io/blog/august-2026-product-updates
- https://zenity.io/academy/ai-intent-detection
- https://docs.aembit.io/ai-guide/blended-identity
- https://www.globenewswire.com/news-release/2026/09/22/3366438/0/en/aembit-launches-support-for-okta-cross-app-access-extending-enterprise-identity-controls-to-ai-agents.html
- https://www.prnewswire.com/news-releases/astrix-security-delivers-the-most-comprehensive-ai-agent-discovery-and-enhances-security-with-agent-policy-enforcement-302719653.html
- https://www.oasis.security/blog/introducing-oasis-agentic-access-management
- https://www.oasis.security/blog/cursor-oasis-governing-agentic-access
- https://docs.permit.io/permit-mcp-gateway/overview/
- https://www.permit.io/mcp-gateway
- https://www.cerbos.dev/ecosystem/mcp
- https://www.cerbos.dev/ecosystem/a2a
- https://www.osohq.com/
- https://www.descope.com/press-release/cross-app-access-xaa-support
- https://stytch.com/connected-apps
- https://www.twilio.com/en-us/blog/company/news/twilio-to-acquire-stytch
- https://www.scalekit.com/product-updates
- https://docs.arcade.dev/en/get-started/about-arcade
- https://www.helpnetsecurity.com/2026/05/15/keycard-for-multi-agent-apps/
- https://www.c1.ai/blog/introducing-agent-runtime-governance
- https://www.plainid.com/intent-based-access-control-for-ai-agents/
- https://www.indykite.ai/blogs/intent-based-access-control-for-ai-agents
- https://cloudsecurityalliance.org/blog/2026/07/14/when-who-are-you-is-no-longer-enough-the-case-for-intent-based-access-control-in-the-age-of-ai-agents

**WAAG internal:**
- `docs/others/gateway-grounding.md` §13, §14
- `docs/others/Agentic-Gateway-Product-Brief.md` §9, §12
- `docs/features/a2a-missing-governance-checks.md`
