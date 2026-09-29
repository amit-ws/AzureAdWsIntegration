# Reva.ai and its IBAC model: research dossier

Researched 2026-09-26 for WhiteSwan intent-aware authorization. Author: research agent (Reva area).

## Executive summary (10 lines)

1. Reva (reva.ai) was founded by three ex-Saviynt leaders. It sells a Cedar/OPA-based authorization control plane and has been marketed since Feb 2026 as "the first IBAC platform". Funding and customers are not public.
2. In practice, "intent" is the **user's first message in a turn**, taken from the conversation text. It is not a signed intent object. Later hops are judged against it using a sentence that describes "what this hop does".
3. Reva's own public enforcement-point code confirms this. The Kong, Copilot Studio and Claude Code plugins send the user's words tagged as the intent-setting role, then each hop as an "agent" statement to be compared with it.
4. Drift is judged by an **LLM judge "guardrail" inside the Reva PDP**. It scores drift from 0.0 to 1.0 (threshold about 0.30) across five dimensions: Actor, Target, Value, Action and Scope. Its verdict is folded into a Cedar allow/deny, plus a `conditional_allow` result that the Claude Code plugin shows as "ask".
5. The "policy boundary" is real in shape: Cedar sets the outer limit and the judge can only narrow it. But "a hop cannot outscore its compromised ancestors" is a one-line claim with no published mechanism.
6. Behavior baselines ("self-learning models", "adaptive trust scoring") are marketing-level only. No signals, features or learning method are published.
7. The performance claims contradict each other: p90 under 10 ms, average under 40 ms, p90 under 40 ms, under 20 ms. Reva's own repo measures **~150–250 ms warm with guardrails deferred** and **2.6–3.0 s with guardrails inline**. "98% drift accuracy" comes from undisclosed internal testing.
8. Identity continuity in the public code is weak. The JWT is decoded but not verified, the agent id comes from a header by default, and the hop chain is rebuilt from a **caller-supplied `traceparent`** kept in node-local storage. No per-hop token is minted or down-scoped.
9. What is new is packaging: an LLM judge placed behind Cedar, many enforcement points sharing one API, and a decision log. The ideas themselves (intent to FGA tuples, temporal policy, transaction tokens) have prior art, including Jordan Potti's ibac.dev (Mar 2026) and AWS Dogwood (Aug 2026).
10. WAAG can beat Reva on **cryptographic lineage, where the intent anchor sits, and latency**. We should adopt their "hop sentence from structured args", the ask/defer outcome, monitor mode and separate policy vs guardrail audit.

---

## 0. Evidence grading and method

| Grade | Meaning |
|---|---|
| **V-code** | Read in Reva's own public Apache-2.0 enforcement-point source or docs on GitHub (fetched read-only via raw.githubusercontent.com, 2026-09-26, branch `main`). This is the strongest evidence of how the product actually behaves. |
| **V-page** | Stated on a Reva, AWS or third-party page (fetched 2026-09-26). This proves only that the claim is made. |
| **CLAIM** | A marketing or performance claim with no published method. |
| **ADV** | Written by Ken Huang, a disclosed Reva **advisor** (guest posts on reva.ai). This is more detailed than Reva's own pages but is not an engineering spec. |
| **INF** | My inference from the evidence, labelled as such. |

Nothing was cloned, installed or executed. The GitHub REST API rate-limited after the first calls, so file contents were read from raw.githubusercontent.com.

Primary sources:
- S01, the IBAC whitepaper (undated, unsigned): https://www.reva.ai/solution-guide/intent-and-behavior-based-access-control-ibac-the-next-evolution-of-authorization-for-the-agentic-enterprise
- S04, the AWS Dogwood post (Yash Prakash, 2026-08-11): https://www.reva.ai/blog/aws-dogwood-and-the-emerging-architecture-for-agent-authorization
- S05, the Anthropic Inference Hooks post (Amit Phadke, 2026-08-11): https://www.reva.ai/blog/anthropic-inference-hooks-bringing-enterprise-ai-policy-into-the-runtime
- Reva GitHub org `reva-ai`: https://github.com/reva-ai. It is named "Reva-Core", located in India, created 2023-09-06, with 46 public repos. Most are forks of Terraform and Helm charts. The four product repos were created 2026-08-14 to 2026-09-22.
  - `kong-plugin-reva-ai-runtime-authorization` (Lua, created 2026-09-11): https://github.com/reva-ai/kong-plugin-reva-ai-runtime-authorization
  - `reva-claude-code-authorization` (TypeScript, v1.2.0, 2026-09-24): https://github.com/reva-ai/reva-claude-code-authorization
  - `reva-copilot-threat-detection` (JavaScript, v0.2.0, 2026-09-10): https://github.com/reva-ai/reva-copilot-threat-detection
  - `demo-ai-app` (Python, 2026-09-22): https://github.com/reva-ai/demo-ai-app

---

