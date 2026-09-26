# WhiteSwan Agentic Auth Gateway: Product & Technical Brief

| Item | Value |
|---|---|
| Date | 2026-09-26 |
| Product | WhiteSwan Agentic Auth Gateway ("WAAG"; customer-facing name "WhiteSwan Agentic Gateway") |
| Code in scope | `/Users/amitprakash/Desktop/WS Apps/AzureAdWsIntegration` (gateway), `ws-gateway-dashboard` (admin UI), `ws-agentic-console` (chat front door), `a2a-sample-agents` (reference agents). The prod repo `backend` is **out of scope** and was not read. |
| Audience | The senior tech + product owner of WAAG, and future AI sessions that must understand the whole application quickly. |
| Code-level depth | `docs/others/gateway-grounding.md` (1257 lines, verified and cited code facts, dated 2026-09-25, hand-rechecked 2026-09-26). It is the ground truth for "what the code does today". This brief is the product layer on top of it. |

### Sources used

| Key | Source |
|---|---|
| **GG §x / GG:n** | `AzureAdWsIntegration/docs/others/gateway-grounding.md`, section x or line n |
| **PRD:n** | `/Users/amitprakash/Desktop/WS Apps/Agentic-Gateway-PRD.md` (written 2026-07-14) |
| **BP:n** | `/Users/amitprakash/Desktop/2026/Files/LLM_Security_Gateway_Product_Blueprint.md` (April 2026 platform blueprint, written for the LLM Gateway) |
| **PLAN:n** | `repo:docs/features/agentic-gateway-build-plan.md` |
| **REF:n** | `repo:docs/features/policy-engine-reference.md` |
| **PPP:n** | `repo:docs/features/post-processor-egress-governance-plan.md` |
| **PDF p.N** | `/Users/amitprakash/Downloads/WhiteswanDocumentation.pdf` (company platform doc v1.1, 89 pages) |
| **MEM/<file>** | `/Users/amitprakash/.claude/projects/-Users-amitprakash/memory/<file>` |
| **T-A** | transcript `-Users-amitprakash-Desktop-WS-Apps-wsAgenticSecurity/d8b3dde8-f9ef-497e-80d2-a7c6791867ad.jsonl` (main build session, 2026-02-09 to 08-29) |
| **T-C** | transcript `…wsAgenticSecurity/c7e5880b-151d-41bd-9ad6-3b93f28d39e8.jsonl` (2026-08-19 to 09-16: launch, Netskope, Zscaler) |
| **T-D** | transcript `…wsAgenticSecurity/23c3a8c8-4de6-4a56-ab48-1341df684c44.jsonl` (Secure OS / Varden, April) |
| **T-B** | transcript `-Users-amitprakash-Desktop-WS-Apps-backend/dd87b823-8aa4-4494-87a0-1b99e0a02bfe.jsonl` |
| `SRC/…` | `src/main/java/com/ws/wsAgenticSecurityGateway/…` |
| git | `git log` of the gateway repo (commit short hashes) |

### How to read this brief
- A sentence with a cite is a **FACT**. Transcript cites give file key, date and a quote of at most 15 words.
- A sentence starting **Judgment:** is the author's assessment, not a cited fact.
- Where the PRD, docs or pitch disagree with the code, both are given and the brief says which is true today. The full list is in the Appendix.
- Vocabulary guardrails used everywhere below:
  - The policy engine is called **"the Cedar-like engine (in-house regex subset)"**. It is not the Cedar library (GG §6.1; `repo:pom.xml:324-326`).
  - **SPIFFE, proof-of-possession (DPoP/mTLS) and egress enforcement are NOT shipped.** SPIFFE is a seam, sender-constraint is a bearer-level `cnf` check, egress is observe-only.

---

## 0. The product in one paragraph

An agentic auth gateway that facilitates single- and multi-hop orchestration for AI agents; every hop gets its own short-lived token, scoped to exactly the one task that hop must do, nothing more. Concretely, WAAG is one inline Spring Boot service (port 9492) with two protocol faces: **MCP** for agent-to-tool calls (`/mcp`, `/stateless/mcp`) and **A2A** for agent-to-agent calls (`POST /a2a`, `message/send`) (GG §1, GG:45-48). Every call becomes a protocol-neutral **hop** that runs through one governance spine, `HopOrchestrator`: identify the caller, extend a delegation chain (`act_chain`) rooted at the human who started the work, ask a default-deny policy engine, mint a 120-second token whose scope is exactly one capability (`<protocol>:<type>:<server>:<name>`), forward the call, and audit it (GG:50-62; `SRC/sts/service/HopTokenMinter.java:33`). The PRD's promise is "No SDK, no code change in the agents" (PRD:3). The live financial demo runs a 4-deep governed chain, human → console → advisor → market-data → Alpha Vantage MCP, with a fresh token and a policy decision at every hop (GG:315-321; MEM/financial-scenario-demo.md:13).

**Judgment: the one sentence for a senior owner.** WAAG is the identity-and-authorization checkpoint for agent traffic: when a human's task fans out across agents and tools, each hop gets a narrow, short-lived permission that is provably tied back to that human, checked against policy and recorded. The core mechanics are real. The envelope around them (policy correctness, admin security, tenant isolation, multi-instance state, SIEM) is not yet enterprise-grade.

**Three honesty qualifiers that must travel with that paragraph:**
1. The per-hop token is on the wire **only for A2A**. On MCP it is minted and audited, but the downstream MCP server still receives the gateway's stored (encrypted) static credential (GG §5.8, GG:485; GG §7.6, GG:778).
2. Only human-delegated (OBO) chains have ever run live. No NHI-rooted (autonomous) chain has occurred (GG:503; MEM/waag-obo-only-no-autonomous-yet.md).
3. "No code change" holds for tool servers. Agents must still forward the inbound OBO and send `X-Agent-Assertion` for full lineage and the sender constraint (GG:1008; `a2a-sample-agents/agent_identity.py:78-89`).

---

## 1. The problem and why now

### 1.1 The problem, in customer terms
Real agent systems are multi-hop: "An agent calls another agent, which calls another, which finally calls a tool." They run on credentials that are long-lived, shared, over-privileged, pulled from a vault, and carry no record of who triggered the work (PRD:16-20).

| Today, without WAAG (PRD:41-49) | With WAAG (as promised) | True today? |
|---|---|---|
| Long-lived tokens | Short-lived tokens, minted per hop | Yes: 120 s, per hop (HopTokenMinter.java:33) |
| Shared service accounts, vaulted credentials | Per-agent identity, just-in-time scoped tokens | Partial: per-agent identity yes; MCP downstream still uses a static brokered credential (GG:778) |
| Broad standing privilege | Least privilege scoped to the exact action | Partial: scope label is per capability, but nothing consumes it downstream on MCP, and no parent→child down-scoping (GG:326) |
| "Bot X did it", no attribution | Actor chain user → agent → agent → tool | Yes, for human-delegated chains (GG §5.9) |
| No policy check per call | Policy on every hop | Yes on the governed doors; one admin bypass exists (GG:777) |

**Judgment: the CISO's problem in one sentence.** "When an agent does something, I can't tell which human asked for it, I can't limit it to just that task, I can't stop it mid-chain, and I can't prove any of it to an auditor."

### 1.2 Why now
- **Agents are chaining.** Multi-hop agent-to-agent orchestration is replacing single tool calls (PRD:16-20).
- **RPA vendors are becoming agent platforms.** The CEO's first customer signal (2026-04-09): Automation Anywhere was "looking for solution which can do agent authentication and authorization" (T-B, 2026-04-09).
- **Protocols are moving.** MCP is dropping sessions; its removal window "opens July 2027" (PLAN:46). A2A adoption itself "creates the market" (T-A, 2026-07-25).
- **Regulation and audit.** EU AI Act enforcement in August 2026 and SOC 2 / HIPAA auditors asking for AI audit trails (BP:49, BP:235; written for the LLM gateway but it applies).
- **Precedent.** Uber publicly solved agent auth with an STS, per-hop audience-scoped tokens, an actor chain, SPIFFE and an MCP gateway, **SDK-first, for in-house trusted agents** (MEM/uber-agentic-auth-precedent.md:13). WAAG's bet: enterprises "don't own or trust every agent", so a drop-in gateway beats a mandated SDK (ibid.:15).
- **Pitch framing.** An incident story was used: an agent found a token in an unrelated config and deleted a production database, with "no scope check" and "no audit trail" (T-B, 2026-06-12). **Unverified** whether the incident happened; do not repeat it as fact.

---

## 2. Who it is for, and the platform it belongs to

### 2.1 Segments
- **PRD primary target:** enterprises running agent/automation platforms "kore.ai, Automation Anywhere, UiPath" whose fleets rely on shared service accounts (PRD:37).
- **Owner's sellable segment:** for shops orchestrating agents in-memory, "we cannot much sell the gateway"; distributed systems "like Uber" are "what the customer is" (T-A, 2026-07-25). The assistant's counterpoint in the same exchange was that in-process shops still need MCP/tool governance.
- **Channel stance:** "AA and Korea.ai gonna be my partners or customers" (T-A, 2026-07-25). Kore.ai and AA named as "early customers" (T-A, 2026-08-03).
- **Where the effort actually went (Aug–Sep 2026):** security vendors. Netskope (Agentic NHI RFI, technical session; "the final stage our deal with Netskope", T-C 2026-08-29), Zscaler (call prep, T-C 2026-09-11), Obsidian (joint-data question, T-C). Customer-facing gateway PDFs were built "for a live Netskope deal" (MEM/gateway-enterprise-doc-build.md:11).
- **No production customers yet:** "even the app i have in production doesnt have any customer" (T-A, 2026-07-24); again "as of now as there's no customer" (T-A, 2026-08-09).
- **Blueprint ICP (platform level):** financial services, healthcare, enterprise SaaS first; 200+ employees, existing CISO team, compliance-bound (BP:347-365).

### 2.2 Buyers, users, personas

| Role | Who | What they touch today (fact) |
|---|---|---|
| Economic buyer | CISO / CTO / Head of Security (BP:30-33) | CISO dashboard: posture, accountability, blast radius, priority actions (GG §9.6) |
| Secondary buyer | Compliance / audit; CFO in the blueprint (BP:33) | SOC 2 and SOX evidence packs with CSV export (GG:920) |
| Partner / OEM buyer | Agent platforms: Kore.ai, AA, UiPath (PRD:37) | Only a seam: `AgentSource` interface with one `SelfDescribeAgentSource` (`SRC/protocol/a2a/source/AgentSource.java:10-16`) |
| Operator / admin | Security or platform admin | 14-page admin dashboard (`ws-gateway-dashboard/index.html:31-103`) |
| Integrator | Agent developer | Must route through the gateway, forward the OBO, send `X-Agent-Assertion` (GG §12.2; `a2a-sample-agents/README.md:50-58`) |
| Delegating human | End user of an agent front door | Signs in via IdP; gateway roots every chain at them. Demo front door: `ws-agentic-console` (GG §12.1) |

**Judgment on the buyer.** The PRD names platforms, the reporting surfaces (CISO, compliance) target the security buyer, and live deal effort went to security vendors that look more like partners or acquirers. Who pays (platform vendor embedding WAAG, enterprise CISO deploying it in front of platforms, or a strategic vendor) is written down nowhere. See §13.

### 2.3 The WhiteSwan platform context
- **Company platform doc (v1.1)** lists "Agentic Security" as a domain next to PAM/JIT, AD ITDR, Cloud/K8s and NHI & Secrets (PDF p.1-3). It defers the MCP/Agentic Gateway to a "dedicated product guide" (PDF p.3). Its shipped agentic features are **endpoint-side** coding-agent activity monitoring (Claude Code, Copilot CLI) (PDF p.42-43, p.60). The platform UI has an "Agentic Gateway" nav entry marked NEW (PDF p.60).
- **Adjacent platform assets not wired to the gateway:** an OpenBao-backed Vault issuing short-lived scoped JWTs to machine clients (PDF p.81-83); SIEM CEF/Syslog forwarding (PDF p.74); BYOK LLM setup (PDF p.88). **Judgment:** these are the natural homes for PRD G2/G3 (JIT credentials, vault) and H4/H5 (export).
- **The April blueprint's vision:** Varden (MCP gateway, "built"), Vigil (discovery), a separate A2A Gateway and an LLM Gateway sharing "one Cedar policy engine", one console, one identity model, unified audit into Vigil (BP:5-11, BP:266-278).
- **Reality:** A2A was folded into the same gateway (PRD:26-29; GG §1); the engine is not Cedar (GG §6.1); no LLM gateway, no Vigil integration and no SIEM export exist in this repo (GG §9.7); the admin plane is its own unauthenticated dashboard, not a shared console (GG §12.3). On 2026-05-22 the owner said "varden, secure os etc doesn;t exist for us" (T-A, 2026-05-22).

### 2.4 Names over time
| When | Name | Source |
|---|---|---|
| 2026-02-09 | "WS (whiteswan) MCP … Gateway" | T-A, 2026-02-09 |
| Apr 2026 | "Varden", first module of the "Secure OS" platform | T-A 2026-04-16; T-D 2026-04-17 |
| 2026-05-22 | Varden / Secure OS dropped | T-A, 2026-05-22 |
| 2026-06-12 | "WS agentic auth orchestration", pitched SDK-first | T-B, 2026-06-12 |
| 2026-07-21+ | "Agentic Auth Gateway" (PLAN:1); PRD title "Agentic Gateway" (PRD:1); acronym WAAG from 07-27 | PLAN:1; T-A |
| Aug–Sep 2026 | "WhiteSwan Agentic Gateway" (customer-facing) | MEM/gateway-enterprise-doc-build.md:26 |
| Still in code | Dashboard "WS Agentic Security Gateway - Admin Dashboard"; MCP server name "ws-mcp-gateway"; console branded "Kore.ai Agent Console V1" | `ws-gateway-dashboard/index.html:6`; GG §3.1; `ws-agentic-console/branding.json` |

---

## 3. Product principles (the invariants the product promises)

Each stated principle, then what holds today.