## 1. Company background

| Item | Finding | Grade / source |
|---|---|---|
| Founders | Amit Saha (CEO; co-founder and ex-CEO of Saviynt), Yash Prakash (CSO; ex-CSO/COO of Saviynt), Tushar Agarwal (CTO; ex-Saviynt engineering leader) | V-page, https://www.reva.ai/about-us |
| Locations | Hiring for US remote and Bangalore hybrid roles. GitHub org location is "India" | V-page, about-us; GitHub org API |
| Public launch as IBAC | Amit Saha's LinkedIn post introducing Reva and IBAC. The post id decodes to **2026-02-23**. It promised "sub-20ms" responses and said AWS was a technology partner | V-page, https://www.linkedin.com/posts/amitsaha_ibac-activity-7431680129577177088--1jo |
| Heritage | Before the agent pivot, Reva was a policy-governance tool for Amazon Verified Permissions (AVP) and Cedar: no-code policy authoring, an Access Explorer graph, and SCP/IAM governance | V-page, AWS Marketplace https://aws.amazon.com/marketplace/pp/prodview-wbbggg6agwnpm |
| AWS relationship | Listed on the AVP partner page as the "Policy Governance & Compliance" partner, one of six AVP partners (the others are CyberArk, Okta, Ping, Transmit and Strata). Reva claims to be the "only" governance partner for AVP and an AWS Qualified Software partner. Marketplace SaaS listing: contract pricing, 1-month option at $300/month per unit | V-page, https://aws.amazon.com/verified-permissions/partners/ ; https://www.reva.ai/solutions/aws-authorization ; Marketplace listing |
| Cedar | "Cedar ... is core to Reva. We use Cedar for authorization within our platform" (S04). The public adapters confirm a schema-checked Cedar store: entity types and actions are validated, and context records come only from an allowlist | V-page S04; V-code `pdp.mjs` comments |
| Funding / investors | **Not found.** Crunchbase returned 403. No press release was found | Open question |
| Customers | **None named** on any page reviewed | Open question |
| Advisors | Ken Huang (CEO, DistributedApps.ai; known for CSA/OWASP agentic work) is a disclosed Reva advisor and writes guest posts | V-page (disclosure in his posts) |
| Gartner | Reva borrows Gartner language (AI TRiSM, "Guardian Agents", "Authorization Management Platform"). I found **no evidence** Reva is a named vendor in the Feb 2026 Gartner Market Guide for Guardian Agents. PlainID and Delinea announced that they are | Search, https://www.plainid.com/newsroom/gartner-market-guide-guardian-agents/ |
| Patent | S01 says "patent-pending implementation of deterministic IBAC". **No application number is given.** Google Patents and Justia searches found nothing; Justia returned 403. A 2025–26 US filing may not be published yet (publication usually comes 18 months after filing) | CLAIM; open question |

### Product naming (inconsistent across sources)

- The website lists these components: **Policy Control Tower** (authoring, approval, versioning, certification), **Reva Trust Gateway** (runtime enforcement, HITL, CAEP/SSF signals), **Policy Intelligence** (Access Clipping, behavior risk, threat detection) and **Data Fabric** (identity/resource graph). Source: https://www.reva.ai/platform
- The code and Kong docs call the runtime service **"Reva Trust Guardian"**, abbreviated **RTG**. The evaluation endpoint is `POST /pdp/v2/ai/evaluation` on `api.reva.ai` (V-code, all three plugins). S05 calls Trust Guardian the component that monitors intent/behavior drift. The advisor post calls "Reva Trust Guardian" the "Tool Gateway".
- **INF:** "Trust Gateway" and "Trust Guardian" are one runtime PDP service (RTG) under two brand names. The Kong plugin's search aliases list both "reva-pdp" and "Reva Trust Guardian".
- The umbrella product name "Adaptive Access for Humans and AI Agents" (listed on S01) has no separate product page. The homepage tagline is "Continuous Adaptive Authorization for Humans and AI".

---

## 2. Q1: What exactly is "intent", where does it come from, and how is it carried across hops?

### 2.1 What Reva says

- **S01 (whitepaper):** intent is "the intent originally authorized by the user". IBAC asks at every hop whether the action is still aligned with it. The whitepaper never says how intent is captured, represented or authorized.
- **Technical primer (2026-04-02, unsigned):** https://www.reva.ai/blog/intent-based-access-control-a-technical-primer
  - The user or system *declares* an intent, in natural language or JSON.
  - An **intent parser** (a small LLM or supervised classifier) normalizes it to **pre-defined task templates**.
  - A **policy mapper** turns the template into FGA-style tuples (`principal:action#resource`) plus constraints (`max_rows`, `expiry`, `nonce`).
  - These are bound to a **short-lived signed token (15–30 min)**. Every tool call is checked against the tuple set.
  - A new-looking task requires a new intent.
  - This is a design essay with pseudocode, not a product spec.
- **ADV (Ken Huang, 2026-05-27):** https://www.reva.ai/blog/why-static-authorization-is-failing-in-the-age-of-ai-agents
  - The same four parts: Intent Parser → Policy Mapper → Authorization Engine → Tool Gateway (Trust Guardian). The Intent object has task, scope, allowed actions, target resources and constraints.
  - It also says Reva issues a short-lived signed **OAuth Transaction Token for Agents** (IETF draft) carrying the verified user, the agent chain and "the session's original declared intent". No claim names are given.
  - Separately, the "IBAC Judge" "extracts the original intent from the session's hop history".

### 2.2 What Reva's shipped code actually does (V-code)

The three public enforcement points (PEPs) all build the same Evaluation API v2 request. The **intent anchor is the human's own utterance**, recovered from the conversation. No declared or signed intent object appears anywhere in the wire contract.

**Kong plugin** (`documentation/index.md`, `plugin/handler.lua`):
- The **entry hop** of a turn is the first call seen for a given W3C `traceparent`. It is always evaluated with the **User** as subject, and its text is sent with role `user`.
- Every later hop is sent with role `assistant`/`agent`.
- Per `traceparent`, the plugin keeps `context.hops` (who invoked whom: seq, subject, action, resource, time) and `context.conversation.messages` (what was said on those hops).
- Per session id (`X-Reva-Session-Id` header, else A2A `contextId`, else `mcp-session-id`), it keeps `session.messages`: prior turns as request/response pairs.
- For MCP and A2A calls, prior chat history must be **supplied by the caller** in `params._meta.chatHistory`, `params.metadata.chatHistory`, or `params.message.metadata.chatHistory` / `params.history`. The docs warn that without it a refund is judged "with no run-up".

**Copilot Studio adapter** (`src/core/turn.mjs`, `src/core/pdp.mjs`, `src/core/hop-intent.mjs`):
- The anchor is `plannerContext.userMessage`, matched in chat history or synthesized if Copilot omits it.
- The current hop is sent as `transmission.content`, a **deterministic English sentence built from the tool name and argument names/values**. For example, "Send notification email to X with subject ...". Values are capped at 120 chars, the sentence at 600, and at most 4 arguments are used.
- The planner's own "thought" is optionally **appended after** this sentence (≤300 chars).
- The code comments state the key design lesson plainly. Sending the user's prompt as the hop content makes the payload agree with itself, so the verdict is always ALIGNED. Labelling the agent's action as role "user" has the same effect, because a user message **sets** intent rather than being measured against it.
- Only the **last 10 prior turns** are sent, with each message clamped to **2000 chars** by default. The RTG rejects bodies over **1 MiB**.

**Claude Code plugin** (`src/authorizePrompt.ts`, `src/context.ts`, `SECURITY.md`):
- `UserPromptSubmit` sends the **full prompt text** as an `invokeAgent` evaluation. This is the anchor and the base of the hop chain.
- Every later evaluation carries that prompt again as one conversation message.
- Tool calls send the verbatim shell command, the whole tool input (including file contents being written) and, after the call, the tool response truncated to 2000 chars.

### 2.3 How the "originally authorized intent" is captured and carried

- **Captured:** as raw natural language, namely the user's first message of the turn plus up to N prior turns. It is "authorized" only in the sense that the entry hop (`User invokeAgent Agent`) passed Cedar policy and guardrails. No structured intent, template match or user confirmation appears in any public contract. (V-code; INF for the "only in the sense" reading.)
- **Carried across hops:** **not in a token.** It is carried by
  - (a) PEP-side state keyed by `traceparent` and session id: the Kong nginx shared dict, which is **per node**; the Copilot adapter's rebuild from each webhook payload; the Claude plugin's local cache files. And
  - (b) the RTG's own decision log, keyed by the same ids. (V-code; server-side retention is INF.)
- **Transaction tokens carrying intent** are described only in the advisor post. They are **not visible** in any public PEP. The Kong demo passes the *same* user JWT on every hop, and the demo README says it is unsigned. So the claim is **unverified**.

**Net answer to Q1:** Reva's "intent" is *inferred and anchored* from the user's natural-language request in the conversation, not declared or structured. The structured "Intent object → FGA tuples → signed token" design exists only in blog essays.

---

## 3. Q2: How continuous intent evaluation works