| # | Principle (source) | Holds today? | Evidence |
|---|---|---|---|
| P1 | **SDK-less:** agents import nothing (PRD:31) | Mostly | True for routing and identity. Agents still cooperate on OBO forwarding and assertions (GG:1008). |
| P2 | **Inline, un-bypassable chokepoint:** terminate inbound, re-originate outbound (PRD:77) | **No** | `POST /api/mcp/servers/{s}/tools/{t}` (Playground) calls tools with no PDP or profile check (GG:777). `/a2a` and `/stateless/mcp` skip the `/mcp` door filter (GG:86-88). |
| P3 | **The gateway is the only minter** (PRD:78) | Yes | GG §5.8 |
| P4 | **Stateless per hop:** state rides in the token; any instance serves any hop (PRD:76, :178) | Partial | Lineage rides in the token. Revocation, keys, policies and caches are per-JVM (GG §14 item 24). Never run multi-instance. |
| P5 | **Fail-closed everywhere** (PRD:118, :177) | Partial | PDP errors and revoked-session mints fail closed. Fail-open spots: null tenant skips mint (GG:462); empty chain minted with `sub="unknown"` (GG:467); unresolved agent skips profile gate (GG:758); dropped policy fragments widen permits (GG:563); auth mode `none` before app-ready (GG:362-363). |
| P6 | **Default-deny** (PRD:103, :132) | In the engine, yes | No permit / no policies / error → DENY (GG §6.3). In the live demo tenant the one broad permit is wider than it reads (§5.3). |
| P7 | **Attribute-based policies only, no per-agent rules** (PRD:133) | Not how it's used | Live policies are per-agent (`principal == Agent::"agent-console"`) (GG §6.9). Capability profiles are per-agent. |
| P8 | **Monotonic down-scoping:** a hop never exceeds its parent (PRD:116) | **No** | Invariants check chain *structure* only; parent `scope` is never read (`SRC/sts/model/OboInvariants.java:29-35, 93-104`; GG:326). |
| P9 | **Protocol-neutral spine:** it speaks only `Hop`/`MintedToken`/`Response` (PLAN:24-28) | Largely | GG §1. Code is duplicated across 4 near-identical leg methods (GG:1063). |
| P10 | **Anything that can block is deterministic and fail-closed; LLM signals never block** (post-processor plan, GG §8.5) | Partial | Deterministic, yes: no LLM on the request path (GG:969). Fail-closed, no: the profile gate can block yet fails open for unresolved agents (GG:758), plus the other fail-open spots in P5. GG:846 notes the principle is "the plan's stated position, not code". |
| P11 | **IdP-neutral:** Entra, Okta, Keycloak (PRD:108) | Partial | Multi-issuer decoder exists; one global IdP decoder serves all tenants; only Keycloak exercised live (GG §5.1-5.2). |
| P12 | **Honest outward text:** "credibly bold", no fabricated metrics (MEM/gateway-enterprise-doc-build.md:25); no internal identifiers in user-facing API text (MEM/user-facing-text-no-internal-identifiers.md); never rename a wire field an external consumer reads (MEM/obo-actor-workload-identity.md) | Rule in force | See Appendix for places the pitch has outrun the code. |

**Judgment:** P3, P6 (in the engine), P9 and the deterministic half of P10 are real design strengths. P2, P5 and P8 are the promises a technical evaluator will test first, and today they fail.

---

## 4. How it works: single hop and multi-hop

### 4.1 The mechanisms, one line each
| Mechanism | What it is (product language) | Cite |
|---|---|---|
| **Hop** | One governed call through the gateway. A user task becomes a tree of hops. | GG glossary |
| **Spine (`HopOrchestrator`)** | One pipeline every hop runs through, whatever the protocol. | GG:50-62 |
| **Protocol adapters** | `McpAdapter`, `A2aAdapter` behind `ProtocolAdapter`; they translate wire formats only. | GG §2 |
| **Caller identity** | IdP JWT (validated via JWKS) → classified human-delegated vs automated; agent identity from the verified `azp`/`client_id`, not the self-declared name. | GG §5.2-5.4; MEM/console-identity-verified-azp.md |
| **Agent registry + approval** | Agents, humans and NHIs discovered on first contact start PENDING; admin approves, blocks or deprovisions. | GG §7.1-7.2 |
| **Capability profile** | Per-agent allow-list of tools/prompts/resources/skills; also filters what `tools/list` shows the agent's LLM. | GG §7.5 |
| **PDP** | Default-deny Cedar-like engine (in-house regex subset) evaluated per hop with the chain context (root type, root verified, actor). | GG §6 |
| **Connectivity gate** | Don't mint a token for a call that can't be delivered. | MEM/hop-pipeline-order-empty-box.md |
| **STS mint (OBO)** | RS256 token, 120 s TTL, `aud` = target, `scope` = one capability, `act_chain` + RFC 8693 `act`, `trace_id`/`corr_id`; per-tenant keys. | GG §5.8 |
| **`act_chain`** | Root-first delegation lineage (human or NHI root, then agents), append-only, integrity-checked. | GG §5.9 |
| **Sender constraint (`cnf.workload_id`)** | On A2A tokens: the presenter must prove it is the intended agent via `X-Agent-Assertion`, else -33016. Bearer-level, **not** proof-of-possession. | GG §5.5 |
| **Credential brokering** | Downstream MCP secrets are AES-GCM encrypted at rest and injected by the gateway; agents never hold them. Static, not JIT. | GG §7.6 |
| **Revocation** | Kill a session or revoke a token (`jti`). Per JVM; coverage differs per door. | GG §5.10 |
| **Audit** | Two ledgers: `gateway_audit_log` (timeline) and `pdp_audit_log` (authoritative decision ledger with deciding policy and reason), joined by correlation/trace id; async. | GG §9.1-9.2; MEM/pdp-decision-audit-attribution.md |
| **Egress post-processor** | Async classification of responses (PII, PCI, secrets, financial, prompt-injection phrase). **Observe-only.** | GG §8 |

### 4.2 Single hop (agent → MCP tool)
1. An agent (e.g. Claude Desktop, or a sample agent) calls `tools/call` on `/mcp` with an IdP bearer token.
2. **Door filter** (only on `/mcp`): authenticate, classify token, resolve tenant, register or look up the agent/human/NHI, enforce PENDING/BLOCKED (GG:174-186).
3. **Spine:** capability profile gate → registry lookup → build `act_chain` [human, agent] → PDP → connectivity → mint the hop token (GG:50-62; order per MEM/hop-pipeline-order-empty-box.md).
4. **Dispatch:** `McpAdapter` calls the real MCP server with the server's stored credential. The minted token stays inside the gateway as an audit/enforcement artifact (GG:485, :778; locked decision `repo:docs/others/stage-1-plan.md:15`).
5. **Audit + egress** asynchronously (GG §9.2, §8).
6. No rejection is a 403. Door-filter rejections come back as a JSON-RPC error inside HTTP 200 (GG:181). A PDP deny on `tools/call` is a normal result with `isError=true` and "[-33003] … Policy violation" (GG:205). Prompt/resource failures are thrown as JSON-RPC errors (GG:236).

Live proof: Stage 1 verified 2026-07-22 with scope `mcp:tool:github:github_get_me` and 120 s TTL (MEM/agentic-gateway-staging-roadmap.md:14).

### 4.3 Multi-hop (human → agent → agent → tool)
1. The human signs in to the console (Keycloak OIDC + PKCE) and asks a question (`ws-agentic-console/src/server.js:126-167`).
2. **Hop 1 (A2A):** console → gateway `/a2a` → advisor. The gateway mints an OBO with `aud=advisor`, scope `a2a:skill:advisor:advisor.analyze` (format `<protocol>:<type>:<server>:<publicName>`, and an A2A skill's publicName is `<agent>.<skillId>`; GG:280, :294, :479; `SRC/sts/service/ScopeDeriver.java:18-24`), `cnf.workload_id=advisor`, `act_chain=[human, agent-console]`, and sends it to the advisor as `Authorization: Bearer` (GG:303, :319).
3. **Hop 2..n (A2A):** the advisor forwards that OBO and adds its own `X-Agent-Assertion`. The gateway validates its own token (it trusts itself on the return leg via `MultiIssuerJwtDecoder`/`StsJwtDecoder`), checks `cnf`, appends the advisor to the chain, re-runs profile + PDP, and mints a fresh token whose audience is the next target. It is not narrower: the parent scope is never read (GG:326) (commit `851d692`; GG §4.6).
4. **Leaf (MCP):** the specialist calls the Alpha Vantage tool on `/mcp`. Chain depth reaches 4 (GG:315-321).
5. **Why each token is "for that one task":** audience = the next target only, scope = one capability, TTL = 120 s, `cnf` binds it to the recipient agent.
6. **What is not there yet:** no check that a child's authority is narrower than its parent's (P8), no deadline propagation (each A2A level has its own 120 s timeout, GG:329), an A2A deny returns as a FAILED Task in HTTP 200 (GG:310).

**Inline proxy, not token-return.** The gateway calls the next agent itself; the token-return model was rejected (PLAN:30-34). **Judgment:** the build plan's phrase "no stealable token in the agent's hands" (PLAN:34) is too strong: downstream agents do receive a 120 s OBO and re-present it; theft is mitigated by `cnf`, not prevented.

### 4.4 Measured cost
Governance adds ~12–13 ms p50 per hop; PDP p50 0 ms / p99 1 ms; downstream A2A skill p50 6.9 s, MCP tool p50 1.4 s (26 demo journeys, single user) (GG §13(d), GG:1071-1081). Everything is synchronous and blocking; ~33 concurrent demo journeys would exhaust Tomcat's 200 workers (inferred, GG:1082-1086).

---

## 5. Capability map with honest maturity

Labels: **SHIPPED** (works end to end) · **PARTIAL** (works with named gaps) · **PLANNED** (not built; maybe a seam) · **OVERCLAIMED** (docs/pitch say more than code does).

### 5.1 Capability map

| Domain | Capability | Customer value | Maturity | Key gap (cite) |
|---|---|---|---|---|
| Tokens | Per-hop OBO mint (RS256, 120 s, aud, one-capability scope) | No standing shared credential per hop | **SHIPPED** | TTL hard-coded, not configurable (HopTokenMinter.java:33) |
| Tokens | `act_chain` lineage + RFC 8693 `act` | Every action attributable to the human | **SHIPPED** (human-rooted) | No per-entry timestamps (GG:488); `identity_source` always `KEYCLOAK` (GG:426) |
| Tokens | Token on the downstream wire | Downstream can verify who acts for whom | **PARTIAL** | A2A only; MCP servers never see it (GG:485, :778) |
| Tokens | Fail-closed mint | Unsafe hop doesn't run | **PARTIAL** | Null tenant skips mint; empty chain minted as `sub="unknown"` (GG:462, :467) |
| Tokens | Monotonic down-scoping | Blast radius shrinks with depth | **PLANNED** (claimed as P0/V1) | Structural invariants only (OboInvariants.java:29-35) |
| Tokens | Keys, rotation, JWKS | Verifiable, rotatable signing | **PARTIAL** | Rotation per-JVM; public JWKS likely empty (inferred, GG:524-525) |
| Tokens | Revocation (session, `jti`) | Stop a live chain | **PARTIAL** | `/a2a` has no `jti` check; A2A has no sessions to kill (GG:509-512, :728) |
| Identity | JWT auth on agent doors | Only valid IdP callers | **PARTIAL** | No issuer/audience validation, trust-all TLS for JWKS (GG:373-376); mode `none` = anonymous (GG:365) |
| Identity | Human vs automated classification | "On behalf of Sarah" vs "a bot did it" | **SHIPPED** | Gateway OBO always HUMAN_DELEGATED; PDP never reads `tokenType` (GG:414, :608) |
| Identity | Verified agent identity (azp) | No impersonation by renaming | **PARTIAL** | Falls back to self-asserted `clientInfo.name` (GG:582, :697) |
| Identity | Sender constraint (`cnf` + assertion) | Stolen OBO useless to others | **PARTIAL** | Any IdP token with `azp` passes, including a human's; absent on `/stateless/mcp` (GG:419, :422). **Not PoP.** |
| Identity | Approval gates (PENDING/BLOCKED/DEPROVISIONED) | Nothing acts until approved | **PARTIAL** | `/mcp` only; 8 PENDING A2A calls ALLOWed live (GG:291) |
| Identity | Runtime discovery | Shadow agents appear as PENDING | **PARTIAL** | NHIs discovered on `/mcp` only (GG:328) |
| Identity | SPIFFE / mTLS / API-key caller auth | Workload attestation | **PLANNED** | `WorkloadIdentitySource` seam only (GG:425) |
| Identity | Autonomous (NHI-rooted) chains | Govern unattended bots (Netskope Q7) | **PLANNED** | Spec with Options A/B (`repo:docs/features/autonomous-multiagent-nhi.md:3-57`); agent-side scaffold only |
| Policy | PDP on every governed hop, default-deny | Nothing runs unless allowed | **SHIPPED** (core) | Playground bypass; `tools/list` not policy-checked (GG:161, :777) |
| Policy | Policy language | Expressive ABAC | **OVERCLAIMED / PARTIAL** | Regex subset; `principal in AgentGroup` / `resource in Server` heads ignored; `!`, `\|\|` mis-evaluated; unknown fragments dropped (GG:549-566) |
| Policy | Lineage guardrails (`deny-unverified-root/-actor`) | Block unattributable chains | **PARTIAL** | Both disabled in live tenant `amitdev.local` (GG:648) |
| Policy | Capability profiles | Agents see/call only what's provisioned | **PARTIAL** | Unresolved agent skips gate (GG:758); SKILL sets go stale (GG:754) |
| Policy | Per-tenant partitioning | One BU's rules never decide another's | **PARTIAL** | `X-WS-Tenant` header outranks verified claim; null tenant = union of all (GG:444, :446) |
| Policy | `/check` review, `/test` dry-run | Catch mistakes before save | **PARTIAL** | `/check` doesn't report ignored fragments (GG:639); `/test` can't exercise args (GG:661) |
| Policy | Versioning, obligations/step-up, intent | Prove past state; escalate risky actions | **PLANNED** | GG:573, :1185; §12 |
| Orchestration | Protocol-neutral spine + adapters | One pipeline for all protocols | **SHIPPED** | 4 duplicated legs (GG:1063) |
| Orchestration | MCP `/mcp` (tools, prompts, resources) | Drop-in governed MCP endpoint | **SHIPPED** | Downstream `isError` dropped → errors look like success (GG:211) |
| Orchestration | `/stateless/mcp` | Ready for sessionless MCP | **PARTIAL** | `_meta` never read; `server/discover` only a comment (GG:89; `HttpTransportConfig.java:84`); weaker gates |
| Orchestration | A2A `/a2a` | Governs agent-to-agent calls | **PARTIAL** | `message/send` only; first text part only; artifacts dropped (GG:270, :306) |
| Orchestration | Multi-hop fan-out under one trace | Full delegated journeys | **SHIPPED** (human-delegated) | No deadline propagation (GG:329) |
| Orchestration | A2A onboarding (card URL/JSON) | Register third-party agents | **PARTIAL** | Skills in memory only; no refresh; name collisions with MCP (GG:343-346) |
| Orchestration | Downstream credential custody | Agents never hold tool keys | **SHIPPED** | Key falls back to a compiled-in constant (GG:942) |
| Orchestration | JIT per-call credentials, vault | Replace standing tool keys | **PLANNED** | No vault client anywhere in the repo; secrets are AES-GCM in Postgres (GG:768, :942) |
| Egress | Response classification | See sensitive data flows | **SHIPPED (observe-only)** | Hard-coded `OBSERVE` (GG:839) |
| Egress | Rule library, templates, AI rule assistant | Tune detection per industry | **SHIPPED** | 14 templates / 5 packs (GG:834) |
| Egress | Block / redact / tokenize, provenance taint, request-side inspection | Stop data leaving | **PLANNED** | GG:840, :845; PPP:56-70 |
| Egress | "Honeypot fix" (no raw payloads in audit) | Audit isn't a leak target | **OVERCLAIMED** | Raw args/responses stored in full; decision reversed 2026-08-17 (GG:887) |
| Audit | Dual ledger with deciding policy + reason | "Who did what, for whom, why allowed" | **SHIPPED** | Rows dropped when queue full (GG:870) |
| Audit | Trace / View-DAG / OBO receipt | Reconstruct a journey | **PARTIAL** | Edges inferred by name; no tenant predicate on some queries (GG:898, :904) |
| Audit | Retention, tamper-evidence, SIEM export | Retention rules, integrity, SOC feed | **PLANNED** | None; CSV only (GG:892, :926) |
| Reporting | CISO dashboard (9 widgets over 6 endpoints; MEM/ciso-dashboard-backend.md:29, GG:914) | Exec view of agent risk | **SHIPPED (read-only)** | Egress coverage always 0 (inferred, GG:847); "attributed" counts DEFAULT_DENY (GG:922) |
| Reporting | SOC 2 / SOX packs | Auditor evidence | **SHIPPED** | CSV/print only (GG:920) |
| Reporting | Identity Access Graph | Observed vs entitled vs gap | **PARTIAL** | Entitlement view inherits ignored-head problem (inferred) |
| Admin | Admin REST + 14-page dashboard | Operate everything from one place | **PARTIAL** | **No authentication**; tenant from header (GG:48, :440) |
| Admin | Policy / profile / rule AI assistants | Plain English → policy/profile/rule | **PARTIAL** | Policy `/chat/save` enables with no approval (GG:667); sends tenant PII to Anthropic (GG:669-671) |
| Ops | Packaging, HA, metrics, rate limits | Run in production | **PLANNED** | No Dockerfile/k8s, no Micrometer, per-JVM state (GG:935-940, :1162) |

### 5.2 PRD P0/P1/P2 status (65 items)

**Tally** (mapping of PRD:96-196 against GG): **16 SHIPPED / 28 PARTIAL / 21 PLANNED.**
- **P0:** 12 SHIPPED / 13 PARTIAL / 1 PLANNED (monotonic down-scoping).
- **P1:** 3 / 12 / 12.
- **P2:** 1 / 3 / 8.

| # | PRD item (line) | Pri | Status | Evidence / gap |
|---|---|---|---|---|
| A1 | Inline, terminate + re-originate (99) | P0 | SHIPPED | The inline proxy works on all three doors. It is not un-bypassable: the Playground endpoint skips it (GG:777; see P2 in §3) |
| A2 | Classify target, route to adapter (100) | P0 | SHIPPED | By `descriptor.protocol` (GG:206) |
| A3 | MCP path unchanged after refactor (101) | P0 | SHIPPED | Characterization tests (GG:1248) |
| A4 | A2A intercept + forward (102) | P1 | SHIPPED | `message/send` only |
| A5 | Default-deny unknown callers/targets (103) | P1 | PARTIAL | Unresolved caller skips profiles (GG:758) |
| A6 | Egress for non-MCP calls (104) | P2 | PLANNED | — |
| B1 | Caller auth: mTLS / workload JWT / API key (107) | P0 | PARTIAL | JWT + assertion only |
| B2 | Human token via JWKS, IdP-neutral (108) | P0 | PARTIAL | No iss/aud check, trust-all TLS (GG:376) |
| B3 | Delegated vs automated (109) | P0 | SHIPPED | PDP doesn't read it (GG:608) |
| B4 | Platform-signed user assertion (110) | P1 | PLANNED | — |
| B5 | Holder-of-key DPoP / cert-bound (111) | P2 | PARTIAL | `cnf` bearer check, not PoP (GG:420) |
| C1 | Short-lived, configurable ~5 min (114) | P0 | PARTIAL | 120 s fixed |
| C2 | OBO / RFC 8693 actor (115) | P0 | SHIPPED | Claims only; no exchange grant (GG §5.8) |
| C3 | Monotonic down-scoping (116) | P0 | **PLANNED** | Not built |
| C4 | Own keys, JWKS, rotation (117) | P0 | PARTIAL | JWKS likely empty; per-JVM |
| C5 | Fail-closed mint (118) | P0 | PARTIAL | Null-tenant skip |
| C6 | Scope to target + action (119) | P1 | PARTIAL | Minted, not consumed downstream |
| C7 | Revocation via registry block (120) | P1 | PARTIAL | `/mcp` only |
| C8 | Pluggable minting (KC token exchange) (121) | P1 | PLANNED | Self-signed only |
| C9 | SPIFFE SVID input (122) | P2 | PLANNED | Seam |
| D1 | `act_chain` shape incl. timestamps (125) | P0 | PARTIAL | No timestamps |
| D2 | Extend one actor per hop (126) | P0 | SHIPPED | GG:489 |
| D3 | Correlation id across hops (127) | P0 | SHIPPED | `trace_id` in OBO (GG:895) |
| D4 | Reconstruct chain from audit (128) | P1 | PARTIAL | Name-inferred edges |
| E1 | PDP every hop (131) | P0 | SHIPPED | Not on bypass/list |
| E2 | Deny → 403 + audit (132) | P0 | PARTIAL | Deny is audited and default-deny holds, but it returns HTTP 200 (MCP `tools/call`: `CallToolResult isError=true`; A2A: FAILED Task), not 403 (GG:181, :205, :310) |
| E3 | Attribute-based only (133) | P0 | PARTIAL | Per-agent rules used |
| E4 | Invariants: initiator gate, all approved (134) | P1 | PARTIAL | Guardrails disabled live; approval UNKNOWN on MCP |
| E5 | Versioned store (135) | P1 | PARTIAL | No versioning |
| E6 | Parameterized templates (136) | P1 | PLANNED | Egress templates only |
| E7 | Approval gate / escalate (137) | P1 | PLANNED | No obligations |
| E8 | Simulation / dry-run (138) | P2 | PARTIAL | `/test`, `/check` |
| F1 | Inventory incl. owner/team/tier (144) | P0 | PARTIAL | No owner columns (GG:694) |
| F2 | Approval before any hop (145) | P0 | PARTIAL | `/mcp` only |
| F3 | Platform sync, kore.ai first (148) | P1 | PLANNED | Seam |
| F4 | IdP sync of NHIs (149) | P1 | PLANNED | Runtime discovery only |
| F5 | SPIFFE attestation (150) | P2 | PLANNED | — |
| F6 | Runtime discovery → PENDING (153) | P1 | PARTIAL | MCP callers yes |
| F7 | Well-known agent-card (154) | P2 | SHIPPED | Admin-initiated |
| F8 | Bulk auto-approval (156) | P1 | PLANNED | Per-id only |
| G1 | Inject minted JWT downstream (159) | P0 | PARTIAL | A2A yes, MCP no (deliberate, PLAN:42) |
| G2 | JIT scoped tool credential (160) | P1 | PLANNED | — |
| G3 | Vault integration (161) | P1 | PLANNED | — |
| H1 | One record per hop (164) | P0 | SHIPPED | Split across 2 tables |
| H2 | Async audit (165) | P0 | SHIPPED | Lossy under load |
| H3 | Chain reconstruction + SOX/SOC 2 export (166) | P1 | PARTIAL | CSV only |
| H4 | Metrics, health, tracing (167) | P1 | PARTIAL | No metrics stack |
| H5 | Retention + export APIs (168) | P1 | PLANNED | — |
| H6 | Tamper-evident audit (169) | P2 | PLANNED | — |
| I1 | Admin APIs (172) | P0 | SHIPPED | **Unauthenticated** |
| I2 | Connectors, keys, scopes APIs (173) | P1 | PARTIAL | No connectors |
| I3 | Dashboard views (174) | P1 | SHIPPED | + CISO, graphs |
| J1 | Fail-closed everywhere (177) | P0 | PARTIAL | See P5 |
| J2 | Stateless per hop (178) | P0 | PARTIAL | Per-JVM state |
| J3 | Timeouts, retries, breaking (179) | P1 | PARTIAL | Timeouts only |
| J4 | Rate limiting (180) | P1 | PLANNED | — |
| J5 | HA + graceful shutdown (181) | P1 | PLANNED | No deploy artifacts |
| J6 | Tenant isolation (182) | P1 | PARTIAL | Header override, unscoped registries |
| K1 | MCP adapter (185) | P0 | SHIPPED | — |
| K2 | A2A adapter (186) | P1 | SHIPPED | — |
| K3 | REST / RAG / LLM adapters (187) | P2 | PLANNED | — |
| L1-L4 | SDK; cross-org/AAuth + PoP; SPIFFE root; DLP beyond today (193-196) | P2 | 3 PLANNED, 1 PARTIAL | Only observe-only egress exists |

**Judgment on "V1 complete".** Team memory records "V1 … COMPLETE" (MEM/agentic-gateway-staging-roadmap.md:17). That is the build plan's narrower V1 (single-hop MCP, PLAN:82-86), not the PRD's v1 = P0 + P1 (PRD:10). Say which one whenever "V1 complete" is quoted.

### 5.3 The single most important capability caveat
The live demo tenant's only broad permit, `financial-desk-grant`, uses a `principal in AgentGroup` head that the engine ignores, so it effectively reads "permit any agent, any action, any resource whenever the root human is verified". `agent-console` (empty groups) was ALLOWed 44 times through it, and both DEFAULT guardrail forbids are disabled (GG:645-652). Inferred: denials in the demo tenant today therefore come mostly from **capability profiles**, not policy, except hops with an unverified root, which fall to DEFAULT_DENY (the broad permit requires `rootVerified == true`, GG:647). The `default` tenant holds only the two forbids, so everything there is denied by policy (GG:649). **Judgment:** any "default-deny policy" claim in the live demo is resting on the profile layer.

### 5.4 Scope creep (shipped, not in the PRD)
Egress post-processor (GG §8), CISO dashboard and compliance module (GG §9.6), Identity Access Graph (GG §9.5), capability profiles (GG §7.5), three AI assistants (GG §11), `/stateless/mcp` (PLAN "Delta 2"). **Judgment:** governance *reporting* has run ahead of core P0/P1 *enforcement*.

### 5.5 KPIs and today's baselines
The PRD defines operational metrics only (PRD:167: "per-hop latency, mint rate, allow/deny"); it sets no targets and no business KPIs. What exists today:

| Tier | KPI | Source of the number | Baseline today |
|---|---|---|---|
| Operational | Governance overhead per hop | Audit event timestamps (GG §13(d)) | ~12–13 ms p50 (GG:1081) |
| Operational | PDP evaluation time | `evaluationDurationMs`, n=497 | p50 0 ms, p99 1 ms (GG:1074) |
| Operational | Decision → token minted | Audit events | SKILL 11.3 ms / TOOL 10.1 ms p50 (GG:1075) |
| Operational | Downstream latency | Audit events | A2A skill 6.9 s p50, MCP tool 1.4 s p50 (GG:1076-1077) |
| Operational | Mint rate, allow/deny rate | `STS_TOKEN_MINTED` events, `pdp_audit_log` (SQL only; no Micrometer, GG:935) | Computable, not tracked |
| Operational | Audit rows dropped | Not counted: rows are dropped silently when the queue is full (GG §14 item 28) | Unknown |
| Governance | % of actions with a verified root | `pdp_audit_log` chain context | Computable, not tracked |
| Governance | % of denials by default-deny vs explicit forbid | `pdp_policy_id` / `pdp_reason` (MEM/pdp-decision-audit-attribution.md) | Computable, not tracked |
| Governance | PENDING vs approved agents | Agent registry | Computable; but MCP approval status is UNKNOWN on 210/210 rows (GG:584), so an "approved actions" KPI is not trustworthy on MCP today |
| Governance | CISO KPI strip + posture score | 6 tiles with window-over-window deltas (MEM/ciso-dashboard-backend.md:14); posture weighted 40/25/20/15 (GG:915) | Already computed live |
| Business | POCs, pilots, deals, time-to-first-governed-call | No source | Open (§13) |

**Cannot be measured today:** coverage of traffic that bypasses the gateway, which the CISO dashboard deliberately never shows (MEM/ciso-dashboard-backend.md:27).

---

## 6. Product surfaces by persona

**Cross-cutting fact:** the admin plane has no identity. The dashboard sends only `X-WS-Tenant: amitdev.local` (hard-coded) to `http://localhost:9492` with no Authorization header (`ws-gateway-dashboard/js/api.js:8-26`); `/api/admin/**` is `permitAll` (GG:48, :359); CORS allows `*` (GG:944); the admin actor comes from a spoofable `X-Admin-User` header the dashboard never sends (GG:1138). CISO, auditor and admin are the same anonymous caller. A React port of CISO and trace-DAG views exists in the prod UI per memory (MEM/ciso-dashboard-backend.md; MEM/ws-react-trace-dag-reactflow-dagre.md), out of scope here. **Judgment:** treat the vanilla dashboard as the reference/dev UI, not the shipped customer console.

| Persona | Jobs to be done | Surfaces (dashboard pages unless noted) | Can decide | Biggest gap today |
|---|---|---|---|---|
| **Security admin** | Connect tool servers without giving agents their keys; know every agent; approve; grant least privilege; stop a bad actor; keep IdP and keys healthy; tune sensitivity | Configuration (MCP servers, IdP, STS keys), MCP Servers, Agents (+ Live Sessions), Identities, Capability Profiles, Policies (chat → editor → `/check` review), Health, In-Flight, Processed Data › Rules, Playground | Approve/block/deprovision; allow-lists; policies; revocations; key rotation; detector tuning | Kill switches don't cover A2A (no `jti` check, no sessions, status not enforced) (GG:291, :509-512, :728) |
| **CISO** | "Are we governed and improving? What first? If agent X is compromised, what can it reach? Who is accountable?" | CISO › Dashboard (window 24h–90d, posture "higher is better", priority actions), Accountability, Blast Radius, Point-in-Time, Access Graph, Identity Graph | Nothing directly; every action hands off to an admin screen (e.g. seeded policy prompt) | Coverage card offers "Configure controls" for redact/block that doesn't exist (`cisoDashboard.js:497, :513`); owner = team label, not a person (GG:694) |
| **Compliance / auditor** | SOC 2 / SOX evidence; who did what on whose behalf; reconstruct an incident | CISO › Compliance (framework switch, print, CSV), Audit Log (trace, View DAG, OBO receipt with integrity chips) | Export compliance CSV only | No tamper-evidence, no retention, rows dropped under load, **no Audit Log export**, no read-only auditor identity (GG:870, :892) |
| **Agent developer** | Make my agent callable; call others; keep the chain intact; debug a deny | No developer portal. Contract = sample-agents README: A2A card + `message/send`, forward OBO as `Authorization`, own token as `X-Agent-Assertion`, address `metadata.skillId=<agent>.<skillId>` | — | Toolbox refresh needs restart (`a2a-sample-agents/README.md:44-46`); Playground bypasses policy so it can't reproduce a deny |
| **End user** | Ask a question, get an answer grounded in governed tools and agents | `ws-agentic-console`: Keycloak login (or demo SSO), chat, per-call ✅/🚫, "Governed delegation" journey for A2A turns | — | An A2A policy deny renders as ✅ with the deny text as the answer (`a2aClient.js:150-166` vs GG:310); MCP tool errors show as success (GG:211) |

**Customer-visible UI (not inventoried here).** The UI buyers actually saw is the React UI on the cloud environment: 13 Agentic Gateway screens captured from `amitdev.whiteswansec.io` for the enterprise PDF (MEM/gateway-enterprise-doc-build.md:17), including a React port of the CISO dashboard and of View DAG (MEM/ciso-dashboard-backend.md:29; MEM/ws-react-trace-dag-reactflow-dagre.md:11). The recon file listing those screens (`gateway-recon.md` in a backend-session scratchpad) no longer exists on disk, and the ws-react code sits in the out-of-scope prod stack. So it is **unknown which of the defects below also exist in the customer UI** (open question §13 Q19).

**Verified UI defects worth knowing (vanilla dashboard and console):**
- **Capability-profile "Read-Only" preset** is a browser regex `\b(list|get|search|read|…)\b` on tool names (`ws-gateway-dashboard/js/profiles.js:596-600`). Because `_` is a word character it matches no snake_case names (`get_me`, `list_issues`, `GLOBAL_QUOTE` → false), so Read-Only grants zero tools but all prompts and resources on typical servers.
- **AI Profile Assistant auto-assigns** the new profile to agents by case-insensitive substring match on name (`profiles.js:940-965`).
- **Policy "Review" gate** (`/check`: syntax, registry references, effect, conflicts, duplicate `@id`) is the best-designed flow (`policies.js:1119-1258`), but it validates against an engine that silently drops group/server scoping, and the "Block unapproved agents" quick prompt steers the LLM toward that ignored syntax (inferred from GG:639, :678).
- **Console:** system prompt still says "internal engineering assistant" and cites billing/refund agents (`ws-agentic-console/src/llm.js:28-37`); environment switch is process-global and `POST /api/branding` is unauthenticated (`server.js:79-119`); the Journey reads the unauthenticated admin audit API (`journeyClient.js:36-45`), so securing the admin API will break it unless the console gets a credential.
- **Unused API client entries:** custom attributes, revocation lists, `/policies/test`, `/chat/save` (`api.js:138, :140, :161-170, :224, :226`).

---

## 7. The journey: how we got here

| # | Dates | Phase | What it delivered | Why |
|---|---|---|---|---|
| 0 | 2024-10 → 2025-06 | **Azure IAM / JIT prehistory** | Azure AD sync, RBAC inventory, JIT role state machine, K8s/AKS JIT, OPA-Rego policy generation (git `76e006e`, `0931e45`) | WhiteSwan's core business: permanent access made "time-bound" (PDF p.3). The gateway was later built inside this same app, which still co-hosts the legacy modules (GG §2, §10). |
| 1 | 2026-02-09 → 02-13 | **MCP gateway core** | WS MCP client + server, Capability Registry, Orchestration Layer, async audit (git `2462e4b`, 81 files); stdio then HTTP | One governed chokepoint between agents and enterprise MCP servers: "building the WS (whiteswan) MCP (model context protocol) Gateway" (T-A, 2026-02-09) |
| 2 | 02-18 → 03-05 | **AI-summit showcase + agent lifecycle** | Dashboard (first commit 02-21), in-flight view, PENDING/APPROVED/BLOCKED, session lifecycle | "I have to go in an AI summit and wanna showcase" (T-A, 2026-02-18). Branch family is still `ai-summit*`. |
| 3 | 03-09 → 03-16 | **PDP** | PDP-1..8: Cedar-style policies, LLM policy assistant, capability profiles; PR #1 `af69998` | Next-morning demo plus chat-authored policy; "Lets go wth Cedar" (T-A, 2026-03-09). `cedar-java` failed on ARM64 (`UnsatisfiedLinkError`), so a pure-Java evaluator was written the same day. |
| 4 | 03-17 → 03-25 | **AuthN / identity** | Token classification, human/NHI/agent registries, blocking, approval; PR #2 `fa1227b` | Policy is meaningless without the principal; seed of "human behind the agent" (OBO) |
| 5 | 04-02 → 07-21 | **Tenancy + Secure OS / "Varden" detour** | Row-level tenancy (`69f697b`…`b3cf9bd`); git quiet 04-05 → 07-19 | Port into a multi-tenant Secure OS platform as module "Varden" (T-A, 2026-04-16). Dropped 05-22. |
| 6 | 05-27 → 07-22 | **Rethink: MCP gateway → agentic auth (design)** | Studied Uber; SDK-first plan ("sts, agent registry, sdk", T-A 05-28); compared AAuth; **pivot to SDK-less** (07-08) and **one gateway** (07-08); PRD 07-14 | Real systems are multi-hop on shared long-lived credentials (PRD:18); "sdk less approach as of now for quick launch in the market" (T-A, 2026-07-08) |
| 7 | 07-22 → 07-25 | **Hop spine + STS + act_chain (single-hop MCP V1)** | Stage 0 split + characterization tests; Stage 1 STS (`fa0c6f2`, verified live 07-22); Stage 2 enforce (`8b7c1bc`); `/stateless/mcp`; Stage 5 "plug & play" B1–B6 neutral spine (`7640922`…`a20db9e`) | "centralised service is the product; the adapters are lightwaeight plug-ins" (T-A, 2026-07-22); MCP spec deprecating sessions |
| 8 | 08-03 → 08-05 | **Stage 2.5 hardening** | Key rotation (`cfad3db`), OBO revocation (`22448c0`), tenant-partitioned PDP (`1b3598f`, fixed a real cross-tenant leak), authoring UX (`e34687a`); #5 distributed state **not done** | Operability before A2A (MEM/agentic-gateway-staging-roadmap.md:16) |
| 9 | 08-06 → 08-10 | **A2A multi-hop** | Full A2A in one 55-file commit (`851d692`, 138 tests green); agent cards; Unified Agent Model stages 1–8 (PR #5 `ff14571`); OBO invariants (`ad993f0`) | "A2A isn't a thing in the enterprise grade right?" (T-A, 2026-08-04) → prove the core on A2A anyway |
| 10 | 08-10 → 09-01 | **Governance surfaces** | View DAG + empty-hop reasons, CISO dashboard, standalone compliance + SOX, post-processor (observe-only), credential encryption, access graph | Buyers are CISOs and non-technical admins: "create a dashboard for CISO, non tech admins" (T-A, 2026-08-13) |
| 11 | 08-19 → 09-26 | **Go-to-market, docs, next bet** | LinkedIn launch (T-C 08-19); Netskope RFI + sessions; Zscaler prep; autonomous scaffold (agent-side only); docs reorganized (`76502d4`), PR #6 `18c0c40` (09-25); grounding doc; branch `ai-summit-intentAuthZ` (0 commits) | Customer questions now set the roadmap: autonomous NHI (Netskope Q7) and intent-aware authZ |

**Commit volume:** 2024-10…2025-06 = 121 commits; 2026: Feb 23, Mar 45, Apr 4, Jul 24, Aug 49, Sep 3 (git log). Six PRs mark the big merges: #1 PDP, #2 AuthN, #3/#4 tenancy, #5 unified agent, #6 agentic auth gateway.

**Housekeeping fact (the "2" duplicates).** 279 untracked `<Name> 2.<ext>` copies (277 byte-identical to the originals) appeared on 2026-09-25 at 20:41 IST, most likely from an iCloud Desktop sync conflict, and the source copies were removed after a byte-for-byte check (GG:13, :23; MEM/icloud-desktop-conflict-copies.md:11-13). A read-only `find` on 2026-09-26 shows what is still left:
- **1078** copies under `target/` (build output, cleared by `mvn clean`).
- **2** in the graph cache (`.code-review-graph/graph 2.db-wal`, `graph 2.db-shm`).
- **3 broken git ref copies** in the gateway repo: `refs/heads/ai-summit 2`, `refs/remotes/origin/ai-summit 2`, `refs/remotes/origin/ai-summit-agenticAuthGateway 2`. `git branch -a` prints "warning: ignoring ref with broken name" for each.
- **20 `.git/index N` copies** (`index 2` … `index 21`, dated Feb–Sep 2026) in the gateway repo, and `.git/index 2` in `ws-gateway-dashboard`.

No copies remain in `src/` or `docs/`. The git copies are older than the 09-25 event (the newest, `index 21`, is dated 09-25 20:11), so they are most likely a separate, longer-running iCloud effect (inferred). The index copies are safe to delete; remove the ref copies only after confirming each ref's commit is reachable from a real branch. The memory note is stale on two points: it says the graph-cache copies were removed and that `target/` held 1,001 copies (MEM/icloud-desktop-conflict-copies.md:13); both files exist today and `target/` holds 1078.

**Judgment on the journey.**
- The strongest decision was **sequencing**: prove every agentic-auth primitive on single-hop MCP, neutralize the spine, only then add A2A. That is why A2A landed in days.
- The weakest pattern is **demo-driven breadth after A2A**: CISO, compliance, post-processor, access graph and docs landed in ~3 weeks while core enforcement gaps (A2A status gate, stateless door gaps, ignored policy heads, unauthenticated admin, per-JVM state) stayed open. GG §14 lists 39 gotchas across all categories; items 1-12 are security/fail-open and 13-27 correctness (GG:1134-1190).
- Repo/venue churn (standalone → Secure OS → prod repo → back to local) cost about a quarter of calendar time and left drift; one source of truth is now the rule (MEM/gateway-two-repos-local-prod.md).

---

## 8. Key decisions and their rationale (condensed decision record)

Status: **Holds** · **Holds, weakened** (intent sound, code partly delivers) · **Revisit** · **Superseded** · **Deferred**.

| # | Decision | Date | Why | Rejected | Status |
|---|---|---|---|---|---|
| D1 | **Gateway-first, no SDK** | 2026-07-08 (restated 07-13) | Enterprises can't mandate an SDK in agents they don't own; faster POC (MEM/uber-agentic-auth-precedent.md) | Uber-style SDK + STS; SDK + separate A2A gateway | Holds; SDK = PRD P2 (PRD:193) |
| D2 | **One gateway for MCP + A2A** | 2026-07-08 | "I want to have a single gateway which contains both" (T-A) | Blueprint's separate A2A Gateway (BP:6-8) | Holds (GG:45-48) |
| D3 | **Sell governance/compliance depth, not token plumbing** | 2026-08-03 | agentgateway (solo.io) already does OBO/token exchange; "governance thing thats our moat" (T-A) | Leading with "we do token exchange + OBO" | Holds. **Judgment:** the moat needs trustworthy audit data, which dropped rows and header-chosen tenants weaken |
| D4 | **Abandon Secure OS / Varden fork** | 2026-05-22 | Evolve the original app | Absorbing into Secure OS | Holds |
| D5 | **Target agent/automation platforms** | 2026-07-08 | PRD:37 | — | Holds in docs; **revisited in practice** (effort went to Netskope/Zscaler) |
| D6 | **Agent is its own principal type, separate from NHI** | design | Root = human or NHI; after root = agents (GG:488, :501) | "Agents are NHIs" (industry) | Holds, under market pressure (T-C, 2026-08-29) |
| D7 | **Own STS; RFC 8693 claim shape only, no exchange grant** | 2026-07-22 / 08-03 | Inline gateway mints as the call passes | Keycloak token exchange (PRD:121); full RFC 8693/7523/ID-JAG | Holds; interop debt logged |
| D8 | **Per-tenant keys, JWKS, rotation** | 07-22 / 08-03 | Global key risks cross-tenant leakage (stage-1-plan:16) | — | Holds, weakened (per-JVM, JWKS likely empty) |
| D9 | **Mint per hop after PDP ALLOW and connectivity; 120 s; fail-closed** | 07-22 | Never issue a token for denied or undeliverable work | — | Holds, weakened (null-tenant skip; 120 s vs PRD ~5 min) |
| D10 | **`act_chain` in the token, root-first, append-only, invariant-checked** | 07-22 / 08-10 | "the gateway doesn't remember previous hops" (PRD:76) | — | Holds for A2A; stateless claim weakened |
| D11 | **OBO on the wire only for A2A; MCP keeps brokered credential** | 07-22 | Third-party MCP servers can't validate a WhiteSwan JWT (PLAN:42) | PRD P0 G1 inject downstream | Holds |
| D12 | **Agent identity from verified `azp`, not self-declared name** | 08-07 / 08-09 | Anti-impersonation (spoof test proven) | — | Holds, weakened (fallbacks, GG:582, :697) |
| D13 | **Sender-constrained A2A OBO (`cnf` + `X-Agent-Assertion`)** | 08-07 | Theft simulation rejected (MEM/financial-scenario-demo.md:17) | DPoP/cert-bound (P2) | Holds, weakened (human token accepted; stateless has no check) |
| D14 | **Ordered multi-signal token classification, default HUMAN** | 03-18 / 08-09 | One signal is fragile | Introspection by default | Holds; flaw: gateway OBO with NHI root → HUMAN (GG:414) |
| D15 | **Human profile only from IdP login, never from delegated tokens** | 08-09 | Fixed an OBO clobber bug (MEM/human-profile-obo-clobber.md) | — | Holds |
| D16 | **OBO-only now; autonomous agents deferred** | 08-13 / 09-01 | `/a2a` is session-less; NHIs from assertions never registered | — | Deferred (Options A/B) |
| D17 | **SPIFFE: seam only; carry `workload_id`/`identity_source` now** | 08-04 / 08-10 | Needs a real deployment; gateway only validates SVIDs | — | Deferred; `identity_source` always `KEYCLOAK` |
| D18 | **Cedar-like syntax on an in-house regex evaluator** | 03-09; reaffirmed 08-03 | Native lib failed on ARM64; later "much flexible" for dynamic `act_chain` attrs (MEM/agentic-gateway-staging-roadmap.md) | OPA/Rego; AWS `cedar-java` | **Revisit**: flexibility became silent widening |
| D19 | **Default-deny; errors fail closed** | 03-09; LD-1 in Stage 2 | "A broken gate must never become a bypass" (stage-2-plan:13-15) | Original fail-open | Holds in engine; weakened in live data |
| D20 | **Defense in depth: approval → profile → PDP** | 03-10 | Three layers, all must pass | — | Holds on `/mcp`; weakened on `/a2a` |
| D21 | **Protected lineage guardrails; write-scoped lineage deferred** | 07-23 / 08-03 | MCP read/write annotations not captured | — | Holds in code, bypassed in live data (GG:648) |
| D22 | **Tenant-partitioned policy engine** | 08-03 | Fixed a last-writer-wins cross-tenant leak | Single-tenant deploy model (March) | Holds, weakened (header override, null-tenant union) |
| D23 | **LLM policy assistant + `/check` against real registries; `@id` identity** | 03-09 / 08-03 | "a mock test is just a mock" | Dry-run simulator | Holds, with gaps (`/chat/save` auto-enables; no versioning) |
| D24 | **Protocol-neutral spine; neutral contract deferred until 2nd protocol** | 07-22 → 07-25 | Designing against MCP only "is guesswork" (stage-0-refactor-plan:15-24) | "MCP gateway with a rename" | Holds, weakened (4 duplicated legs) |
| D25 | **A2A via inline proxy, not token return** | 07-22 | Every hop enforced; revocation mid-flight (PLAN:30-34) | Token-return | Holds; cost = fully synchronous blocking |
| D26 | **A2A first for agent-to-agent (POC)** | 08-04 | "As of now foe POC lets go with the A2A protocol" (T-A) | gRPC/HTTP connectors first | Holds; real customer protocols still open |
| D27 | **SKILL is a distinct capability type** | 07-25 | Own policy action `skillInvocation` (MEM/a2a-capability-type-skill.md) | Map skills to TOOL | Holds (`SRC/orchestration/model/CapabilityType.java:13`) |
| D28 | **A2A onboarding by card URL/JSON; platform sync deferred** | 08-05 / 08-13 | Seam for Kore.ai/AA later | PRD sources 1-3 | Holds, weakened (in-memory skills, no refresh) |
| D29 | **One canonical agent keyed by (tenant, name)** | 08-06 (decided); built 08-09/10 | "on the design level of BE architecture it should be one" (T-A) | Two stores, UI-only merge | Holds |
| D30 | **Stateless MCP alongside session MCP** | 07-23 | MCP spec deprecating sessions | — | Holds, weakened (`_meta` dropped, weaker gates) |
| D31 | **Egress: observe-first, async, classify at the spine (Option A)** | 08-11 / 08-17 | Redacting between hops breaks agents (PPP:14-22); spine has producer+consumer context | Option B in adapters (MEM/postprocessor-hook-option-b.md) | Holds; "honeypot fix" **Superseded** ("let them get saved", T-A 2026-08-17) |
| D32 | **Two ledgers; `pdp_audit_log` authoritative; async audit** | 02-09 / 08-10 | Async was an original requirement | — | Holds, weakened (drops under load) |
| D33 | **Compliance standalone with duplicated queries** | 08-17 | Ship without the CISO dashboard (MEM/compliance-standalone-module.md) | Depend on CISO package | Holds |
| D34 | **Downstream secrets AES-GCM at rest, masked, decrypted at connect** | 08-19 | MEM/mcp-cred-encryption-url-and-config.md | — | Holds, weakened (compiled-in key fallback) |
| D35 | **Admin API `permitAll` + Playground bypass** | 07-23/24 | Code was being ported into ws-backend which has admin login (structural-refactor-plan:32-41) | — | **Revisit**: since 09-25 all work is in this repo, so the rationale is gone |
| D36 | **Work only in AzureAdWsIntegration** | 09-25 | Prod copy drifted | Dual-repo rule (09-03) | Holds |
| D37 | **`ddl-auto: update`; drop/recreate freely** | 08-07 | No customers, local dev (MEM/schema-drop-recreate-ok.md) | Flyway/Liquibase | Holds. **Judgment:** must change before the first customer |
| D38 | **Intent-aware authZ next: research first** | 09-25 | "research how others do it first, no guessing" (MEM/intent-aware-authz-mindset.md) | — | Open |
| — | **Single instance for now** | 2026-04-17 | "no lets build it for single instance as of now" (T-D) | — | Holds; conflicts with PRD "stateless / horizontal" |

**Judgment on the pattern.** The decisions are consistent and well argued (gateway-first, own STS, inline proxy, default-deny, observe-first). The recurring weakness: each was verified along the happy path of the financial demo, while side paths (`/stateless/mcp`, `/a2a` door gates, unresolved agents, null tenants, ignored policy heads) quietly fail open.

---

## 9. Market and positioning

### 9.1 Deals and conversations in flight
| Counterparty | What's happening | Cites |
|---|---|---|
| **Netskope** | 18-question Agentic NHI RFI answered with the cloud team and CEO; "the final stage our deal with Netskope"; long technical session incl. code structure, quality, test coverage; follow-up briefs (API design, concurrency, JIT/PAM, MCP & IdP onboarding); employee census "send it to netskope"; engineering-head 1:1 | T-C 2026-08-28 / 08-29 / 09-01; T-B |
| **Zscaler** | Two prep rounds (2026-09-11) on six identity/token questions; "they already have a mcp gateway"; CEO: portfolio "actually very comprehensive", AI announcements "do overlap" | T-C 2026-09-11 |
| **Obsidian Security** | CEO asked what unique data could enrich a joint solution; answer: verified human→agent→tool delegation, entitlement vs usage, egress sensitivity per action, attempted-vs-allowed ledger | T-C |
| **AA / Kore.ai** | Named partners/targets; platform sync not built; console currently branded "Kore.ai Agent Console" | PRD:148; `ws-agentic-console/branding.json` |
| **AI/ML diligence** | CEO forwarded an AI/ML questionnaire; answered from gateway view (LLM only in admin assistants) | T-B |

**Judgment:** the census, code-quality review, engineering-head 1:1 and AI/ML diligence together read like acquisition or partnership diligence rather than a normal SaaS sale. No transcript says so outright.

### 9.2 Questions buyers actually asked
- **Netskope RFI gateway questions (Q7–Q18):** Q7 OBO vs autonomous; Q8 zero standing access; Q9 policy expressiveness; Q10 scope to the task, not the user's full rights (confused deputy); Q11 self-escalation; Q12 tampering with others' privileges; Q13 toxic combinations; Q14 footprint/efficacy; Q15 adaptive access by risk/intent (answered "Partially"); Q16 vault credentials ("Partially"); Q17 multi-agent chain visibility; Q18 per-hop JIT + which NHI each agent used (T-C, 2026-08-29). Q7 intent: distinguish OBO from autonomous activity (`repo:docs/features/autonomous-multiagent-nhi.md:13-14`).
- **Zscaler's six:** how agents are identified; the minting process; how the token is downsized; why a stolen token is useless; SPIFFE; readiness for intent-aware authZ (T-C, 2026-09-11).
- **Expected enterprise probes:** scalability ("what if there's 1m request flowing"), test quality, latency (T-C).

### 9.3 Competitors and precedents
| Name | Positioning (as stated) | Reliability |
|---|---|---|
| **Zscaler** | AI Broker (inline on MCP and A2A), Agent Registry, AI Access Graph (June 2026), partners with Oasis for NHI. Recommended line: overlap in ambition, difference in layer; do **not** pitch "we have A2A, you don't" | Prior session web research (T-C), not re-verified |
| **Uber** (precedent) | SDK-based STS + actor chain + SPIFFE for trusted in-house agents | MEM/uber-agentic-auth-precedent.md:11-15 |
| **agentgateway (solo.io)** | Open-source OBO / token exchange / ID-JAG | T-A, 2026-08-03 (drove D3) |
| **LLM/agent gateways** (Portkey, LiteLLM, Kong, Lakera, Lasso) | Infra-focused or point tools, different buyer | BP:331-343 (LLM-gateway blueprint) |
| **IGA tools** (SailPoint, Saviynt) | Better at un-instrumented discovery, prebuilt compliance, role-separated approvals (June self-assessment) | T-A (June GTM Q&A) |
| **Kore.ai / AA** | Partners or customers, **not** competitors | T-A, 2026-07-25 |

### 9.4 Differentiators (as pitched) with the code check
1. **Gateway-first for agents you don't own or trust.** Real, but agents still have to route through the gateway and forward the OBO + assertion (GG:1008).
2. **Authorizes the delegated action, not just access** ("which agent can reach which resource" vs "this call on behalf of this human") (T-C, 2026-09-11). The PDP sees action, resource, root type/verified, actor and a flat `argumentsFlat` string; **no purpose or intent** (GG:1122-1130).
3. **Per-hop OBO with human-rooted, integrity-checked `act_chain`.** Real (GG §5.8-5.9); on the wire only for A2A.
4. **One protocol-neutral spine; new platforms become adapters** (GG §1). Real.
5. **Governance depth for CISO/auditor** (D3). Real surfaces; data trust weakened by audit drops and tenant gaps.

### 9.5 Gaps buyers probe (what is true today)
| Probe | True today | Cite |
|---|---|---|
| Is SPIFFE live? | **No.** Seam only; `identity_source` hard-coded `KEYCLOAK` | GG:425-429 |
| Proof-of-possession / DPoP / mTLS? | **No.** `cnf.workload_id` vs assertion `azp`; bearer, human token accepted | GG:419-420 |
| Do you block data leaving? | **No.** Observe-only; egress coverage always 0 | GG:839-847 |
| Autonomous agents? | **OBO-only in practice**; `/a2a` can't form an NHI root; none ever live | GG:328, :503 |
| Is it really Cedar / default-deny? | Cedar-like **regex subset**; ignored heads; demo grant wide open | GG:539-543, :645-651 |
| Intent-aware? | No purpose signal anywhere on the path | GG:1122-1130 |
| Admin plane security? | `/api/admin/**` unauthenticated; header-chosen tenant also overrides the token's tenant on the data plane | GG:48, :446 |
| JIT credentials / vault? | Static secrets, AES-GCM in Postgres; OBO never sent to MCP | GG:767-769, :778 |
| Revocation / HA? | Per-JVM; no `jti` check on `/a2a`; no container packaging | GG:508, :512, :940 |
| 1M requests? | ~12 ms/hop overhead, but fully blocking; ~33 concurrent journeys exhaust threads (inferred) | GG:1081-1087 |

**Judgment:** the story that holds up in the room is the A2A per-hop OBO with a human-rooted, integrity-checked chain plus the `cnf` check, shown live. The weakest ground is any claim about policy strength ("Cedar", "default-deny") in the live tenant, autonomy, SPIFFE, PoP or enforcement. Pre-call prep already flagged SPIFFE/PoP as "ready, not shipped" (T-C); it did not yet know the policy-engine and admin-plane gaps, which the grounding work found on 2026-09-25/26.

---

## 10. The demo story

### 10.1 The cast
| Piece | Role | Cite |
|---|---|---|
| Agentic Console (:4100) | Branded chat front door; human signs in via Keycloak; its Claude model calls gateway tools and agent skills; shows ✅/🚫 per call and a Journey view | `ws-agentic-console/README.md:3-30` |
| Advisor (:11001, `advisor.analyze`) | Orchestrator; its model picks specialists | `a2a-sample-agents/advisor.py:1-29` |
| Market-data (:11002), Fundamentals (:11003), News (:11004) | Specialists calling Alpha Vantage MCP tools via the gateway; can hand off to `news.sentiment` | `a2a-sample-agents/README.md:14-27` |
| Gateway (:9492) | `/a2a` + `/mcp` on one spine | GG:43-62 |
| Keycloak realm `ws-gateway` | Human login + one client-credentials client per agent | MEM/financial-scenario-demo.md:17 |

Agents are real a2a-python SDK agents with an Anthropic brain, model from `AGENT_MODEL`: code default `claude-sonnet-5`, `start-agents.sh` default `claude-haiku-4-5`, which is what the demo runs (`a2a-sample-agents/agent_brain.py:29`; GG:980; MEM/financial-demo-run-gotchas.md:13).

### 10.2 The narrative and what each beat proves
| # | Beat | Proves | Caveat (true today) |
|---|---|---|---|
| 0 | Admin onboards agents by URL; they start PENDING; profiles + policies granted | Nothing trusted by default | PENDING not enforced on `/a2a` (GG:291) |
| 1 | Human signs in, asks "Analyze NVDA — price, earnings, news" | A verified human is the root | The gateway sees the console LLM's paraphrase, not the human's words (GG:992-993) |
| 2 | Console → advisor (A2A): identity, profile, PDP, 120 s OBO scoped to advisor | Just-in-time, per-call access | — |
| 3 | Advisor fans out to 1–3 specialists in parallel | Emergent DAG, each hop separately authorized | Edges are declared in code; the model picks among fixed edges (`advisor.py:12-19`) |
| 4 | Each agent sends `X-Agent-Assertion`; stolen OBO → -33016 | Sender constraint: an OBO presented by a different agent is refused (bearer-level, not PoP, not replay-proof; the intended holder can reuse it for its 120 s life) | Any IdP token with an `azp`, including a human's, passes as the assertion; no check on `/stateless/mcp` (GG:419, :420, :423) |
| 5 | Specialists → Alpha Vantage (MCP); toolbox from profile-filtered `tools/list` | Least privilege is visible per agent (trace `b3dd985136b3`, MEM/agent-toolbox-autodiscovery.md:13) | OBO is audit-only on MCP; downstream gets the static credential (GG:485, :778) |
| 6 | Answer + Journey + View Trace / View DAG / OBO receipt | Full attribution | DAG edges inferred by name (GG:898) |
| 7 | Deny moment: unprovisioned identity/tool refused (`Agent Console → alphavantage` DENIED, MEM/financial-demo-run-gotchas.md:19) | Default-deny on screen | Denials come mostly from **profiles** (inferred); the live policy grant is wide open (§5.3); an A2A deny renders ✅ in the console |
| 8 | Egress classification tags | DLP-style visibility | Observe-only |
| 9 | Autonomous variant (`AGENT_AUTONOMOUS=1`, `run_autonomous.py`) | Q7 answer | **Scaffolded, never proven**; A2A roots at an unverified human (`a2a-sample-agents/AUTONOMOUS_DEMO.md:89-97`) |

### 10.3 Live vs scripted, and preconditions
- **Live:** human login, LLM-chosen fan-out, real Alpha Vantage data, per-hop PDP + mint, `cnf` check, profile-filtered discovery, audit, Journey. Full-governance e2e verified via `pdp_audit_log` on 2026-08-06 (MEM/financial-scenario-demo.md:13).
- **Pre-arranged:** A2A edges, demo policies/profiles in `amitdev.local`, console skill `advisor.analyze` (`ws-agentic-console/config/a2a-skills.json`).
- **Cosmetic:** console branding; the gateway keys entitlement off the verified `azp` (`agent-console` local, `claude-desktop` cloud) (MEM/console-identity-verified-azp.md).
- **Run gotchas:** agents must run under `.venv03` or silently lose their LLM; fresh console login after any gateway restart (MEM/financial-demo-run-gotchas.md:13-17). The memory's "console must be branded claude-desktop" precondition is stale for local (identity is `agent-console`).
- **Deployment:** agents as four containers on a GCP VM, firewall open to `0.0.0.0/0` on 11001-11004 (`a2a-sample-agents/DEPLOY.md:1-21`); gateway on Azure behind `amitdev.whiteswansec.io` (`ws-agentic-console/config/environments.json`).
- **Which code runs where.** Local = this repo (`AzureAdWsIntegration`) + the vanilla `ws-gateway-dashboard` + a local Keycloak (`environments.json`, env `local`). Cloud (`amitdev`) = the **prod `backend/ui-host` jar** on an Azure VM, upgraded with the company's `upgrade.sh`: "deploy the jar on my stack (the vm is on azure)" (T-B, 2026-08-10), with the ws-react UI and its own Keycloak at `auth-amitdev.whiteswansec.io` (`environments.json`; MEM/ciso-dashboard-backend.md:29). All customer-facing screenshots and the enterprise PDF came from the cloud UI (MEM/gateway-enterprise-doc-build.md:17). The two copies have drifted: "~40 local-only / ~22 prod-only files" (MEM/gateway-two-repos-local-prod.md:13). **So what buyers saw may behave differently from what this brief describes.** Convergence is an open question (§13 Q19).
- **Demo network posture (security).** The gateway calls the agents over **plain HTTP on a public IP** (`http://<public IP>:11001-11004`), so A2A OBO bearer tokens cross the internet unencrypted (`DEPLOY.md:7-11`). The firewall accepts `0.0.0.0/0` (`DEPLOY.md:16-20`). The agents' Keycloak client secrets follow a predictable, name-derived pattern (`DEPLOY.md:22-23`; value not reproduced). Agents and the console share one VM (`DEPLOY.md:4`). A buyer running a packet capture or pentest against the demo would find all of these. Fix: TLS on agent endpoints, allow-list the gateway's egress IP, random client secrets.
- **Legacy billing/refund demo is broken** (imports a removed function) (GG:1004).

**Judgment:** before the next buyer demo, re-scope `financial-desk-grant`, re-enable the DEFAULT guardrails, and fix the console's A2A deny rendering, or the "deny moment" and "default-deny" claims rest on the wrong layer.

---

## 11. Senior engineering assessment

### 11.1 Strengths (keep these)
- **One protocol-neutral spine** (GG §1-2). A2A was added as an adapter reusing PDP, STS, audit and egress, with real but contained additions: a SKILL leg in the spine (4 near-duplicate legs in total, GG:1063), `skillInvocation` in the context builder (GG:292), and `cnf` for `a2a:` scopes (GG:420). The A2A commit `851d692` touched 55 files, 346 lines of them in `HopOrchestrator` (git). **Judgment:** the most valuable engineering asset.
- **Real per-hop delegation tokens** with per-tenant RSA keys, flat `act_chain` + RFC 8693 `act`, trace ids, capability scope, `cnf` (GG §5.8; `SRC/sts/service/StsService.java:50-123`).
- **Chain-integrity invariants** that fail closed (GG §5.9).
- **Fail-closed PDP and mint on the main path** (GG §6.3; `HopOrchestrator.java:424-437`).
- **Production-shaped key lifecycle** (ACTIVE → RETIRING → RETIRED, encrypted private JWK) (GG §5.11).
- **Cheap governance:** ~12–13 ms p50/hop; PDP ~0 ms (GG §13(d)).
- **No LLM on the request path** (GG §11). Deterministic and auditable.
- **Decision attribution** (`pdp_policy_id`, `pdp_reason`) powering CISO, compliance, access graph (GG §6.13, §9.1).
- **Well-tested deterministic egress classifier** (checksums, context scoring) (GG §8.2).

### 11.2 Correctness risks (the product does something other than it says)
1. Policy language silently widens permits (ignored heads, dropped fragments, `!`/`||`, effect from the first "permit"/"forbid" keyword anywhere incl. `@id`) (GG §6.2).
2. `resource == Tool::"x"` inside `when`/`unless` never matches (case bug), hidden by `/test` (GG:557).
3. `approvalStatus` is always UNKNOWN on MCP (210/210 rows) because `TenantContext` is null on the handler thread (GG:584).
4. Profile gate fails open for unresolved agents (GG:758).
5. Downstream MCP `isError`/`structuredContent` dropped → errors returned and classified as success (`SRC/protocol/mcp/outbound/service/McpClientService.java:228`).
6. `OboIntegrityException` and adapter failures escape unshaped, most likely HTTP 500 with no audit row; no `@ControllerAdvice` (GG:312, §14.17).
7. No hop-type vs descriptor-type check (GG §14.16).
8. A2A payload fidelity: first text part only; artifacts/data dropped; `role` rewritten at any depth (GG §4.2-4.5).
9. Registry/directory keyed by name only; MCP and A2A names evict each other (GG:345-346).
10. Blocked agents still get a successful `initialize` (GG:156).
11. Auth-config poll reads disabled rows and can flip mode to `none` (GG:362, :378).

### 11.3 Security posture
| Pri | Issue | Cite |
|---|---|---|
| P0 | `/api/admin/**` and `/api/mcp/**` `permitAll`; tenant and admin actor from headers; CORS `*` | `SRC/security/GatewaySecurityConfig.java:74-84`; GG:440, :944 |
| P0 | Direct tool bypass (Playground) | GG:777 |
| P0 | Data-plane `X-WS-Tenant` header read before the verified `ws_tenant` claim | `SRC/security/TenantResolver.java:33-46` |
| P0 | Policy engine widening; the customer policy reference teaches the ignored forms | GG:652; REF:36-38, :140 |
| P0 | Committed secret literals in `application.yml` (positions only: :47, :60-61, :113, :150 default); compiled-in encryption-key fallback | GG:942, :945 |
| P0 | Live tenant has no enabled forbid (guardrails disabled, probably by direct DB write) | GG:648 |
| P0 | Inconsistent doors: `/a2a` no status gate, no `jti` check, session keyed by caller `contextId`; `/stateless/mcp` no `cnf`, no human/NHI gate | GG:225, :291, :350 |
| P1 | IdP decoder: no issuer/audience validation, trust-all TLS for JWKS; STS decoder issuer check can't fail | GG:373, :376 |
| P1 | Assertion accepts any IdP token with `azp` | GG:419 |
| P1 | Custom attributes merged last can overwrite `rootVerified` (latent; 0 rows) | GG:605 |
| P1 | LLM policy auto-enabled via `/chat/save`; assistants send tenant PII and cross-tenant profiles to Anthropic | GG:667-675, :764 |
| P1 | Raw `Authorization` in context bag; raw JWT claims and full payloads in audit; no retention | GG:184-189, §9.3 |
| P1 | Auth mode `none` before app-ready → anonymous `/a2a` | GG:352, :363 |
| P1 | Public Agent Card lists all tenants' skills | GG:269 |
| P1 | Cloud demo: A2A OBOs sent to agents over plain HTTP on a public IP; agent ports open to `0.0.0.0/0`; predictable agent client secrets; agents and console on one VM | `a2a-sample-agents/DEPLOY.md:4, :7-11, :16-23` (§10.3) |
| P2 | Per-JVM revocation and rotation; public JWKS likely empty; classifier has no size cap or regex timeout | GG:508, :525, :804 |

**Judgment:** with the P0 set as-is, the tenant boundary is a convention, not a control, and the live tenant's policy boundary is effectively "root verified, therefore allow."

### 11.4 Scalability, operability, tests, debt
- **Scale:** single instance by decision; per-JVM revocation, keys, policies, rule caches, registry, A2A directory, MCP sessions, in-flight state; sticky MCP sessions; fully synchronous A2A holding a thread per subtree; no deadline propagation; DB reads on the hot path (STS decode builds a new decoder per token); audit queue 2000 entries, drops on overflow (`SRC/audit/config/AuditAsyncConfig.java:17-28`) (GG §4, §5.10-5.11, §13(d)).
- **Operability:** fat jar co-hosted with legacy Azure/K8s/OPA modules (legacy K8s job every 20 s); no Dockerfile/k8s/helm; `ddl-auto: update`; STDERR logs only; no Micrometer/OTLP; **no SIEM export**; no retention; rejections all HTTP 200 (GG §10, §9.7).
- **Tests:** unit-only: 53 gateway test files (plus 1 app-level `contextLoads`, GG:1244) vs 272 gateway main `.java` files (`find`, 2026-09-26); no Spring context/HTTP/DB tests. Untested: `HttpMcpAuditFilter`, `A2aInboundController`, `TenantResolver`, the decoders, `AgentAssertionVerifier`, PDP head evaluation, native SQL, controllers, LLM assistants. One test pins a fail-open behaviour (`repo:src/test/.../pdp/service/CedarPolicyEngineTest.java:90-102`) (GG Appendix B). Whether a clean `mvn clean test` is green was not checked (read-only).
- **Debt:** regex engine with three effect detectors that can disagree; `HopOrchestrator` 1463 lines with 4 near-duplicate legs (:231, :515, :809, :1070); `GatewayAuditService` 2201 lines; duplicated Human/NHI services and compliance copies; dead code (`CustomAttributeProvider` SPI, 9 never-emitted event types); hand-rolled transports; stale docs (GG §13(c), §14.34-39).

### 11.5 Prioritized actions

**FIX-NOW** (mostly small and local; before anyone outside the team runs it)
1. Make the policy engine **reject** syntax it doesn't understand (or implement `in` heads), take effect from the statement keyword only, fix the `when` case bug; re-validate stored policies and re-scope `financial-desk-grant`.
2. Authenticate the admin plane (OIDC resource server + admin role, tenant from token, CORS restricted); remove or govern the Playground bypass.
3. On the data plane, the verified `ws_tenant` claim/issuer mapping must beat `X-WS-Tenant`.
4. Rotate and remove committed secrets; fail startup when the encryption key is unset.
5. Re-enable the lineage guardrails in `amitdev.local`; stop the auth poll reading disabled rows; deny (not `none`) before app-ready.
6. Same gates on all three doors: status gates and `jti` revocation on `/a2a` and `/stateless/mcp`, `cnf` on stateless, revocation keyed on identity not `contextId`.
7. Propagate `TenantContext` to the MCP handler thread; profile gate denies unresolved agents.
8. Stop swallowing downstream `isError`; shape `OboIntegrityException`/adapter errors into audited denials.

**NEXT** (first real customer deployment)
1. Least privilege on the MCP wire: forward the OBO to MCP servers that accept it, or broker a JIT scoped credential (platform Vault is a candidate); add parent→child scope down-scoping.
2. Token validation hardening (iss/aud, standard TLS, assertion audience/grant checks, JWKS cache, fix public JWKS).
3. Tenant-scope all in-memory state and audit queries; namespace MCP vs A2A names.
4. Packaging and ops: container + helm, Flyway, Micrometer metrics (latency, mint rate, allow/deny, audit drops), OTLP traces, CEF/syslog SIEM export of both ledgers.
5. Audit durability (backpressure/outbox for decision rows), retention, composite/tenant indexes, decision-time timestamp.
6. Integration tests over the three doors, tenant resolution and decoders; policy-grammar rejection suite; delete the fail-open test.
7. Human approval for LLM-authored policies; PII minimization in assistants.
8. Stop persisting raw credentials/claims; retention class for full payloads.

**LATER** (scale and depth)
1. Multi-instance: shared revocation/key/policy state with pub/sub; persisted A2A skills; stateless-first MCP.
2. Non-blocking A2A fan-out with deadline propagation and cancellation.
3. Replace the regex engine with a real grammar/AST or re-evaluate `cedar-java` with context records; policy versioning; obligations.
4. Collapse the 4 orchestrator legs; split `GatewayAuditService`; extract legacy modules.
5. A2A completeness (streaming, `tasks/*`, artifacts, card refresh).
6. Egress enforcement (SHADOW → LIVE) after size caps/regex timeouts; SPIFFE/DPoP; NHI-rooted autonomous mode.
7. Hygiene: `mvn clean` (clears the 1078 `target/` copies); remove the 2 graph-cache copies, the 20 `.git/index N` copies and, after a reachability check, the 3 broken `* 2` git refs (§7); dead config/code, stale docs, pom description ("Demo project for azure ad integration", GG §10).

### 11.6 Sequenced roadmap and gates
The FIX-NOW/NEXT/LATER lists above are engineering fixes. This maps them onto the PRD's phases and the competing bets.

| PRD phase | PRD scope | Status today | Main open items | Depends on |
|---|---|---|---|---|
| **Phase 0** Refactor (PRD:202) | Spine + MCP adapter, no behaviour change | **Done** (Stage 0, 7 characterization tests; MEM/agentic-gateway-staging-roadmap.md:13) | — | — |
| **Phase 1** Agentic-auth core on MCP (PRD:204) | STS, OBO, down-scoping, keys/JWKS, `act_chain`, per-hop PDP + audit, JIT token injection, fail-closed | **Mostly done** | Down-scoping not built (C3); token not injected on MCP by decision (D11); fail-closed partial (P5); JWKS likely empty (C4) | FIX-NOW 1, 5-8 |
| **Phase 2** A2A + production completeness (PRD:207) | A2A, discovery/sync, scoping model, invariants + store/templates/approval gate, brokering, observability, admin APIs + dashboard, rate limiting, HA, tenant isolation | **Partial** | Platform sync (F3), templates (E6), approval gate (E7), rate limiting (J4), HA (J5), tenant isolation (J6), metrics (H4); Stage 2.5 #5 "distributed enforcement state" still open (MEM/agentic-gateway-staging-roadmap.md) | FIX-NOW 2-3; NEXT 3-5; §11.7-11.8 decisions |
| **Phase 3** Hardening + reach (PRD:210) | SPIRE, AAuth + PoP, SDK, extra adapters, tamper-evident audit, simulation | **Not started** (only the SPIFFE seam and `/test` dry-run exist) | All | Phase 2 |

**Competing bets outside the PRD phases** (none has a written gate today):
- **Intent-aware authZ**: started 2026-09-25, research-first (MEM/intent-aware-authz-mindset.md:11-17). Depends on FIX-NOW 1 (policy engine) per §12.
- **Autonomous (NHI-rooted) chains**: deferred, Options A/B (MEM/autonomous-multiagent-nhi-gap.md; `repo:docs/features/autonomous-multiagent-nhi.md`). Customer trigger: Netskope RFI Q7 (§9.2).
- **Platform sync (Kore.ai/AA)**: seam only (F3). Trigger: a signed platform partner.
- **SIEM export**: not built (H3-H5). Trigger: any enterprise security buyer; the platform already speaks CEF/Syslog (PDF p.74).
- **OBO on the MCP wire / JIT credentials**: deferred (D11, G2-G3; §13 Q12).

**Judgment: proposed gates.**
- *Ready for first external POC:* FIX-NOW 1-8 done, the demo network fixes in §10.3 done, the live tenant's policies re-scoped, and the deployment model for the POC chosen (§11.7).
- *Ready for first paid customer:* NEXT 1-8 done, plus a decided tenancy model (§11.8), a retention policy (§11.9), CI and a release/upgrade path (§11.11), and a support model (§13).
- *Order of bets:* the policy-engine and admin-plane fixes before intent-aware authZ; autonomous mode next only if Netskope (or another buyer) makes Q7 a deal condition.

### 11.7 Deployment model
**Today (facts).**
- The gateway is one fat jar run with `mvnw`; the repo has no Dockerfile, compose, k8s or helm files (GG:940). The sample agents do have a `compose.yaml` (GG:940) and run as four containers on a GCP VM (`a2a-sample-agents/DEPLOY.md:1-4`).
- There is one cloud environment, `amitdev`, on an Azure VM, with its own Keycloak at `auth-amitdev.whiteswansec.io` (`ws-agentic-console/config/environments.json`). It runs the prod `ui-host` jar, not this repo (§10.3; T-B, 2026-08-10).
- Single instance by decision (T-D, 2026-04-17; §11.4).

**The company's pattern.** WhiteSwan deploys one full stack per customer. The owner, 2026-04-14: "I deploy the entir tech stack eg: code, postgres db for each companies separately" (T-A), described in the same exchange as "own app instance + own DB + own Keycloak realm" (T-A, assistant). The platform doc's default super-admin `wsadmin` is "generated automatically during stack deployment" (PDF p.15).

**Options considered in April 2026** (for Secure OS, before WAAG): (A) shared DB with row-level isolation; (B) a separate DB per tenant with a shared app; (C) a full stack per tenant, the WS pattern. The assistant recommended "Option B — Separate DB per tenant, shared app" (T-A, 2026-04-14). The owner's own tenancy strategy PDF proposed tiers: Standard (shared app, isolated DB), Premium (dedicated VPC), On-Prem "for government/defense" (T-A, 2026-04-14, assistant summary of the PDF). None of this was decided for WAAG.

**Settings that only work on localhost today (block hosted use until changed):**
- RFC 9728 protected-resource metadata has the resource hard-coded to `http://localhost:<port>` (GG:529).
- `ws.gateway.sts.issuer-base` defaults to `https://gateway.local`, a code default that is not a real URL (GG:958, :368). It must be set per deployment.
- The admin dashboard is hard-coded to `http://localhost:9492` and tenant `amitdev.local` (`ws-gateway-dashboard/js/api.js:8-26`).
- One global effective auth mode (GG:360-361; §11.8).

**Judgment.** Today's code (single instance, per-JVM state, row-level tenancy with header overrides) fits a **stack per customer** far better than a shared SaaS: per-customer stacks make most of the tenancy gaps moot, at the cost of N stacks to run. A shared SaaS needs NEXT 3 and LATER 1 first. The choice is open (§13 Q21).

### 11.8 Tenancy model
- **What a tenant is.** A row-level `ws_tenant_name` on every gateway entity, carried in a ThreadLocal `TenantContext` and stamped by a JPA listener. There is no Hibernate multi-tenancy, no filter and no row-level security; all scoping lives in hand-written queries (GG:432-436).
- **How each door picks the tenant.** Admin/`/api/mcp`: the `X-WS-Tenant` header, required. `/mcp` and `/a2a`: header → `ws_tenant` claim → issuer mapped via `gateway_auth_config` → `"default"`. `/stateless/mcp`: header → issuer lookup → `"default"`, no claim step. PDP: a null tenant means the union of every tenant's policies (GG:440-444). The header beats the verified claim (GG:446).
- **Provisioning.** There is no tenant-creation step. A tenant comes into being on first use: the PDP lazily loads its policy slot and seeds the DEFAULT guardrails on the first request (GG:628, :633; MEM/agentic-gateway-staging-roadmap.md, "lazy-seed runs DB writes … on first request"). STS signing keys are per tenant (GG §5.8). The issuer→tenant mapping is the tenant column of a `gateway_auth_config` row (GG:441).
- **Different IdPs per tenant are not really supported.** The effective auth mode is one global value, taken from the first enabled `gateway_auth_config` row of any tenant (GG:360-361), and one global IdP decoder serves all tenants (§3 P11).
- **State that is not tenant-scoped:** the capability registry, the A2A directory, MCP sessions, the custom-attribute cache, `getAllProfiles()`, in-flight requests, the policy-LLM metadata cache, and the public Agent Card (GG:448-456).
- **The dashboard is single-tenant**: tenant `amitdev.local` is hard-coded (`ws-gateway-dashboard/js/api.js:8-26`; MEM/agentic-gateway-staging-roadmap.md, "(G) dashboard is single-tenant").
- **What was planned vs built.** April's recommendation was a DB per tenant (Option B above), and the Secure OS fork built a `TenantDatabaseRouter` (`AbstractRoutingDataSource`) to per-tenant Postgres (T-D, 2026-04-17). Secure OS was dropped on 05-22 (§7). **The gateway today is row-level on a shared DB (Option A).**

**Judgment: the fit question.** If WAAG ships as a stack per customer (the WS pattern), the row-level gaps matter mostly for business units inside one customer, and tenant-scoping the in-memory state can wait. If WAAG ships as shared SaaS, FIX-NOW 3 and NEXT 3 become blockers and Option B deserves a second look.

### 11.9 Data flows and privacy
What data the product touches, where it goes and how long it stays. "Product" = ships to a customer; "demo only" = the console and sample agents.

| Component | Data item | Destination | Stored where, how long | Customer control today |
|---|---|---|---|---|
| Gateway data plane (product) | MCP tool arguments and full responses | Downstream MCP server (customer-owned or third-party, e.g. Alpha Vantage), with the gateway's stored credential | Full and untruncated in `gateway_audit_log` (`CLIENT_TOOL_INVOCATION`); **kept forever**, no retention job (GG:887, :892) | None (egress is observe-only; no redaction setting) |
| Gateway data plane (product) | A2A `input` text + OBO bearer token | Downstream agent (`Authorization: Bearer`) (GG:303) | PDP request JSON, ≤2000 chars per string (GG:886); forever | None |
| Gateway auth (product) | Raw JWT claims; human profiles with roles, `custom_claims`, `last_jwt_claims` | Stays in the gateway | `OAUTH2_AUTH_SUCCESS` audit rows and the human registry (GG:890, :712); forever | None |
| Gateway → IdP (product) | JWKS fetch (no user data) | Customer IdP | — | Trust-all TLS on the fetch (GG:376) |
| Downstream secrets (product) | MCP API keys, headers, URL keys | Downstream MCP servers at connect time | AES-GCM in Postgres, masked on read; key falls back to a compiled-in constant (GG:768-769, :942) | Set the encryption key |
| Admin assistants (product, admin-time) | Admin prompt; human **names, emails, roles, custom claims**, block reasons; NHIs; capability, policy and (profile AI) every tenant's profile names | **Anthropic** (`claude-haiku-4-5` hard-coded, one shared key) | Prompt logged at INFO to STDERR; chat audited truncated to 500 chars (GG:973-977, :891) | Only by removing the key; no BYOK |
| Egress classifier (product) | Response content | Stays in the gateway (no LLM, GG:969) | Classification rows; forever | Rule library (observe-only) |
| Agent console (demo only) | User chat and tool results | **Anthropic** via the SDK (`ws-agentic-console/src/llm.js:2, :110`) | Console process | — |
| Sample agents (demo only) | Inbound task text and tool results | **Anthropic** tool loop (GG:980) | Agent process | — |

**Contradiction with the platform model.** The WhiteSwan platform runs AI tasks BYOK, on "your provider account settings, retention policies, and billing" (PDF p.88). The gateway's assistants use one WhiteSwan-configured Anthropic key and send tenant PII (GG:973-977). True today: no BYOK in the gateway. Residency, DPA and subprocessor questions are in §13.

### 11.10 Onboarding a customer (runbook with known gaps)
Netskope explicitly asked for this: "Could you provide a one-pager on MCP server and IDP onboarding details?" (T-C, 2026-09-03).

1. **Configure the IdP** via `/api/admin/auth-config` (CRUD, validate, discover, refresh-jwks) (GG:533). Gaps: one global auth mode for all tenants (GG:360-361); the 60 s poll reads disabled rows (GG:362); a change to the JWKS URI alone is never applied (GG §14 item 27); the admin API is unauthenticated (§11.3).
2. **Map issuer → tenant**: the tenant column of the `gateway_auth_config` row (GG:441). Gap: the dashboard is hard-coded to one tenant (§11.8).
3. **Add MCP servers** (`gateway_server_config`); secrets are AES-GCM encrypted and masked; connecting auto-registers tools, resources and prompts (GG:767-770). Set the encryption key first, or the compiled-in fallback is used (GG:942).
4. **Create one IdP client-credentials client per agent** (the demo seeds 4 confidential Keycloak clients, `a2a-sample-agents/DEPLOY.md:22-23`). Who does this (customer IAM or WhiteSwan) is open (§13).
5. **Register A2A agents** by card URL or card JSON; new rows start PENDING (GG:333-339). Gaps: skills live in memory only, are re-fetched only at gateway startup, a failed fetch leaves **zero skills**, and there is no periodic refresh (GG:343-344); SKILL allow-sets go stale after ingestion (GG:345).
6. **Approve agents, assign capability profiles, write policies** and review them with `/check` (GG §7.1-7.2, §7.5; GG:639). Gaps: the engine ignores `in` heads (§5.3); the "Read-Only" profile preset grants no snake_case tools (§6).
7. **Integrator contract**: route agents through the gateway, forward the inbound OBO as `Authorization`, send the agent's own token as `X-Agent-Assertion` (`a2a-sample-agents/README.md:50-58`). A profile change reaches an agent's toolbox only on agent restart (README.md:44-46).
8. **Verify** with the audit trace, View DAG and OBO receipt (GG §9).

**Restart-sensitive steps:** agent restart after profile changes (step 7); gateway restart re-fetches A2A skills (step 5) and then needs a fresh console login (MEM/financial-demo-run-gotchas.md:17); schema changes apply on restart via `ddl-auto` (GG:937). **Time-to-value has never been measured** (§13).

### 11.11 Operating it
- **Build and CI.** Built with `./mvnw`; the working rule is `test-compile` or `test` before hand-off (MEM/verify-test-compile.md:15). **No CI config exists in any of the four repos** (read-only check 2026-09-26: no `.github`, `.gitlab-ci.yml`, `Jenkinsfile` or `azure-pipelines.yml` in `AzureAdWsIntegration`, `ws-gateway-dashboard`, `ws-agentic-console`, `a2a-sample-agents`).
- **Release and upgrade.** A jar. The cloud stack is upgraded by running the company's `upgrade.sh` with a new `ui-host` jar on the VM (T-B, 2026-08-10). No versioning or release policy is written down.
- **Schema.** `ddl-auto: update`, no Flyway/Liquibase, hand-run SQL in `repo:docs/migrations` (one file today) (GG:937); drop/recreate is the current working rule (MEM/schema-drop-recreate-ok.md).
- **Backup, retention, DR.** Nothing in the repo; no retention or purge job for either audit table (GG:892).
- **Logging and monitoring.** STDERR console log only; audit package and MCP SDK at DEBUG (GG:943). No Micrometer or OTLP (GG:935). The Ops dashboard endpoints `/api/admin/dashboard/{summary,in-flight,health,pdp}` back the Health and In-Flight pages; in-flight exposes every tenant's tool arguments (GG:923). The MCP health check is passive (GG:776).
- **Background jobs.** 7 `@Scheduled` jobs on one default scheduler thread, including the legacy K8s job every 20 s (GG:941).
- **Restart-sensitive state.** Revocations, key rotation, policy and rule caches are per-JVM (GG §14 item 24); A2A skills (GG:344); console sessions; agent toolboxes (§11.10).
- **Time zone rule.** Time-based policies evaluate in the JVM default zone; the cloud host is UTC, so gateway hour = IST − 5:30, and `dayOfWeek` is an uppercase, case-sensitive match (MEM/gateway-clock-is-utc.md:11-17).
- **Demo runbook.** Agents under `.venv03`; fresh console login after a gateway restart (MEM/financial-demo-run-gotchas.md:13-17); plus §10.3.
- **Open:** support model, SLA, on-call ownership, incident handling (§13).

### 11.12 Runtime and third-party dependencies
| Dependency | Version / state | Role | What breaks if it changes | Owner | Platform reuse? |
|---|---|---|---|---|---|
| PostgreSQL | driver 42.7.4 (GG:935) | All state, both ledgers | Schema drift under `ddl-auto` | Whoever runs the stack | — |
| OIDC IdP | Only Keycloak exercised live (§3 P11) | Human and agent identity | IdP-neutral claim unproven for Entra/Okta | Customer | — |
| Anthropic API | `claude-haiku-4-5` hard-coded, one shared key (GG:973-977) | Admin assistants | Model retirement breaks all three assistants | Third party | **BYOK** (PDF p.88) |
| MCP Java SDK | `mcp-bom` 0.12.1 (GG:935) | `/mcp`, `/stateless/mcp`, outbound client | MCP session removal window opens July 2027 (PLAN:46) | Third party | — |
| a2a-java | 1.0.0.Final, Gson 2.11.0 (GG:935) | A2A wire | A2A spec churn | Third party | — |
| nimbus-jose-jwt | 9.37.3 (GG:935) | STS signing and validation | — | Third party | — |
| Spring Boot / Java | 3.3.4 / 17 (GG §10) | Runtime | — | Third party | — |
| Co-hosted legacy modules | Azure resourcemanager, msgraph, k8s client-java 22, OPA at `localhost:8181` (GG:935, :939) | Not used by the gateway; same JVM, same `permitAll` chain | Their committed credentials and scheduled jobs ride along (GG:941, :945) | WhiteSwan | Extract (LATER 4) |
| Prod `ui-host` + ws-react | Drifted copy (§10.3) | The customer-visible UI and cloud gateway | Buyers see a different build | WhiteSwan platform team | — |
| Platform Vault (OpenBao) | Not wired (PDF p.81-83) | — | — | WhiteSwan platform | **Yes** (G2/G3) |
| Platform SIEM forwarding (CEF/Syslog) | Not wired (PDF p.74, p.87) | — | — | WhiteSwan platform | **Yes** (H4/H5) |
| Platform built-in roles / RBAC | Not wired (PDF p.15) | — | — | WhiteSwan platform | **Yes** (admin auth, FIX-NOW 2) |

### 11.13 Team, knowledge and working model
**Where the product's knowledge lives:**

| Store | What | Version-controlled? |
|---|---|---|
| `docs/others/gateway-grounding.md` | The verified code facts this brief treats as ground truth | **No**: untracked (`git status`: `?? docs/others/gateway-grounding.md`) |
| `docs/features/*`, `docs/others/*` | Plans, PRD-adjacent design docs, policy reference | Yes (14 tracked files) |
| Memory files | 48 notes on decisions, bugs, demo gotchas (`/Users/amitprakash/.claude/projects/-Users-amitprakash/memory/`) | **No**: outside every repo |
| Build transcripts | The why behind decisions (T-A to T-D) | **No**: local JSONL files |
| PRD, blueprint | `Agentic-Gateway-PRD.md`, `LLM_Security_Gateway_Product_Blueprint.md` | Not in any gateway repo |

**Repos.** All four under `github.com/amit-ws/*`. Active branches on 2026-09-26: gateway `ai-summit-intentAuthZ`, dashboard `codex`, console and agents `main` (git).

**Working conventions.** The engineer writes and verifies; the owner reviews and commits by hand (MEM/dev-workflow-user-commits.md:11-15). Commit format "[Feature Stage N]: …", feature-level only (MEM/commit-message-format.md:14-26). `test-compile` before hand-off (MEM/verify-test-compile.md:15). UI changes are checked by the owner's eye (MEM/ui-changes-ask-user-to-check.md:11-15). Gateway work happens only in this repo (MEM/gateway-two-repos-local-prod.md:11).

**Risks.** One engineer and an AI pair built everything from the first MCP commit (owner, 2026-09-26). The repos live in iCloud-synced `~/Desktop`, which produced the 279 duplicate files and the git-internal copies (MEM/icloud-desktop-conflict-copies.md:11; §7).

**Judgment: hygiene actions.** Commit the grounding doc; move the repos out of iCloud Drive; add CI (at least `mvn test` on push); decide whether the memory notes' decision records belong in the repo. Repository and IP ownership (a personal-looking `amit-ws` account vs a company org) is open (§13).

---

## 12. Where intent-aware authorization fits (facts only, no design)

Intent-aware authZ is "the next major WAAG feature (started 2026-09-25)", research-first: "research how others do it first, no guessing/pretending" (MEM/intent-aware-authz-mindset.md:3, :11, :13-17). Branch `ai-summit-intentAuthZ` exists with 0 new commits (git). It is not in the PRD.

**What the product promise would need that is not there today (all facts from GG):**
- **No purpose/intent signal on the request path.** The PDP sees action, resource, root type and verification, actor, custom attributes and one flat natural-language field, `argumentsFlat` (GG §1 facts 3-4; GG:1122-1130).
- **The human's original request never reaches the gateway.** The console LLM paraphrases it into A2A `input`; the human's words stay in the console (GG:992-993, :1024).
- **The root question is not propagated across hops.** Each downstream hop sees only its own input (GG:327, :1127).
- **The minted token carries no purpose/intent claim** (GG §5.8).
- **No LLM, ML or classifier on the request path**; all three LLM assistants are admin-time only (GG §11, GG:969).
- **The engine returns ALLOW/DENY only**: no obligations, advice or step-up, so "escalate to a human" cannot be expressed (GG:573, :1052).
- **No request-side argument inspection**; egress classification is response-only and async (GG:1106; PPP:66-70).
- **The parent scope is never read**, so "this hop is outside the purpose of the parent task" has no hook today (GG:326).
- **Audit has no intent field**; the PDP ledger has no trace/session column (GG:865).

**Contradiction to keep in mind:** on the Zscaler prep the owner said "yes true we are already ready for Intent AuthZ" (T-C, 2026-09-11); later memory says not to pitch "we're already ready" (MEM/intent-aware-authz-mindset.md:16). True today: not ready; the list above is what is missing.

**Judgment (sequencing only, not design):** intent-aware authZ sits on top of the PDP and the chain. While the engine silently widens grants and `/a2a` skips status gates, a new intent signal would be layered on an enforcement base that doesn't yet do what it says. The FIX-NOW list is a prerequisite in practice.

---

## 13. Open strategic questions

**Customer and market**
1. **Who pays?** Agent-platform vendor (Kore.ai/AA/UiPath as partner/OEM), enterprise CISO deploying in front of platforms, or a strategic security vendor (Netskope, Zscaler)? This decides platform sync vs SIEM export priority.
2. **What is the Netskope deal** (commercial partnership, OEM, acquisition)? Were RFI Q7 ("Yes"), Q9 ("Cedar-based") and Q16 (HashiCorp Vault) sent as drafted, given the code facts?
3. **Which protocol will Kore.ai/AA/Netskope actually speak** to the gateway, if A2A was a POC stand-in ("We coded the multi agent thing with a2a thinking -> to prove the POC", T-C 2026-08-29)?
4. **Canonical name:** "WhiteSwan Agentic Gateway", "Agentic Auth Gateway", WAAG, or the dashboard's "WS Agentic Security Gateway"?
5. **Is the blueprint's platform vision** (Varden + Vigil + A2A + LLM Gateway, shared engine/console/identity) still the strategy after "varden, secure os etc doesn;t exist for us"?
6. **Pricing and packaging** for WAAG itself. The only datapoints are pre-WAAG reference points: the April 2026 blueprint's ACV ladder puts the MCP gateway (Varden) at "Customer adds Varden → $50K/year", with levers per-seat, per-million-tokens and feature tiers (BP:286-297); the owner's April 2026 tenancy strategy proposed deployment tiers Standard (isolated DB), Premium (dedicated VPC) and On-Prem (T-A, 2026-04-14, assistant summary). Still open: WAAG's unit of value (per agent, per hop/mint, per protected server, or per human seat) and which tier maps to which deployment model (§11.7).
7. **Is shipping the console as "Kore.ai Agent Console"** in external demos approved by Kore.ai?

**Product scope**
8. **Success metrics.** The PRD defines operational metrics only (PRD:167) and no targets. §5.5 lists the KPIs that can be computed today with their baselines. Still open: targets, business KPIs (POCs, deals, time-to-first-governed-call), and (Judgment) candidates such as standing credentials removed, median time to revoke a live chain, and evidence coverage per control.
9. **Non-goals.** The PRD has no non-goals section. Decided ones so far: SDK for launch, token-return model, STS token on the MCP wire, hand-assigned owner columns, blocking on LLM signals.
10. **Are the unmet P0s still P0** (mTLS/API-key caller auth, monotonic down-scoping, approval on every hop, un-bypassable path, fail-closed everywhere), or re-prioritized?
11. **Autonomous (NHI-rooted) mode**: committed next, ahead of intent-aware authZ? Option A (each agent roots at its own NHI) or B (propagated lineage + classification fix)?
12. **When does the OBO go on the MCP wire** for internal servers that can trust the gateway JWKS, and should the platform Vault supply JIT credentials for the rest?
13. **SDK:** is it still on the roadmap, with what trigger?

**Engineering and ops**
14. **Deployment topology:** single instance + warm standby, or N instances? This decides whether per-JVM state is FIX-NOW or LATER. See §11.7 for today's deployment facts.
15. **Is `/api/admin/**` protected by anything outside this repo** in any deployment (ingress, prod ui-host)? Prod is out of scope here.
16. **Policy engine:** grammar/AST rewrite of the in-house engine, or re-evaluate `cedar-java` with context records? What exactly was the "flexibility" requirement?
17. **Were the `amitdev.local` guardrails disabled on purpose**, and is the widened grant what buyers were shown?
18. **Raw payload retention** (superseding the honeypot fix): acceptable for SOC 2/SOX positioning without a retention/redaction policy?
19. **Does the out-of-scope prod repo** carry the same FIX-NOW issues, or does it already have SIEM, vault or admin auth that the enterprise doc implies? The cloud environment buyers saw runs that copy, which has drifted (~40 local-only / ~22 prod-only files, §10.3). What is the plan to converge (for example, redeploy the cloud environment from this repo), which copy will future demos use, and do the vanilla-dashboard defects in §6 also exist in the 13 React screens?
20. **Housekeeping:** clear the 1078 `target/` copies with `mvn clean` and the 2 `.code-review-graph/graph 2.db-*` sidecars before the next build? Also remove the 20 `.git/index N` copies (gateway) and `.git/index 2` (dashboard), and the 3 broken git refs `refs/heads/ai-summit 2`, `refs/remotes/origin/ai-summit 2`, `refs/remotes/origin/ai-summit-agenticAuthGateway 2`, once each ref's commit is confirmed reachable from a real branch? The memory note saying the graph-cache copies were removed is stale (§7).

**Ownership questions no available source answers** (the editorial review checked the PRD, BP:286-297, PDF p.12/15/86-88 and transcript searches for pricing, SLA, on-call, backup, on-prem and license, and found nothing gateway-specific)
21. **Hosting model for WAAG:** WhiteSwan-hosted SaaS, a stack per customer (the WS pattern), or customer-hosted/on-prem, and for which tier (§11.7)?
22. **Data residency, DPA and subprocessors:** which regions; is Anthropic (admin assistants) an acceptable subprocessor; is BYOK required, as on the platform (§11.9)?
23. **Retention and recovery:** retention periods for raw payloads and JWT claims; backup and DR targets (RPO/RTO) (§11.11)?
24. **Support model:** support tiers, SLA, on-call ownership and incident handling?
25. **Release and versioning policy,** and the upgrade path for customer-hosted installs?
26. **Vendor assurance:** WhiteSwan's own SOC 2 or pentest status as the vendor of a security product?
27. **Repository and IP ownership:** the personal-looking `amit-ws` GitHub account vs a company org; who else has access (§11.13)?
28. **Team plan:** headcount and hiring beyond the single engineer and AI pair?
29. **Per-agent IdP clients:** who creates and operates them for a customer, the customer's IAM team or WhiteSwan professional services (§11.10)?
30. **Tenancy model to commit to:** row-level (today), DB per tenant (April recommendation), or stack per customer (§11.8)?
31. **Time-to-value:** what is the target time from signature to first governed call, given the onboarding steps in §11.10?

---

## Appendix: contradictions between docs/pitch and code

"True today" is decided by the code facts in `gateway-grounding.md` unless stated.

| # | Topic | Docs / PRD / pitch say | Code says | True today | Cites |
|---|---|---|---|---|---|
| 1 | Policy engine identity | "Cedar-based" (REF:9); PRD "PDP (Cedar)" (PRD:73, :130); blueprint "single Cedar policy engine" (BP:11) | In-house regex evaluator for a Cedar-like subset; no Cedar library, schema, `\|\|`, `!` or parentheses | Code: not Cedar | `repo:pom.xml:324-326`; GG §6.1 |
| 2 | Group/server heads | REF examples use `principal in AgentGroup`, `resource in Server` (REF:36-38); blueprint uses `principal in Team`, `resource in Provider` (BP:96-108) | Both head forms ignored → policy applies to everyone/everything | Code | GG:549, :555; `SRC/pdp/service/CedarPolicyEngine.java:405-460` |
| 3 | "Never accidentally broader" | REF:140 | Ignored heads, dropped fragments, `!`/`\|\|` mis-evaluation widen permits; `financial-desk-grant` allowed `agent-console` 44× | Code | GG:563, :647-652 |
| 4 | Least-privilege demo policies | Each MCP leaf permitted by its own `fin-*` policy (MEM/financial-scenario-demo.md:13) | 2 enabled policies live; broad grant permits all under verified root; guardrails disabled | Code / live data | GG:645-651 |
| 5 | Per-tenant isolation | "one tenant's policies can never decide another tenant's request" (REF:11-12); PRD J6 | Header outranks verified claim; null tenant = union of all; several stores/queries unscoped | Code | GG:444-456, :904 |
| 6 | AI-authored policy review | "Nothing activates without admin review" (REF:13-14); diligence blurb "Nothing the AI writes goes live on its own" (T-B) | `/chat/save` saves `enabled=true` with no approval (dashboard itself uses `/check` + create) | Code (API) | `SRC/pdp/controller/PolicyController.java:389-397`; GG:667 |
| 7 | Token TTL | "configurable, default ~5 min" (PRD:114) | Fixed 120 s | Code | `HopTokenMinter.java:33` |
| 8 | Token injected downstream | P0 "Inject the minted JWT downstream" (PRD:159) | A2A only; MCP uses static brokered credential (deliberate, PLAN:42) | Code + build plan | GG:485, :778 |
| 9 | Token exchange | P0 "OBO / token exchange (RFC 8693)"; P1 pluggable Keycloak exchange (PRD:115, :121) | Self-signed RS256 with RFC 8693 `act` shape; no exchange grant | Code | GG §5.8; T-A 2026-08-03 |
| 10 | Monotonic down-scoping | P0 (PRD:116); V1 invariant (PLAN:71); memory "V1 complete" | Structural invariants only; parent scope never read | Code: not built | `OboInvariants.java:29-35`; GG:326 |
| 11 | Fail-closed everywhere | P0 (PRD:177); stage-1 LD4 | Null-tenant mint skip; `sub="unknown"` empty chain; unresolved agent skips profile; dropped fragments; `none` before app-ready | Code | GG:462, :467, :758, :563, :362 |
| 12 | Un-bypassable chokepoint | PRD:77; "every protected call is evaluated" (REF:3-5) | Playground direct call skips PDP/profiles; `tools/list` not policy-checked; `/a2a`, `/stateless/mcp` skip door gates | Code | GG:161, :777, :86-88 |
| 13 | Approval before any hop | P0 (PRD:145); stage-2 "already refused" | `/mcp` only; 8 PENDING A2A calls ALLOWed; `approvalStatus` UNKNOWN on MCP | Code | GG:291, :584 |
| 14 | Attribute-based only | P0 "no per-hop or per-agent rules" (PRD:133) | Per-agent heads supported and used live | Code | GG:646 |
| 15 | Deny response | "Deny → 403 + audit" (PRD:132) | Always HTTP 200: door-filter rejections as a JSON-RPC error; MCP PDP deny as a normal result with `isError=true` "[-33003] … Policy violation"; A2A deny as a FAILED Task | Code | GG:181, :205, :310 |
| 16 | Stateless / horizontal scaling | "Any instance can handle any hop" (PRD:76, :178) | Revocation, keys, policies, caches, registry, sessions per-JVM; single-instance by decision | Code | GG §14.24; T-D 2026-04-17 |
| 17 | Caller authentication | P0 mTLS / workload JWT / API key (PRD:107); Identities page mentions API keys (`ws-gateway-dashboard/index.html:184`) | JWT + assertion only; no mTLS or API-key inbound | Code | GG:359-365, :425 |
| 18 | Sender constraint strength | "stolen OBO is useless" (`a2a-sample-agents/README.md:55-58`); "no stealable token in the agent's hands" (PLAN:34) | Any IdP token with `azp` passes; no `cnf` on stateless; agents do hold 120 s OBOs | Code | GG:419-422, :1008 |
| 19 | SDK-less, no agent change | PRD:3, :31 | Agents must forward OBO + send assertion for lineage and `cnf` | Partially true | GG:1008; `agent_identity.py:78-89` |
| 20 | Stateless MCP "Delta 2" | `_meta` + `server/discover` (PLAN:44-52); memory marks DONE | `_meta` dropped at handler; `server/discover` only a comment | Code: partial | GG:89; `HttpTransportConfig.java:84` |
| 21 | Audit "honeypot" fix | Plan: never persist raw value (PPP:40-41, :52-54); Javadoc says redacted | Raw args/responses stored in full; reversed 2026-08-17 | Code | GG:848, :887 |
| 22 | Egress enforcement | Plan V2 block/redact (PPP:56-64); CISO "Configure controls" for redact/block | Observe-only; coverage always 0 | Code | GG:839-847; `cisoDashboard.js:497, :513` |
| 23 | Tag propagation | V1 "observe, tag, propagate" (PPP:16-17) | `provenance_categories` never written | Code | GG:840 |
| 24 | SIEM / unified audit | Blueprint: all events into Vigil (BP:242, :275-276); enterprise doc outline has "SIEM CEF"; platform CEF/Syslog (PDF p.74) | Gateway has no SIEM/syslog/OTLP/webhook export; CSV only | Code (for the gateway) | GG:926 |
| 25 | Secrets "vaulted" | Enterprise doc "vaulted" language; RFI Q16 draft "HashiCorp Vault supported today" (flagged as overreach) | AES-GCM in Postgres; compiled-in key fallback; no vault code | Code | MEM/gateway-enterprise-doc-build.md:24; GG:768, :942 |
| 26 | Latency / threads | "sub-millisecond overhead" (T-C 2026-09-08); "isn't holding a thread" (T-C 2026-09-11) | PDP ~0 ms, total ~12–13 ms p50/hop; fully blocking | Code | GG:1072-1086 |
| 27 | Netskope Q7 (OBO vs autonomous) | Answered "Yes"; branch on classification in policy, step-up possible (T-C 2026-08-29) | No NHI root ever live; PDP never reads `tokenType` (branch via `rootType` instead); no obligations/step-up | Code: partial, undemonstrated | GG:328, :503, :573, :608 |
| 28 | Autonomous demo | "Each tool call roots at rootType=nhi" (`AUTONOMOUS_DEMO.md:34-38`) | Possible only on `/mcp`, never observed; A2A roots at unverified human | Code | GG:503 |
| 29 | Intent readiness | "yes true we are already ready for Intent AuthZ" (T-C 2026-09-11) | No purpose signal, no request-path classifier, no intent claim | Code: not ready | GG:1122-1130; MEM/intent-aware-authz-mindset.md:16 |
| 30 | Verified identity only | Principal "never self-asserted clientInfo.name" (MEM/financial-scenario-demo.md:17) | Fallback registration and PDP `agentName` use `clientInfo.name` | Code | GG:582, :697 |
| 31 | Policy versioning / point-in-time | P1 versioned store (PRD:135); CISO "reconstruct governance as-of any past date" (`ciso.js:8`) | Not built (design proposal only); point-in-time is observed, not declared | Code | GG:918, :1185 |
| 32 | Token revocation button | "Revoke token (A2A / multi-hop)" bites on a later hop (`audit.js:797-801`) | `/a2a` has no `jti` check; bites only when re-presented to MCP within 120 s | Code | GG:509-512 |
| 33 | Kill session | Stops minting and rejects requests (`agents.js:391-393`) | True on `/mcp`; A2A has no sessions | Code | GG:350, :728 |
| 34 | Deny visible in console | "the deny moment lands on screen" (`ws-agentic-console/README.md:24-25`) | A2A deny = FAILED Task in HTTP 200 → rendered ✅ | Code | `a2aClient.js:150-166`; GG:310 |
| 35 | Public JWKS | Keys served at `/.well-known/sts/jwks.json` (`ws-gateway-dashboard/js/config.js:123`) | `TenantContext` not set on that path → likely empty (inferred) | Code (inferred) | GG:525 |
| 36 | Read-Only profile preset | Per-server "Read-Only" mode | Name regex matches no snake_case tools | Code | `profiles.js:596-600` |
| 37 | One console, one login, RBAC | Blueprint one login (BP:275); platform role-based access (PDF p.17) | Admin dashboard has no login; header tenant; no RBAC | Code | GG:48, :1138 |
| 38 | LLM configuration | Platform BYOK provider/key/model (PDF p.88) | Three assistants hard-code `claude-haiku-4-5`, one config key with a committed default | Code | GG:973-977 |
| 39 | Separate A2A Gateway product | Blueprint lists it separately (BP:8) | A2A is a second face on the same spine | Code | PRD:26-29; GG §1 |
| 40 | Stale internal docs | stage-2-plan "single global policy list"; concurrency doc "Tomcat thread, no DB waits"; compliance paths `/api/admin/ciso/compliance/*` | Per-tenant slots; boundedElastic + DB reads; paths moved to `/api/admin/compliance/*` | Code | GG §14.35; MEM/compliance-standalone-module.md |
| 41 | Java version | Buyer summary "Spring Boot/Java 21" (T-C) | Spring Boot 3.3.4, Java 17 | Code | GG §10 |