| Element | What is known | Grade |
|---|---|---|
| Where it runs | Inside the RTG/PDP as a **guardrail**: a content evaluator configured in the Reva console, not in the PEP. It runs on the same evaluation call, and its verdict is folded into the one `decision` field. The decision log records "Evaluated Policies" and "Evaluated Guardrails" separately | V-code, Kong `documentation/index.md` §Guardrails |
| Guardrail types named | "An intent-drift check" that compares current activity with the user's originally approved intent and attributes how far it has diverged. Also "a jailbreak and prompt-injection detector". Each has its own score threshold and allow/deny verdict, and each can be set to observe-only | V-code, Kong docs |
| Algorithm (IBAC Judge) | (1) extract the original intent from the session's hop history; (2) compare the current hop's reason with it through **LLM drift analysis**; (3) emit a drift score from 0.0 (aligned) to 1.0 (drifted), threshold "typically 0.30"; (4) attribute drift across **Actor, Target, Value, Action and Scope**, each with a severity | ADV (2026-05-27) |
| Worked example | A $500 transfer to "John" becomes $50,000 to an external email after prompt injection. Drift 0.86; Value 0.96; Target 0.94 | ADV |
| Inputs actually sent | Current hop sentence (`transmission.content`), turn `conversation.messages`, `hops`, `session.messages` (prior turns), `inputValues` (raw args), principal/subject/resource, timestamp, source IP, environment enum, custom scalars | V-code, `pdp.mjs` |
| "semantic similarity, contextual reasoning, workflow state, execution history" (S01) | Only the LLM-judge path is described. No embedding or similarity method is published. "Workflow state" and "execution history" appear to correspond to the `hops`/`conversation`/`session` collections | S01 CLAIM; INF mapping |
| Trace-based policy testing | S04 proposes replaying proposed temporal policies against recorded or synthetic agent trajectories before deployment. It is described as an opportunity, not a shipped feature | V-page S04 |
| Temporal policy | Reva positions AWS Dogwood (Cedar with `formerly`, `count_within`, `sum_within` over event traces; Apache-2.0; AgentCore Policy integration) as complementary. Reva does not say it ships Dogwood | V-page S04; AWS blog 2026-08-06 https://aws.amazon.com/blogs/opensource/introducing-dogwood-runtime-verification-for-ai-agents/ |

Observations:
- **Guardrail results are opaque to the PEP.** The Copilot adapter's install guide says the client response carries only `{decision}`, with no score, chain or reason. Drift and injection verdicts are visible *only* in Reva's decision log (V-code, `docs/INSTALL.md` §8.4).
- The Copilot drift example payload shows the actual policy that fired. It was `blockedTermInUserMessage` (a deterministic term match on "confidential claims"), **not** the drift judge. INF: at least some of their demo "drift" blocks are keyword rules.
- Chain-aware scoring ("a hop cannot outscore its compromised ancestors", S01 Table 1, ASI08) has **no published mechanism**. The `hops` collection carries only subject, action, resource and time, with no per-hop score. So any monotonic cap would have to live server-side, keyed by `traceparent`. INF, unverified.

---

## 4. Q3: Behavior baselines

- **Claims:**
  - Human and non-human identities build baselines over time, and deviations become risk signals (S01 §6).
  - "Adaptive Trust Scoring" and "Behavioral Anomaly Detection" (https://www.reva.ai/solutions/ai-security).
  - "Continuous Behavioral Evaluation" of "actions, tool usage, delegation patterns, and execution flows", using "trained LLM-as-Judge and self-learning models" (https://www.reva.ai/platform).
  - MCP page: detects "anomalous tool invocation behavior, excessive access attempts, or agent goal drift" (https://www.reva.ai/solutions/mcp-server-security).
  - Blueprint example: an agent "suddenly requesting high volumes of data it has never touched before" (Amit Phadke, 2026-02-16, https://www.reva.ai/blog/the-blueprint-for-agentic-security-operationalizing-ai-governance-with-runtime-authorization).
- **Response actions claimed:** Access Clipping (automatic removal of unused privileges; "80% reduction in standing privileges", homepage), step-up approval, quarantine, revocation, and optional HITL.
- **Inputs:** CAEP/SSF (Shared Signals) risk signals ingested by the Trust Gateway (platform page).
- **What is NOT published:** features, window sizes, per-entity vs peer-group baselines, cold-start handling, the learning algorithm, false-positive rates, or how a baseline score enters a Cedar decision.
- **INF:** "Access Clipping" and entitlement-vs-usage analytics are a natural carry-over from the founders' Saviynt/IGA background. This is the most plausible "self-learning" component (usage-based right-sizing), rather than a per-request online anomaly model.
- **Grade:** CLAIM throughout.

---

## 5. Q4: Where models run, and how probabilistic outputs meet deterministic policy

### 5.1 Model placement
- S01: evaluations use LLM-as-Judge. Enterprises "may choose" **Reva-provided Small Language Models**, enriched through enterprise retrieval, organizational context and domain knowledge, "while maintaining control over sensitive data". CLAIM. No model names, sizes or hosting details are given.
- Platform page: "Run in your VPC, hybrid cloud, or fully on-prem" (CLAIM).
- **Shipped default is SaaS (V-code):**
  - The Claude Code plugin defaults to `api.reva.ai` and transmits full prompts, shell commands and file contents there. Its SECURITY.md says retention and logging are properties of the Reva tenant.
  - The Copilot adapter targets `api.<env>.reva.ai`.
  - The adapters themselves run in the customer environment, and the Copilot README says Reva does not operate them. But evaluation (the PDP plus guardrails) happens in the Reva tenant.
- **INF:** "on-prem" means a dedicated tenant deployment, not a local model on each PEP. That is the same data-residency trade-off as the WhiteSwan CEO's "light LLM in customer env" idea.

### 5.2 The "policy boundary"
- S01: the three questions (intent aligned? behavior consistent? chain unbroken?) are "necessarily probabilistic". Determinism comes from the business policy boundary, and intent may evolve within it but cannot cross it.
- ADV: the IBAC Judge sits on a deterministic Cedar/OPA "policy floor" that the LLM judge can only narrow within, never broaden.
- V-code consistency: guardrails can only add a deny (or a `conditional_allow` → ask) on top of a Cedar decision. Nothing in any PEP lets a guardrail turn a Cedar deny into an allow. The Kong docs say a guardrail denial looks the same as a policy denial at the gateway. This matches "narrow, never broaden". (V-code for the PEP view; the server-side combination logic is not public.)
- "A hop cannot outscore its compromised ancestors": this is S01 Table 1 only. It reads as a **monotonic trust score along the chain**, where a descendant's trust is capped by its least-trusted ancestor. No formula is published. CLAIM.

---

## 6. Q5: Enforcement architecture

### 6.1 PEP/PDP split (V-page S05 + V-code)
```
AI workload → native enforcement point (PEP) → Reva RTG (/pdp/v2/ai/evaluation) → Cedar + guardrails → allow / deny / conditional_allow(ask)
```
Policy authoring, approval, versioning and certification live in the Policy Control Tower. Enforcement is wherever the workload already has a hook.

### 6.2 Integrations
| PEP | Evidence | Notes |
|---|---|---|
| Kong AI Gateway (plugin, Lua, Kong ≥3.12, priority 1000) | V-code | Classifies `invokeModel` / `invokeTool` (MCP `tools/call` only) / `invokeAgent` (A2A `message/send` only) by path prefix or body sniffing. Fail-closed by default; `monitor_mode`; 5 s timeout, 2 retries. Needs a 64 MB nginx shared dict for hop and chat state |
| Microsoft Copilot Studio external threat-detection webhook | V-code | Microsoft allows about 1 s and **fails open** past it. Fires only for tool calls of *generative* agents. Principal is taken from `conversationMetadata.user.id`, not the Entra app token |
| Claude Code plugin (PreToolUse, PostToolUse, UserPromptSubmit, SessionStart/End) | V-code | Maps tools to actions `executeBash`, `read`, `write`, `edit`, `glob`, `grep`, `invokeTool`, `spawn`, `invokeAgent`. Repo-relative resource ids. Registers user, machine hardware id and HTTP MCP servers at session start. Explicitly excludes Desktop Chat/Cowork |
| LiteLLM, TrueFoundry | Code comment in `pdp.mjs` mentions the `REVA_HOOK_MODE` and `mode` settings of those plugins | Not independently found in LiteLLM/TrueFoundry docs (search, 2026-09-26) |
| Anthropic Inference Hooks | V-page S05 | Pre-inference request gate: identity, project, model, data, location/time/device, risk |
| AWS (AVP, AgentCore Policy, IAM/SCP governance) | V-page | Governance and control-plane integration, not a PEP for agents per se |
| SDK / webhooks, LangChain, n8n, Bedrock, Kubernetes | V-page (ai-security) | Named only |

### 6.3 Evaluation API v2 request (paraphrased from `pdp.mjs` and `types.ts`)
- `subject`: the entity acting now (Agent, or User on the entry hop).
- `principal`: always the originating human.
- `action`, `resource`.
- `context`: flat scalars and scalar arrays, plus records only from an allowlist (`conversation`, `hops`, `chatHistory`, `environment`, `onBehalfOf`); other records get a 400. Also `timestamp`, `sourceIp`, `environment` (PROD/STAGING/DEV/SANDBOX), and operator-added attributes prefixed with the store name.
- `transmission`: `{promptKey, role, contentType, content|userQuery, response}`.
- `inputValues`: raw arguments.
- `session`: `{id, turn, startedAt, messages[]}`.
- Optional request-local `entities`: a User with `UserGroup` parents taken from a JWT `groups` claim.
- Entity types and actions are schema-checked. Entity ids are free-form.
- The PDP enforces strict session invariants. It returns 400 if a prior turn has no response, if a response timestamp precedes its request, or if `session.turn` is below 2 while messages are present.

### 6.4 Outcomes
- **Allow / Deny:** HTTP 200 (allow) or 403 (deny), each with `decision`. Anything else is treated as "no decision" and blocked (V-code).
- **Defer / HITL:** a 200 whose body has `guardrails.outcome = "conditional_allow"` means synchronous guardrails want a human to confirm. The Claude Code plugin maps this to Claude's native **"ask"** prompt. The Kong plugin and Copilot adapter handle only a boolean. S04 describes richer HITL patterns (deny and retry after approval, hold while pending, suspend and resume asynchronously) as how enterprise workflows *can* work, not as shipped.
- **Fail modes (V-code):**
  - Kong and Copilot are fail-closed by default.
  - The Claude plugin fails closed on 5xx/404/424/timeouts. It fails **open** on 401 (latched for the whole session) and on 413 (payload too large).
  - Every PEP has a monitor ("would deny") mode.

### 6.5 Decision snapshot
- S01 claims every decision records identity chain-of-custody, intent evaluation, behavioral assessment, policy reasoning, risk signals and enforcement outcome. The stated purpose is EU AI Act, NIST AI RMF and ISO 42001 evidence.
- V-code confirms a server-side decision log that separates "Evaluated Policies" from "Evaluated Guardrails". Only a policy denial produces a row; plugin-side 401/400 refusals do not.
- The snapshot schema is not public; docs.reva.ai requires an access code.

### 6.6 Identity continuity in shipped PEPs (V-code; weakness analysis is INF)
- **Kong:**
  - The human is taken from `Authorization: Bearer` with the **signature not verified**. The docs tell you to put the jwt/OIDC plugin in front.
  - Agent identity defaults to the `X-Reva-Agent-Id` header, and the docs warn this is an assertion, not a credential. The alternative is the Kong Consumer.
  - `authorize_agent` defaults to **false**, so every hop is evaluated as the User.
  - In the demo app, the resource id for an agent is its Kong Service host URL.
- **Hop chain:** rebuilt per node from `traceparent`, which the caller supplies or the plugin mints when it is absent. There is no signed act chain, no token exchange and no per-hop down-scoped credential. The same bearer is forwarded on every hop.
- **Claude plugin:** the subagent lineage is a best-effort local file cache that binds spawns to agent ids first-in, first-out.

---

## 7. Q6: Performance and accuracy claims, substantiated vs marketing

| Claim | Where | Status |
|---|---|---|
| "p90 decision latency below 40 milliseconds for standard evaluation paths", with "progressive evaluation for more complex workflows" | S01 | CLAIM, no method |
| "Average Sub-40ms Decision Latency" for deterministic evaluation | https://www.reva.ai/platform | CLAIM. Note that average ≠ p90 |
| "P90 sub-10ms latency", 99.9% availability | https://www.reva.ai/ (homepage digest) | CLAIM, and conflicts with the above |
| "sub-20ms response times" | Launch post, 2026-02-23 | CLAIM |
| "Sub-40ms latency, above 98% drift detection accuracy" | ADV 2026-05-27 | CLAIM, no citation |
| **Measured: ~150–250 ms warm**, "guardrails deferred" (the default posture), against a live deployment | Reva's own `pdp.mjs` comment, `CHANGELOG.md` 0.2.0 and `INSTALL.md` (2026-09-10) | **V-code (vendor-measured, end-to-end through the adapter)** |
| **Measured: 2.6–3.0 s** with guardrails **inline in enforce mode** | Same `pdp.mjs` comment | **V-code** |
| Default PEP timeouts: Kong 5 s ×2 retries; Copilot 2.5 s; Claude Code 25 s | V-code | Timeouts this large suggest multi-second tails are expected |
| "98% accuracy in behavioral drift detection" from "internal testing" with self-learning models and LLM-as-Judge; platform page says "Up to 98%" | S01; platform | CLAIM. No dataset, base rate, precision/recall or false-positive rate |
| "billions of authorization decisions" (S01) vs "millions" (platform) | — | Inconsistent, CLAIM |
| "50% faster development", "80% fewer standing privileges", "90% fewer items to certify" | Homepage | CLAIM |

**Assessment:**
- The sub-40 ms figure most plausibly describes the **Cedar-only path inside the PDP**. The intent-drift judge is an LLM call that Reva's own engineers measured at seconds when run inline, and it is otherwise "deferred". **INF:** "deferred" most likely means it runs after, or alongside, the fast Cedar decision (observe or async), which would explain "progressive evaluation".
- So "real-time intent enforcement at under 40 ms" is **not substantiated**. The public evidence supports *either* fast deterministic enforcement *or* slow inline intent judgment, not both at once.
- "98%" is unfalsifiable as stated.

---

## 8. Q7: What is genuinely new vs repackaged

**Prior art and parallel work (dated):**
- **ibac.dev** (Jordan Potti, Mar 2026): https://ibac.dev/
  - An LLM parses the user request into OpenFGA tuples before execution. Every tool call is checked against the tuples, with TTL-conditioned tuples.
  - AgentDojo: 100% security in strict mode and 98.8% in permissive mode across 240 injection attempts; about 9 ms per check.
  - The same acronym ("Intent-Based Access Control") predates Reva's April primer and appears concurrent with Reva's Feb launch.
- **Reva's own primer** (2026-04-02) reproduces the parse → tuples → signed short-lived token → gate pattern, and cites no prior work.
- **AWS Dogwood** (2026-08-06): temporal Cedar over event traces. Reva positions itself as complementary.
- **IETF OAuth Transaction Tokens for Agents** (draft), AuthZEN, CAEP/SSF, SPIFFE: standards Reva adopts, not invents.
- Older patents titled "intent-based access control" and "behavioral based intention detection" exist (USPTO results 9703952 and 10559145 surfaced in search). **I did not read them.** They are a warning that the IBAC *name* and broad concept are not novel and may limit the scope of any Reva patent.
- LLM-as-judge for drift, and "guardian agents" (Gartner, Feb 2026), are industry-wide.

**What appears genuinely distinctive (still modest):**
1. **Composition:** a **real Cedar PDP with the LLM judge as a narrowing guardrail** in one call, with allow/deny/conditional-allow outputs and separate audit of policy vs guardrail. This is a clean, defensible architecture pattern.
2. **Five-dimension drift attribution** (Actor/Target/Value/Action/Scope) with per-dimension severity. This is useful for explainability, if real (ADV only).
3. **Engineering lessons in the PEPs:**
   - hop-description sentences generated deterministically from tool name and argument shapes, so the hop is not compared with itself;
   - role discipline, where only the human's utterance sets intent;
   - replacing their own block messages in history so a blocked term cannot wedge the chat;
   - fail-closed defaults and monitor mode.
4. **Breadth of enforcement points** (Kong, Copilot Studio, Claude Code, inference hooks) behind one API and one policy store. This is a go-to-market strength.

**Repackaged or unsubstantiated:** "deterministic IBAC" is policy-as-code plus an LLM guardrail. The behavior baselines, SLMs, chain-aware scoring and transaction-token intent propagation are claims without public mechanism.

---

## 9. Q8: Strengths and weaknesses vs an inline gateway like WAAG

### 9.1 Where Reva is stronger today
- **Intent exists at all.** Reva has a working drift guardrail and a user-utterance anchor. WAAG has **no purpose signal**: the human's words never reach the gateway on the console path, and the PDP sees only `argumentsFlat` on A2A (grounding §13(a), 13(g)).
- **Real Cedar with a schema.** WAAG's PDP is a regex Cedar-like subset with no OR/NOT that silently ignores some head forms (grounding §6.2, §13(b)).
- **Three-valued outcome** (allow/deny/conditional_allow → ask). WAAG's engine outputs ALLOW/DENY only (§13(b)).
- **Conversation and history context** sent to the PDP: turn hops, prior turns, raw args. WAAG's PDP consults no history, and the parent `corr_id` is never read (§13(f)).
- **Enforcement-point breadth and a rollout story** (monitor mode per PEP), plus the AWS/AVP channel and a founder team with an IGA pedigree.

### 9.2 Where Reva is weaker (and WAAG can win)
1. **Identity continuity and lineage.**
   - Reva's shipped chain is reconstructed from caller-supplied `traceparent`, unverified JWTs and header agent ids, stored per Kong node.
   - WAAG mints a **per-hop 120 s OBO with a verified, human-rooted `act_chain`** and a `cnf` sender constraint, signed by the gateway (grounding §5.8–5.9). That is a concrete answer to ASI03/ASI08 where Reva offers a claim.
2. **Intent-anchor integrity (INF from reading the Kong code, not tested).**
   - In Reva's Kong model, the entry hop (role `user`, the intent-*setting* message) is simply the first call seen for a `traceparent`. A downstream agent that drops or re-mints `traceparent` could start a fresh "turn". Its own text would then be treated as the user's intent.
   - Caller-supplied `chatHistory` in `_meta`/`metadata` is also self-asserted.
   - WAAG can bind the anchor to **gateway-held state keyed by the signed `trace_id`/`corr_id` in the OBO**, so an agent cannot reset or forge the anchor.
3. **Latency.**
   - WAAG's governance overhead is ~12–13 ms p50 per hop, in-process (§13(d)).
   - Reva adds a network round trip to a SaaS PDP (~150–250 ms warm per their own measurement), and 2.6–3.0 s with the inline judge.
   - An inline gateway can run cheap deterministic intent checks synchronously and reserve model calls for the uncertain slice.
4. **Data exposure.** Reva's default is to ship prompts, commands and file contents to `api.reva.ai`. A gateway-resident evaluator (the CEO's local-model idea) is a real differentiator for regulated buyers, **if** it is actually local.
5. **Downstream credential scoping.** Reva authorizes but forwards the same bearer. WAAG scopes each hop's token to exactly one capability (for A2A on the wire).
6. **Opaque verdicts.** Reva's PEP gets only a boolean, and drift scores live only in its console. WAAG owns the whole path and can return a structured reason, an obligation and the audit row in-line.

### 9.3 What WAAG should adopt (concrete, mapped to our seams)
| Adopt | Why | WAAG seam (grounding §13(c)) |
|---|---|---|
| **Deterministic "hop sentence" from structured args + descriptor**, never the LLM's own message and never the user's prompt | Reva's hard-won lesson: a hop compared with itself is always ALIGNED. For MCP we already have `descriptor` (description, inputSchema) + full args in scope | `HopOrchestrator` between registry lookup and `buildFor*` |
| **Anchor = verified root request, set once, immutable per trace** | Their anchor is the user's utterance. Ours should be the human's request captured at the console/first hop and **bound to the signed trace**, not to headers | New: capture at first hop; store keyed by `trace_id`; look it up via the OBO `trace_id`/`corr_id` (the parent entry already sits in `InFlightRequestRegistry`, and nothing reads it today) |
| **Third outcome: ASK/DEFER (conditional allow)** | HITL as part of authorization, not a deny | PDP result type (today ALLOW/DENY only); A2A/MCP error mapping |
| **Guardrail can only narrow policy** (monotone combination) | Keeps determinism and auditability | Evaluate intent *after* the PDP ALLOW, before mint; its result may downgrade to ASK/DENY only |
| **Separate audit of policy verdict vs intent/guardrail verdict, with per-dimension drift attribution** (Actor/Target/Value/Action/Scope) | Explainability for CISO/auditor, our D3 strength | `pdp_audit_log` + new intent columns |
| **Monitor mode per rule/guardrail** | Safe rollout. Our egress classifier is already observe-only | Config flag on intent evaluator |
| **Replace our own block messages in history** | Stops a blocked term from wedging later turns | Any history we feed an evaluator |
| **Chain-aware monotone scoring**: a child's trust ≤ min(ancestors) | Answers ASI08. We *can* implement it because we hold the signed chain | Carry an intent/risk verdict per `corr_id`; the child reads the parent's verdict via `corr_id` |

### 9.4 Where not to follow Reva
- Do not ship a PDP-side LLM judge on every hop inline. Reva's own numbers show seconds. Use deterministic checks first (structured arg vs anchor entities, value/target deltas, capability ↔ task allow-lists), a small local model for the ambiguous remainder, and ASK for the rest. This is the Jev CEO's "smallest primitive per decision" point.
- Do not rely on caller-supplied history or trace headers for intent context.
- Do not publish unsubstantiated accuracy or latency numbers. Buyers (Netskope, Zscaler) will probe them. Publish our method: dataset, precision/recall and false-positive rate on our demo scenarios.

---

## 10. Relevance to WhiteSwan (summary)

- Reva is the closest direct competitor in *naming* ("IBAC") and *pitch* (intent + behavior + deterministic policy + multi-hop). Expect buyers to ask "how are you different from Reva?"
- Our honest differentiated answer is **verifiable lineage plus a tamper-proof intent anchor plus inline speed**. Their public enforcement code shows unverified tokens, header identities and `traceparent`-keyed chains, which undercuts their "identity chain of custody" message.
- Their demo (`demo-ai-app`: orchestrator → A2A specialists → MCP tools through Kong) is structurally the same as our financial demo. A side-by-side "prompt-injected specialist" scenario would show our strengths directly.
- Their weakest documented point, the inline-judge latency of 2.6–3.0 s, is where a gateway-resident deterministic-first design wins.
- Prerequisites on our side still apply (product brief §12): the regex PDP that widens grants and the A2A door-gate gaps must be fixed before layering intent, or the "policy floor" we would claim is not real.

---

## 11. Open questions

1. What is Reva's funding, investor list and named customer base? Crunchbase was blocked and no press was found.
2. Is there a published patent application for "deterministic IBAC"? What are its number, filing date and claims?
3. What model runs the drift guardrail (hosted LLM, Reva SLM, customer choice)? Where is it hosted for the SaaS tenant?
4. What exactly does "guardrails deferred" mean in the default posture: async/observe after the decision, or skipped? Is drift ever enforced synchronously in production?
5. How is "a hop cannot outscore its compromised ancestors" computed? Is a per-hop score persisted server-side, and keyed by what?
6. Does any Reva PEP or SDK actually issue OAuth Transaction Tokens carrying intent, as the advisor post claims? With which claims?
7. What signals, windows and algorithms make up the "self-learning" behavior baselines? What are the false-positive rates?
8. What dataset, base rate and metric underlie "98% drift detection accuracy"?
9. Are the Structured Intent object and templates (from the primer) shipped anywhere, or only essays?
10. How does the RTG handle multi-node Kong, where the per-node shared dict splits a turn's hops across nodes?
11. Could a downstream agent actually reset the entry hop by re-minting `traceparent` in a live Reva deployment? Our reading of the Kong code suggests yes, but it is untested.
12. What does docs.reva.ai (access code required) say about the decision-snapshot schema and guardrail configuration?
