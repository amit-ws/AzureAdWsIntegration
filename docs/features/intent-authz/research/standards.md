# Standards and protocols for capturing, binding and propagating intent (as of 2026-09-26)

Research dossier for WAAG intent-aware authorization. Research area: standards and protocols.

## Executive summary (10 lines)

1. No ratified standard defines an "intent" claim today. The closest things are the Transaction Token `scope` + immutable `tctx` (IETF OAuth WG, draft -11, waiting for shepherd write-up, IESG target Dec 2026) and AAuth "missions" (individual draft -11, 2026-09-25), which hash an approved mission (`mission_s256`) and evaluate every later token request against it.
2. Commerce leads on cryptographic intent binding. AP2 v0.2 (now at the FIDO Alliance) and Mastercard Verifiable Intent use SD-JWT "open mandates" signed by the user. These carry typed constraints plus the agent key (`cnf`), and verifiers check each constraint deterministically against the final "closed" action.
3. Building blocks that are already final: RFC 9396 RAR (typed `authorization_details`), RFC 8693 (`act`, `may_act`), RFC 9470 (step-up by auth level only), CIBA Core 1.0 (out-of-band human approval with `binding_message`), SD-JWT RFC 9901, W3C VC 2.0, SPIFFE, and the OpenID AuthZEN Authorization API 1.0 (whose decisions can carry obligations and step-up hints).
4. MCP 2026-07-28 is now stateless: no `initialize`, no sessions, and `_meta` required on every request. Nothing in core MCP carries intent. Tool annotations are only hints and are untrusted unless the server is trusted. URL-mode elicitation over MRTR, and the Tasks extension, give a standard channel for "require approval".
5. A2A v1.0 (2026-04-09) gives us `contextId`/`taskId`, free-form `metadata`, versioned extensions (declared on the AgentCard, activated per request) and the `AUTH_REQUIRED`/`INPUT_REQUIRED` task states. It is the natural carrier for an intent extension. AP2 already rides on it.
6. Risk frameworks all point the same way. OWASP ASI01 (Agent Goal Hijack), LLM06 "complete mediation", and NIST AI 100-2 E2025 (design as if prompt injection will succeed) favour deterministic enforcement against a bound intent, with model judgments used only as signals.
7. Recommended adoption path for WAAG: capture typed intent once at the root, with human approval where risk warrants. Mint a Txn-Token-shaped root OBO whose `tctx.intent` + `intent_s256` is immutable. Copy it into every per-hop OBO (it may narrow, never widen). Carry a reference in an A2A extension and in an MCP `_meta` vendor key. Expose `context.intent.*` to the PDP.
8. The biggest integration risks are these. (a) MCP 2026-07-28 statelessness breaks WAAG's session and `initialize`-based `/mcp` door filter. (b) WAAG's per-hop `scope` = one capability conflicts with Txn-Token semantics, where `scope` is the transaction purpose. (c) The PDP can't express obligations or approval today.
9. Maturity reality: almost every agent-specific intent draft is an individual, unadopted or expired I-D. Build to the stable primitives (JWT/JCS hash, RAR shape, Txn-Token claim layout, CIBA, A2A extensions) and keep the intent schema WAAG-owned and versioned.
10. Product line that holds up: "WAAG binds the human-approved purpose to every hop and enforces it deterministically, using standard building blocks". Don't say "WAAG implements the intent standard", because no such standard exists.

---

## 0. Method, legend, and WAAG baseline

**Method.** Primary sources were the IETF datatracker, rfc-editor, modelcontextprotocol.io (spec 2026-07-28), a2a-protocol.org, ap2-protocol.org and the AP2 GitHub raw docs, OpenID specs, OWASP genai.owasp.org, and the NIST CSRC PDF (read with pdftotext). Where only secondary sources were reachable, the text says so. Nothing was cloned, installed or run.

**Legend.**
- **[V]** verified from a primary source.
- **[V-digest]** verified through a WebFetch digest of the primary page (a small-model summary, so the details may be imperfect).
- **[S]** secondary source.
- **[VC]** vendor claim.
- **[J]** my own judgment.

**WAAG baseline, from `docs/others/gateway-grounding.md` §13 and the product brief §9.5 and §12.**
- No purpose or intent field exists in any envelope the gateway reads.
- The OBO carries `act_chain`, `trace_id`, `corr_id` (never read), `scope` (one capability) and `ws_tenant`.
- MCP `_meta` is dropped, and tool annotations are not even stored.
- The PDP is a Cedar-like regex subset. It returns ALLOW/DENY only, with no OR, no NOT and no obligations.
- Natural language reaches the PDP only through `context.argumentsFlat`, and only on A2A.
- The human's own words never reach the gateway on the console path.
- The OBO goes on the wire only for A2A.
- `/a2a` lacks the `jti`, human, NHI and agent-status gates (`docs/features/a2a-missing-governance-checks.md`).

---

## 1. IETF OAuth Transaction Tokens (draft-ietf-oauth-transaction-tokens)

### Status and maturity [V-digest]
- The latest revision is **-11, dated 2026-07-30**, and it expires 2027-01-31.
- It is an OAuth WG document. WG state: "Waiting for Write-Up". The WG milestone for IESG submission is **Dec 2026**. It has not yet been sent to the IESG.
- Authors: Tulshibagwale (CrowdStrike), Fletcher (Practical Identity), Kasselman (Defakto).
- Source: https://datatracker.ietf.org/doc/draft-ietf-oauth-transaction-tokens/
- Maturity: **late-stage WG draft**. The claim names have already churned once (see below), so pin to -11 and watch for more changes.

### Claim model in -11 [V-digest] (https://www.ietf.org/archive/id/draft-ietf-oauth-transaction-tokens-11.html)

| Claim | Meaning | Req. |
|---|---|---|
| `txn` | unique transaction id | REQUIRED |
| `sub` | principal of the transaction | REQUIRED |
| `scope` | the transaction's narrowly defined purpose, set by the Txn-Token Service (TTS) | REQUIRED |
| `tctx` | transaction context: values that stay **immutable** along the call chain | RECOMMENDED |
| `rctx` | request context: environmental values (IP, requester and so on) | RECOMMENDED |
| `req_wl` | requesting workload | REQUIRED |
| `aud` | trust domain | REQUIRED |
| `iat`, `exp` | lifetime is "minutes or less" | REQUIRED |
| `iss` | issuer | OPTIONAL |

- **Name history (the "purp/azd" question).** Draft -02 (2024-06-21) used `purp` ("purpose or intent of this transaction") and `azd` (authorization details that stay immutable) [V-digest] (https://www.ietf.org/archive/id/draft-ietf-oauth-transaction-tokens-02.html). By -11 these became **`scope`** and **`tctx`**. Anything written against `purp`/`azd` is stale.
- **Transport.** The token travels in the `Txn-Token` HTTP header, not in `Authorization`.
- **The TTS request is a token exchange.** It sends `requested_token_type=urn:ietf:params:oauth:token-type:txn_token`, `scope`, `request_context` (goes into `rctx`) and `request_details` (goes into `tctx`). The subject token can be an OAuth token, a self-signed JWT or unsigned JSON.
- **Replacement rules.**
  - A replacement Txn-Token may narrow the scope and add asserted values.
  - It must **not** change asserted values in a way that widens the permitted actions.
  - `txn`, `sub` and `aud` stay fixed.
  - An expired token cannot be replaced.
- The core draft has **no mention of AI agents**.

### Agent-oriented satellites (all individual, none WG-adopted)
- **draft-araut-oauth-transaction-tokens-for-agents-02** (2026-05-22) [V-digest]. It copies `act` from the access token unchanged. It adds `agentic_ctx`, containing `current_actor`, `originator` (immutable) and `chain_metadata.hop_count`/`min_assurance_level`. It defines **no claim for the user's intent or prompt**. Its predecessor, draft-oauth-transaction-tokens-for-agents, is expired at -06.
- **draft-liu-oauth-a2a-profile-00** (Huawei, 2025-10-20, expired 2026-04-23) [V-digest]. It maps A2A onto Txn-Tokens:
  - `purp` holds the A2A `Task.id`;
  - `tctx` holds the **user input** (immutable);
  - `rctx` holds the agents' observations and decisions (mutable).

  It is interesting as a prior-art pattern, but it is dead as a document.

### What WAAG could adopt [J]
- **Capture.** WAAG's STS already mints per-hop OBOs, so it is effectively a TTS already. Add a root "transaction mint" step. At the front door (console login or the first hop), take `request_details` = the structured intent and write it into `tctx.intent`.
- **Bind.** Sign it with the existing STS RSA key, and add `intent_s256`, a hash of the JCS-canonical intent (RFC 8785). Keep `txn` = `trace_id`.
- **Propagate.** Every per-hop OBO copies `tctx` byte-for-byte. It may add narrowing constraints but never widen them, which mirrors the replacement rule.
- **Enforce.** The PDP reads `context.intent.*`, populated from the verified inbound token. Any hop whose `tctx` differs from the root means a broken chain, and the gateway fails closed.
- **Semantic conflict to fix.**
  - In Txn-Tokens, `scope` is the *transaction purpose* and stays stable. In WAAG, `scope` is the *per-hop capability*.
  - To be Txn-Token-shaped, keep a stable transaction-level `scope` (the purpose code). Move the per-hop capability into `authorization_details` (see §2) or a separate `cap` claim.
  - Otherwise, "scope narrows per hop" looks like "purpose changes per hop".
- **Interop value.** For customers who already run a TTS (Txn-Tokens came out of large internal-microservice deployments), WAAG could accept an inbound `Txn-Token` header as the root intent source instead of minting its own. That's a "bring your own transaction context" story.

---

## 2. RFC 9396 Rich Authorization Requests (`authorization_details`)

- **Status.** Proposed Standard, published May 2023 [V-digest] (https://www.rfc-editor.org/rfc/rfc9396.html).
- **Shape.** A JSON array of objects. Each object has a required `type`, plus optional common fields: `locations`, `actions`, `datatypes`, `identifier` and `privileges`. Types can add their own fields.
- **Where it can appear.** Authorization requests, token requests, token responses and introspection. For JWT access tokens, the AS is RECOMMENDED to add it, filtered by audience, as a top-level claim. The AS may **enrich** what the client asked for, for example with the account the user picked. RFC 9396 itself does not address token exchange.
- **Adoption signals.**
  - ID-JAG (draft-ietf-oauth-identity-assertion-authz-grant-04, 2026-05-21, OAuth WG) explicitly allows `authorization_details` in the ID-JAG [V-digest].
  - The Huawei "Intent Admission Assertions" draft expresses its admission decision as a RAR type (§4).
  - RAR is the de facto shape for structured, typed grants.
- **What WAAG could adopt [J].**
  - Define a WAAG RAR type, for example `"type": "https://whiteswan.io/rar/agent-intent/v1"`, with these fields:
    - `purpose` (an enumerated code);
    - `actions` (allowed capability names or classes);
    - `locations` (servers or agents);
    - `datatypes` (data classes);
    - `identifier` (target entity, such as a payment id);
    - `constraints` (typed caps, such as `max_amount`, `currency`, `valid_until`);
    - `approval` (who approved it and when).
  - Use it in three places:
    1. in the root token (`tctx.intent`);
    2. as the per-hop grant (`authorization_details` with one capability);
    3. as the payload of an admin- or human-approved "mission".
  - This gives a standards-shaped, typed intent that the existing PDP can evaluate with `==`, `like`, `.contains` and integer comparison. No semantic operators are needed.
- **Caveat.** Keycloak (WAAG's IdP in dev) has limited or no native RAR support (open question). WAAG would mint RAR inside its own STS rather than rely on the IdP.

---

## 3. RFC 8693 Token Exchange (`act`, `may_act`)

- **Status.** Proposed Standard, January 2020 [V].
- **Nested `act` semantics [V-digest].** For access control, only the top-level claims and the *current* actor count. Prior actors in nested `act` are "informational only" (https://www.rfc-editor.org/rfc/rfc8693.html §4.1).
- `may_act` says who is allowed to act for the subject.
- Requesting several audiences and scopes gives the Cartesian product of rights, so the spec advises keeping requests narrow.
- **WAAG implications [J].**
  - WAAG's custom `act_chain` array, which policies read for `rootType`/`rootVerified`, deliberately goes further than 8693. Using the lineage for policy is WAAG's differentiator, but it is **not** 8693-standard behaviour.
  - Keep emitting a standard nested `act` alongside `act_chain`, for interop with resource servers that only understand 8693.
  - Document that WAAG's policy use of prior actors is an extension.
  - `may_act` is a clean, standard place to record "agent X may act for human H **for purpose P**". For example, in a human-approved mission token, `may_act` can list the agents allowed to pursue that intent.

---

## 4. Agent-specific IETF / OAuth drafts that touch intent

| Draft | Rev / date | Standing | Intent mechanism | Relevance to WAAG |
|---|---|---|---|---|
| **AAuth** draft-hardt-oauth-aauth-protocol | **-11, 2026-09-25** [V-digest] | Individual, not adopted | **Missions.** The agent proposes a Markdown mission. The person, at their Person Server (PS), approves it. It is identified by `mission_s256` (SHA-256 of the approved mission JSON). The PS keeps a **mission log** and checks each token request against the mission's intent, the log and the person's policy. A `justification` parameter is shown at consent. PoP uses HTTP Message Signatures (RFC 9421). Sub-agents go one level deep via `parent_agent`. | **Closest standards analogue to "capture once, evaluate every hop"**. WAAG's STS plus audit ledger could play the PS role: mission approval → `mission_s256` in every OBO → per-hop check against mission + log. The spec leaves open who the "supervisor" is (the person or a delegated supervision server), which is exactly where a WAAG intent evaluator would sit. Reference SDKs exist in Node, Go and .NET; there is an exploratory PS with missions (christian-posta/aauth-person-server) [S]. |
| **WIMSE AIMS** draft-ietf-wimse-aims | **-00, 2026-09-15** [V-digest] | **WIMSE WG document** (replaces draft-klrc-aiagent-auth; authors from Defakto, AWS, Zscaler, Ping, Okta and others) | Defines an "Agent Mission" (§10.1) as the natural-language objective. Turning a mission into authorization is **out of scope**. Missions are **not bound in tokens**. It recommends Transaction Tokens against lateral movement, and **CIBA** for human in the loop. Local UI confirmation alone is not authorization; approval must be tied to a verifiable grant. Each agent gets exactly one WIMSE identifier, which may be a SPIFFE ID. | This is the IETF's framing document for agents. It validates WAAG's architecture (per-hop downscoped tokens, SPIFFE-ready, CIBA for HITL) and states the gap WAAG would fill: mission → authorization binding. |
| **Intent Admission Assertions** draft-jiang-oauth-intent-admission-00 | 2026-06-23 [V-digest] | Individual (Huawei) | An Admission Point authenticates the originator, evaluates policy and gets consent. It then issues a JWS whose RAR `type:"intent_admission"` carries `intent_ref` (SHA-256 digest plus canonicalization method), `originator`, `presenter` (direct or delegated), `decision:"admit"` and consent evidence. It is PoP-bound with `cnf`. The Execution Endpoint re-verifies it as a non-bypassable gate. It "MAY" travel as or alongside a Txn-Token. | Maps cleanly onto WAAG: the front door is the Admission Point and each hop is an Execution Endpoint. Good vocabulary for a pitch ("admission vs execution"). |
| **Intent Token** draft-williams-intent-token-00 | 2026-03-19, expired 2026-09-20 [V-digest] | Individual (independent) | A JWT that the **human principal signs**. It holds `declared_intent` (action class plus bounds such as `max_position_usd` and `permitted_instruments`), a `delegation_chain` with attenuation, and a fail-closed "snap-back". It claims a 5 ms enforcement target [VC-like spec claim]. No implementations are reported. | Useful pattern (typed bounds, human signature), low standing. |
| **Agentic JWT** draft-goswami-agentic-jwt-01 | 2026-06-27 [V-digest] | Individual | Intent object with `workflow_id`, `workflow_step`, `executed_by`, a `delegation_chain` hash and a `step_sequence_hash`, plus `agent_checksum` and `cnf`. | The idea of a hash over completed steps is a cheap, standard-shaped "behaviour so far" binding (compare Dogwood-style history). Low standing. |
| **AAP** draft-aap-oauth-profile-01 | 2026-02-07, **expired** [V-digest] | Individual | Claims for agent identity, task context, constraints, delegation and oversight. | Prior art only. |
| **OBO for AI agents** draft-oauth-ai-agents-on-behalf-of-user-02 | 2025-08-25, **expired** [V-digest] | Individual (WSO2 authors) | `requested_actor`, `actor_token`, `act` in access tokens. **No intent.** | Prior art for consent-time agent delegation. |
| **ID-JAG** draft-ietf-oauth-identity-assertion-authz-grant-04 | 2026-05-21 [V-digest] | OAuth WG | Cross-app access through the enterprise IdP. The ID-JAG may carry `authorization_details`. | It is the basis of MCP's stable "Enterprise-Managed Authorization" extension [V-digest] (https://github.com/modelcontextprotocol/ext-auth). It is a plausible way for an enterprise IdP to put an intent RAR on an agent's grant. |
| **Identity & Authorization Chaining** draft-ietf-oauth-identity-chaining-17 | 2026-07-19, **submitted to IESG** [V-digest] | OAuth WG, Proposed Standard intended | Token exchange (RFC 8693) + JWT bearer grant (RFC 7523) across trust domains. Claim transcription is allowed but its format is undefined. | The standard route for carrying WAAG's intent context across a trust-domain boundary (for example, into a partner's AS). |
| **AuthZEN claims** draft-gazitt-oauth-authzen-claims-01 | 2026-09-02 [V-digest] | Individual | Fills token claims from AuthZEN resource search. **No intent field.** | Peripheral. |

**Takeaway [J].** The IETF community has converged on the *shape*: human-approved intent → canonical hash → carried in short-lived tokens → re-checked at every execution point. It has **not** converged on a claim name or schema. AAuth `mission_s256`, IAA `intent_ref`, Txn-Token `tctx` and AP2 `checkout_hash` all implement the same "hash the approved thing, verify at execution" primitive. WAAG should adopt the primitive and keep the schema its own, with a documented mapping to each.

---

## 5. RFC 9470 Step-Up Authentication Challenge

- **Status.** Proposed Standard, September 2023 [V-digest] (https://www.rfc-editor.org/rfc/rfc9470.html).
- **Mechanism.** A resource server returns `WWW-Authenticate: Bearer error="insufficient_user_authentication"` with `acr_values` and/or `max_age`. The client re-authenticates the user and retries.
- **Limit.** It covers authentication *strength and freshness* only, not per-transaction approval. How the resource server decides is out of scope.
- **What WAAG could adopt [J].**
  - Use it when the risk is "we need a fresher or stronger human login", for example before a high-value intent is minted.
  - For "a human must approve *this action*", use CIBA (§6), MCP URL-mode elicitation (§8) or A2A `AUTH_REQUIRED` (§9).
  - MCP's own step-up is scope-based (`insufficient_scope` + `scope=`, spec 2026-07-28 [V]), which is also not per-action approval.

## 6. OpenID CIBA Core 1.0 (human approval out of band)

- **Status.** Final, 2021-09-01 [V-digest] (https://openid.net/specs/openid-client-initiated-backchannel-authentication-core-1_0.html).
- **Mechanism.**
  - The client starts a backchannel request.
  - The user approves on their own authentication device.
  - Tokens come back by **poll, ping or push**.
- **`binding_message`.** A short plain-text string shown on both devices to interlock the transaction. This is the only standard CIBA field for "what am I approving".
- **RAR.** Core CIBA does not mention `authorization_details`; whether a profile combines them is open.
- **Endorsements.**
  - WIMSE AIMS recommends CIBA for agent HITL, and says approval must be a verifiable AS grant, not a local UI click [V-digest].
  - The OIDF agentic identity whitepaper (Oct 2025) recommends CIBA-style asynchronous authorization for high-risk agent actions (§2.7) [V-digest] (https://arxiv.org/html/2510.25819).
- **What WAAG could adopt [J].**
  - This is the standard backbone for a future `REQUIRE_APPROVAL` decision.
  - Put a short rendering of the intent plus action in `binding_message`, for example "Refund $250 on pay_X (duplicate) for Alice".
  - Put the full typed intent and action in a RAR object, if the IdP supports it.
  - Stamp the approval (`auth_req_id`, `acr`, time) into the hop's OBO `tctx.approvals[]`, so downstream hops and audit see verifiable evidence.
  - **Gating dependency:** the PDP must first gain a third outcome (obligation or advice). AuthZEN's decision `context` is a standard shape for that (§4 table; https://openid.github.io/authzen/ [V-digest]).
  - **Latency:** CIBA is seconds to minutes, so the blocking thread model (grounding §13(d)) needs an async or suspend pattern first.

---

## 7. OpenID Foundation: AI Identity Management CG and AuthZEN

- **Whitepaper.** "Identity Management for Agentic AI" (OIDF, October 2025; lead editors Tobin South and Subramanya Nagabhushanaradhya) [V-digest] (https://arxiv.org/abs/2510.25819). It covers:
  - scope attenuation through delegation chains (token exchange, Biscuits) (§3.2);
  - users approving high-level natural-language intent that the system turns into least-privilege permissions (§3.4);
  - "Mandates" as cryptographically signed intent artifacts (§3.6);
  - consent fatigue, with policy-as-code and risk-based authorization as the answer;
  - CIBA for asynchronous approval (§2.7).

  It does **not** discuss Transaction Tokens or RAR (per the digest of the full text).
- **CG work items.** Taxonomy, use cases and threat modelling subgroups [V-digest] (https://openid.net/cg/artificial-intelligence-identity-management-community-group/). The OIDF filed a response to the NIST RFI on agent security in March 2026 (https://openid.net/oidf-responds-to-nist-on-ai-agent-security/). The digest of that page was vague, so its exact recommendations are unverified.
- **AuthZEN Authorization API 1.0.** An **OpenID Final Specification**, approved January 2026 (vote 81 for, 1 against) [V-digest] (https://openid.net/authorization-api-1-0-final-specification-approved/). The request is subject/action/resource/context. The decision is a boolean plus an optional `context`, which may hold reasons, **advice and obligations**, UI hints and **step-up instructions**. There is a batch evaluations endpoint with `deny_on_first_deny` and similar options [V-digest] (https://openid.github.io/authzen/). MCP SEP-2848 cites an AuthZEN "Access Request and Approval Profile" as a non-normative approval backend [V-digest].
- **What WAAG could adopt [J].**
  - Make the WAAG PDP **AuthZEN-shaped**, with intent in `context` and obligations in the decision `context`.
  - That makes the PDP swappable with Cedar or OPA engines, and makes `REQUIRE_APPROVAL` or step-up expressible in a standard way.
  - It also answers the buyer question "is it really Cedar?" with "it speaks the OpenID standard PDP API".

---

## 8. MCP specification (current version 2026-07-28)

**Version [V].** The current spec is **2026-07-28** (https://modelcontextprotocol.io/specification/latest, schema `schema/2026-07-28/schema.ts`). Changelog [V] (https://modelcontextprotocol.io/specification/2026-07-28/changelog):

- **Stateless protocol.**
  - The `initialize`/`initialized` handshake is removed.
  - `Mcp-Session-Id` and protocol-level sessions are removed.
  - Every request must carry `_meta["io.modelcontextprotocol/protocolVersion"]` and `["io.modelcontextprotocol/clientCapabilities"]`, and should carry `clientInfo`.
  - `server/discover` is new.
  - Cross-call state must use explicit handles passed as arguments (SEP-2567, SEP-2575).
- **MRTR (Multi Round-Trip Requests).**
  - Server-initiated requests (elicitation, sampling, roots) are replaced by a result with `resultType:"input_required"` and `inputRequests`.
  - The client retries with `inputResponses` + `requestState` (SEP-2322).
- **Tasks** moved to an official extension, `io.modelcontextprotocol/tasks`, with polling through `tasks/get` and input through `tasks/update` (SEP-2663).
- **Streamable HTTP POSTs must carry `Mcp-Method` and `Mcp-Name` headers.** Tools can mirror primitive parameters into `Mcp-Param-{name}` headers through `x-mcp-header`, so intermediaries can route without parsing the body (SEP-2243).
- **OpenTelemetry `traceparent`/`tracestate`/`baggage`** are reserved `_meta` keys (SEP-414).
- **Auth.** Authorization servers should send `iss` (RFC 9207). Client ID Metadata Documents are preferred and DCR is deprecated.
- **Deprecated.** Roots, Sampling and Logging (SEP-2577).

**Intent-relevant facts [V].**
- **No intent, purpose or justification field** exists anywhere in the schema. `CallToolRequestParams` is just `name` + `arguments` (+ MRTR `inputResponses`/`requestState`). A grep of schema.ts found no intent, purpose or justification field. The only "reason" is on cancellation, and "stopReason" is for sampling.
- **`_meta` rules.**
  - Keys take an optional reverse-DNS prefix plus a name.
  - Prefixes whose second label is `modelcontextprotocol` or `mcp` are reserved.
  - Third parties use their own prefix, for example `io.whiteswan/intent`.
  - `clientInfo`/`serverInfo` are self-reported and **should not be used for security decisions**.
- **Tool annotations** (`ToolAnnotations` in schema.ts, with defaults):
  - `readOnlyHint` (false);
  - `destructiveHint` (true, meaningful only when not read-only);
  - `idempotentHint` (false);
  - `openWorldHint` (true);
  - `title`.

  The spec says clients **must** treat annotations as untrusted unless they come from trusted servers (https://modelcontextprotocol.io/specification/2026-07-28/server/tools).
- `tools/list` may vary **by the authorization on the request** but not per connection. This is spec-sanctioned support for WAAG's caller-filtered tool list.
- **Elicitation** (https://modelcontextprotocol.io/specification/2026-07-28/client/elicitation):
  - Form mode must not ask for secrets.
  - **URL mode** handles sensitive out-of-band interactions: the client must show the full URL and get consent, and the server must check that the person completing the flow is the same user who triggered it.
  - The result is `accept`/`decline`/`cancel`.
  - Elicitation is now delivered through MRTR `InputRequiredResult`.
- **Authorization** (https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization):
  - OAuth 2.1 with Protected Resource Metadata (RFC 9728).
  - **RFC 8707 resource indicators** are mandatory on authorization and token requests.
  - Audience validation is mandatory.
  - "MCP servers MUST NOT accept or transit any other tokens" (no passthrough).
  - Step-up is **scope-based** (403 `insufficient_scope` + `scope=`).
  - There is no RAR and no token exchange in core. Extensions live in `modelcontextprotocol/ext-auth`: Enterprise-Managed Authorization (stable, ID-JAG based) and Client Credentials (draft) [V-digest].

**SEPs in flight on intent and approval** [V-digest]:
- **SEP-2848, Asynchronous Approval for Tool Calls** (mcguinness; open draft since 2026-06-03).
  - A "requestable" denial returns a **task handle** instead of failing.
  - The server records an **immutable call binding**: tool name, canonical-args digest, principal, subject, approval id and expiry.
  - The PDP **re-evaluates at execution**.
  - If anything differs, the result is `denied-not-executed`.
  - The approval backend is pluggable (human, supervisor agent, risk engine, ITSM/IGA, AuthZEN example).
  - https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2848
- **SEP-2787, Tool Call Attestation** (closed 2026-09-22, pending WG sponsorship). A signed `_meta` envelope binding intent/purpose, agent id, tool, an argument commitment (JCS/RFC 8785 digest) and a TTL. https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2787
- **SEP-2672, Per-Call Passkey Verified Approval** (closed 2026-09-22). WebAuthn approval bound to the exact tool and arguments, with evidence carried in `params._meta`. Closed because of MCP's shift to WG-first SEP governance. https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2672

**What WAAG could adopt [J].**
1. **Capture.**
   - Parse `_meta` instead of dropping it. Accept an optional `io.whiteswan/intent` (or intent-ref) key.
   - Capture `traceparent`/`baggage` to join WAAG `trace_id` to customer OTel traces.
   - `baggage` must not be trusted for authorization: it is caller-controlled.
2. **Bind.** Because the OBO is not sent to MCP servers, binding is gateway-side: the intent lives in the verified token or gateway state keyed by `txn`/`trace_id`. For MCP servers that trust the WAAG JWKS, a future option is the OBO in the `Authorization` header, which is allowed because the audience is that server.
3. **Enforce with annotations.**
   - Store annotations at registration.
   - Let the admin **override or attest** them per server, since WAAG decides what counts as a "trusted server".
   - Expose `resource.readOnly`/`resource.destructive`/`resource.openWorld` to the PDP.
   - This cheaply enables intent-class rules such as "purpose=research ⇒ readOnly only", with no model.
4. **Approval channel.**
   - For MCP clients that declare `elicitation.url`, WAAG can itself answer a `tools/call` with an `InputRequiredResult` carrying a URL-mode elicitation to a WAAG approval page (CIBA-like, same-user check), and resume on retry with `requestState`.
   - For long approvals, the SEP-2848 or Tasks pattern fits better.
   - Both need the PDP to gain a "requestable deny" outcome.
5. **Operational warning.** WAAG's `/mcp` door filter relies on `initialize`, session registration, identity pinning by session and the "Kill session" action (grounding §3; a2a-gaps doc #6 and #8). **A 2026-07-28 client has no session and no `initialize`.** Supporting the new version needs:
   - per-request identity gates;
   - a kill switch keyed by trace, agent or human;
   - use of `Mcp-Method`/`Mcp-Name` headers for cheap routing and pre-body gating.

   This is a prerequisite that ranks above intent work.

---

## 9. A2A protocol (v1.0)

**Status [V-digest].**
- v1.0 was released **2026-04-09** under the Linux Foundation, which has hosted the project since June 2025.
- It added Signed Agent Cards (JWS), multi-tenancy and "modernized security flows".
- Adoption per the LF press release: 150+ organizations, 22k+ GitHub stars, SDKs in five languages. These are vendor/foundation counts.
- Sources: https://a2a-protocol.org/latest/specification/ and https://www.linuxfoundation.org/press/a2a-protocol-surpasses-150-organizations-lands-in-major-cloud-platforms-and-sees-enterprise-production-use-in-first-year

**Relevant mechanics [V-digest].**
- **Message fields.**
  - `messageId` is set by the sender.
  - `contextId` is optional. The server generates one if absent and may reject client-supplied ones.
  - `taskId` is always server-generated.
  - Also `referenceTaskIds`, `metadata` (any JSON, on Message, Task and Part) and `extensions` (URIs).
- **Intended use of `contextId`/`taskId`.** Both logically group interactions. The agent keeps conversational state across turns under `contextId`.
- **Task states** include `TASK_STATE_INPUT_REQUIRED` and `TASK_STATE_AUTH_REQUIRED` (non-terminal).
- **Extensions** are declared in `AgentCard.capabilities.extensions` with `{uri, description, required, params}`. The client activates them per request through the `A2A-Extensions` header. If a required extension isn't declared, the agent returns `ExtensionSupportRequiredError`. Extension data rides in metadata keyed by the extension URI.
- **Extension types.** Data-only, profile, method and state-machine. Guidance: treat extension data as untrusted, and apply the same authentication and authorization to new methods. Listed examples include Secure Passport, Traceability and Timestamp [V-digest] (https://a2a-protocol.org/latest/topics/extensions/).
- The core spec says **nothing about intent or on-behalf-of user identity propagation** (per the digest). Identity lives in the transport security schemes (OAuth2/OIDC/mTLS/API key) on the AgentCard.

**What WAAG could adopt [J].**
- **Capture/propagate.**
  - Define a WAAG A2A **profile extension**, for example `https://whiteswan.io/a2a/ext/intent/v1`. It puts an intent reference (`intent_s256`, `txn`, optionally the typed intent) in `message.metadata[<uri>]`.
  - Mark it `required:true` on WAAG-fronted agent cards, so non-participating callers fail loudly.
  - The authoritative copy stays in the signed OBO (already on the A2A wire). Metadata is a convenience or reference, never trusted by itself.
- **Use `contextId` properly.**
  - Today WAAG uses the caller-chosen `contextId` as its session id, which lets callers dodge session revocation (a2a-gaps #2).
  - Under v1.0 semantics, the *server* generates `contextId`, so WAAG as the intermediary could mint it and bind it to `txn`/intent.
  - The contextId-to-intent mapping would then become a gateway-owned, revocable handle.
- **Human approval.** When the PDP returns "requires approval", answer the A2A caller with a Task in `AUTH_REQUIRED` (or `INPUT_REQUIRED`) instead of a JSON-RPC error. This is standard, non-terminal and resumable.
- **Stop overloading natural language.** Today the only intent-bearing field is `argumentsFlat` (LLM-written text). The extension carries typed intent, so the PDP never needs to parse prose.

---

## 10. Google AP2 (mandates), Mastercard Verifiable Intent, W3C VC, SD-JWT

**AP2 status [V/V-digest].**
- Announced 2025-09-16 with 60+ partners (Google Cloud blog, per secondary sources).
- **v0.2** introduced a new mandate model. The GitHub releases page shows v0.2.0 as "Human Not Present flows"; the digest gave the year inconsistently, and third parties say April 2026. Repo: 3.2k stars, Apache-2.0 (https://github.com/google-agentic-commerce/AP2/releases).
- **Donated to the FIDO Alliance** (April 2026). Work continues in its Agentic Authentication and Payments Technical WGs [S] (https://www.helpnetsecurity.com/2026/04/29/fido-alliance-ai-agents-authentication-payments-standards/; FIDO post by Nishant Kaushik, 2026-05-26: https://fidoalliance.org/building-the-trust-layer-for-agentic-payments-with-ap2-and-verifiable-intent/).

**Mandate model in v0.2 [V]** (raw spec: https://raw.githubusercontent.com/google-agentic-commerce/AP2/main/docs/ap2/specification.md; site: https://ap2-protocol.org/ap2/specification/):
- **Renamed model.** v0.1's Intent / Cart / Payment Mandates became **Checkout Mandate** and **Payment Mandate**, each **open** (constraints) or **closed** (one specific transaction).
  - v0.1 A2A DataPart keys were `ap2.mandates.IntentMandate` / `PaymentMandate` [S].
  - The extension URI was `github.com/google-agentic-commerce/ap2/...` [S]. The old ap2-protocol.org A2A-extension page now returns 404.
- **Format.** **SD-JWT** secures the mandates. Schemas are versioned by `vct` (for example `mandate.checkout.open.1`), with an exact-match rule.
- **Human Present.** The user sees and signs the *closed* mandate on a "Trusted Surface".
- **Human Not Present.**
  - The user approves an *open* mandate with constraints, and it **MUST include the agent's public key as `cnf`**. `exp` is RECOMMENDED to be as short as the task allows.
  - The agent then signs the closed mandate with its own key.
  - Verifiers receive **both** the user-signed open mandate and the agent-signed closed one.
  - The verifier **evaluates each Constraint** against the closed checkout or payment.
  - The closed Checkout is bound by `checkout_hash` of the merchant-signed Checkout JWT.
  - The agent **must not present another open mandate until the previous one is rejected**, which prevents one approval being spent twice.
- **Extension point.** A new constraint type must define a unique `type`, a schema (including selectively disclosable fields) and **an evaluation algorithm**.
- **Agent Authorization Framework** (https://ap2-protocol.org/ap2/agent_authorization/) [V-digest].
  - It separates Mandate Delegation (on a Trusted Surface) from Action Authorization ("Mandate Receipts").
  - It uses SD-JWT VC, OpenID4VP `transaction_data`, RFC 7800 `cnf` and KB-JWT.
  - It supports a verifiable chain from the user-approved open mandate to the closed mandate.

**Mastercard Verifiable Intent [V-digest]** (https://github.com/agent-intent/verifiable-intent/). Announced 2026-03-05 [S]. v0.1 draft, Apache-2.0, 91 stars, payment-specific. It is a **three-layer SD-JWT chain**:
- **L1.** Issuer → user identity, with the device key in `cnf` (~1 year).
- **L2.** The user's intent, signed by the device key. It is either immediate, or autonomous with constraints and the agent key in `cnf` (15 minutes to 30 days).
- **L3.** The agent's action, split into an L3a payment and an L3b checkout (~5 minutes), cross-referenced by `transaction_id`/`checkout_hash`.
- There are five constraint types: amount range, line items, merchants/payees, instruments, and descriptive fields. Descriptive fields are **context only, not machine-enforced**.

**Security research [V-digest].**
- "Signing the Transaction but Not the Decision: Whisper Attacks and a Binding Defense for AP2" (Louck, Dvir, Stulman; arXiv 2609.11757, 2026-09-10).
- Product-description text steered shopping agents into carts that passed every protocol check but didn't match the user's request. Reported success rates were 56–90% across variants.
- The proposed defense, A-VIP, treats the signed intent **as a capability grant**, binding credential lookups to sessions and cart items to listings. It is a deterministic check, not an attempt to judge the content.
- This is the strongest evidence in the set that **the model's judgment of content must not be the control**. Enforcement against typed, signed constraints is what works.

**W3C VC 2.0.** A W3C Recommendation (seven specs) since **2025-05-15** [V-digest] (https://www.w3.org/press-releases/2025/verifiable-credentials-2-0/). **SD-JWT is RFC 9901**, November 2025 [V-digest] (https://www.rfc-editor.org/info/rfc9901/).

**What WAAG could adopt [J].**
- The **open/closed mandate pattern is exactly "intent once at the root, enforce per hop"**:
  - open mandate = the human-approved WAAG intent (typed constraints + `cnf` of the orchestrator agent + short `exp`);
  - closed action = each hop's concrete tool call;
  - verifier = the WAAG PDP, evaluating each constraint.
- Adopt three things:
  1. typed constraints, each with a declared evaluation algorithm;
  2. a single-use or "no parallel spend" rule for high-value intents;
  3. selective disclosure, so downstream servers see only the constraints relevant to them (SD-JWT). This matters for privacy when intent carries PII.
- **Don't** make WAAG a payments protocol. Do support *pass-through verification*: if an agent presents an AP2 or VI mandate, WAAG can verify signatures and constraints and expose `context.mandate.*` to the PDP. That is a credible "we honour your commerce mandates at the gateway" story.
- **Human signature.** AP2/VI assume a user device key (passkey) signs. WAAG today has only an IdP login; the human never signs. The standards-aligned middle ground: WAAG mints the intent token after an authenticated, fresh (`max_age`, RFC 9470) human confirmation, and records the approval's `acr`/`auth_time`. A true user-held key (WebAuthn, as in SEP-2672) is a later upgrade.

---

## 11. SPIFFE / WIMSE (workload identity under the chain)

- **SPIFFE [V-digest].** SPIFFE IDs, X.509-SVID / JWT-SVID, the Workload API and federation (https://spiffe.io/docs/latest/spiffe-about/overview/). It carries **no user or intent context by design**; it answers "which workload", not "why".
- **WIMSE WG [V-digest]** (https://datatracker.ietf.org/wg/wimse/documents/):
  - arch-08, identifier-03, workload-creds-02, wpt-02, http-signature-07 (2026-09-20), mutual-tls-02 and **aims-00**;
  - workload-identity-practices-07 has been submitted to the IESG (Informational);
  - nothing is in the RFC queue yet.
- **WAAG relevance [J].** WAAG already has the seam (`identity_source`, `cnf.workload_id`; memory note "OBO actor workload identity"). SPIFFE underpins the *identity decay* side (Reva's "two decays") but contributes nothing to intent. Intent binding rides on top: the `cnf` in an intent token should name the agent's SPIFFE ID or key, so a stolen intent token can't be replayed by another workload. This is the same idea as AP2's `cnf` on open mandates.

---

## 12. Risk frameworks: what each says about intent

### OWASP Top 10 for Agentic Applications 2026 (published 2025-12-09) [V-digest] (https://genai.owasp.org/2025/12/09/owasp-top-10-for-agentic-applications-the-benchmark-for-agentic-security-in-the-age-of-autonomous-ai/)

| ID | Name | One-line definition (paraphrased) | Intent-binding control WAAG can offer [J] |
|---|---|---|---|
| ASI01 | Agent Goal Hijack | The agent's objective or plan is redirected, for example by hidden instructions in ingested content | Immutable root intent in every OBO. Per-hop check that the capability and arguments fall within the intent's typed bounds. Approval required for any goal change. |
| ASI02 | Tool Misuse (& Exploitation) | A legitimate tool is used in an unsafe or unintended way | Intent class × tool annotations (read-only vs destructive). Argument constraints (amount caps, target ids). |
| ASI03 | Identity & Privilege Abuse | Privileges outlive or exceed their authorizing context | Per-hop 120 s single-capability OBO (exists). Intent `exp` and `cnf`. The human-rooted chain. |
| ASI04 | Agentic Supply Chain Vulnerabilities | Third-party tools, agents or registries are compromised | Registry pinning; only trusted servers' annotations count. |
| ASI05 | Unexpected Code Execution | Arbitrary code runs through the agent or sandbox | Intent class may forbid code-exec tools outright. |
| ASI06 | Memory & Context Poisoning | Planted content steers later steps | Intent stays fixed while content varies, so poisoned content can't widen the grant (the whisper-attack lesson). |
| ASI07 | Insecure Inter-Agent Communication | Spoofed, replayed or unauthenticated agent messages | Signed OBO + `cnf` (exists on A2A). Signed intent reference in an A2A extension. `jti` replay check (missing on `/a2a` today). |
| ASI08 | Cascading Failures | One corrupted hop propagates | A child hop can't exceed its parent's intent (narrow-only replacement rule). A chain-wide kill switch keyed by `txn`. |
| ASI09 | Human-Agent Trust Exploitation | Humans are deceived into approving harmful actions | Approval UI renders the *typed* intent and action (CIBA `binding_message`), not agent-written prose. |
| ASI10 | Rogue Agents | An agent operates outside policy or intent | Behavioural baselines are out of scope here. Intent gives the reference to measure drift against. |

- **"Intent capsule."** Several secondary sources say OWASP recommends signed "intent capsules" that bind the goal to execution for ASI01 (Adversa AI, Galileo). Adversa's own page presents the capsule as *its* implementation example. **I could not verify the phrase in the OWASP primary text** (the PDF isn't reachable as text), so treat it as [S], unverified.
- **"Least agency."** Also appears in secondary sources (Auth0's summary) [S].

### OWASP LLM Top 10

- **2025 edition [V-digest].**
  - **LLM01:2025 Prompt Injection** (direct and indirect). Mitigations include least privilege, **human approval for privileged operations** and segregating untrusted content (https://genai.owasp.org/llmrisk/llm01-prompt-injection/).
  - **LLM06:2025 Excessive Agency**. Root causes: excessive functionality, permissions and autonomy. Mitigations: run actions in the specific user's context with minimum privilege; human approval for high-impact actions; **"complete mediation"**, meaning authorization belongs in downstream systems, not in the LLM (https://genai.owasp.org/llmrisk/llm062025-excessive-agency/).
- **2026 edition** (published 2026-08-04/06) [S].
  - Prompt Injection is still #1.
  - **Excessive Agency rose from #6 to #3**.
  - Its framing: assume the model *will* be fooled and design so nothing important breaks (https://www.helpnetsecurity.com/2026/08/06/owasp-2026-llm-top-10-released/).
  - The full ranked list could not be verified.

### NIST
- **AI RMF 1.0** (2023-01-26). It is being revised under the White House AI Action Plan, with no date given. Profiles: GenAI AI 600-1 (2024-07-26) and a Critical Infrastructure concept note (2026-04-07). **No agent-specific profile is listed** on the NIST page [V-digest] (https://www.nist.gov/itl/ai-risk-management-framework).
  - A secondary claim of an "NIST AI 100-5 agentic profile" is **unverified** and conflicts with the NIST page.
  - An "AI Agent Interoperability Profile, Q4 2026" is [S], unverified.
- **AI 100-2 E2025**, "Adversarial Machine Learning: A Taxonomy and Terminology..." (March 2025; Vassilev et al.) [V, read from the PDF]:
  - §3.4 covers indirect prompt injection used to **hijack an agent** into doing an attacker's task.
  - §3.4.4 says current mitigations **do not offer full protection**. Designers may assume injection is possible whenever the model sees untrusted input, for example by using multiple LLMs with different permissions or restricting untrusted data to well-defined interfaces.
  - §3.5 "Security of Agents" says agent-specific research is still early.
  - Implication [J]: an in-path LLM or SLM "intent judge" is itself an injectable component. It may *signal*, but deterministic, bound constraints must *decide*.
- **CAISI AI Agent Standards Initiative** (launched 2026-02-17) [V-digest] (https://www.nist.gov/artificial-intelligence/ai-agent-standards-initiative):
  - an RFI on agent security (January 2026, deadline March 9);
  - the NCCoE concept paper "Accelerating the Adoption of Software and AI Agent Identity and Authorization" (comments closed 2026-04-02; per secondary sources, it proposes OAuth 2.0/2.1, OIDC and SPIFFE/SPIRE for agents);
  - sector listening sessions.

  A future NCCoE lab project is a possible venue for WAAG to participate in (open question).

### CSA MAESTRO (2025-02-06, Ken Huang) [V-digest] (https://cloudsecurityalliance.org/blog/2025/02/06/agentic-ai-threat-modeling-framework-maestro)
- **Seven layers:**
  1. Foundation Models
  2. Data Operations
  3. Agent Frameworks
  4. Deployment & Infrastructure
  5. Evaluation & Observability
  6. Security & Compliance (vertical)
  7. Agent Ecosystem
- Layer 7 threats include **Agent Goal Manipulation**, Agent Impersonation and Agent Tool Misuse.
- It is a method, not a control catalogue. WAAG sits at layers 4 and 7 with a vertical layer-6 role. Intent binding is a layer-7 control against goal manipulation.

---

## 13. Synthesis: a standards-aligned intent path for WAAG [J]

### 13.1 Design principles taken from the standards
1. **Hash the approved thing and re-verify at execution.** This is common to AAuth, IAA, AP2 and SEP-2848.
2. **Immutable context, narrowing-only replacement.** From Txn-Tokens.
3. **Typed constraints with declared evaluation algorithms.** From AP2 and RAR. Natural-language descriptions are context, never enforcement (VI's "descriptive fields").
4. **Key-bind the intent to the acting agent.** `cnf`, as in AP2, IAA and AAuth.
5. **Human approval is a verifiable grant, not a UI click.** From WIMSE AIMS and CIBA.
6. **The model is never the authority.** From OWASP LLM06 complete mediation, NIST AI 100-2 §3.4.4 and the whisper-attack results. This also matches the Jev CEO's advice (source 03).

### 13.2 Capture once at the root (options, in order of strength)
- **A. Structured intent picked or confirmed by the human.** The console shows a typed intent card (purpose code, allowed capability classes, constraints, expiry) and the human confirms it. WAAG mints it after a fresh login (RFC 9470 `max_age`). Optionally the approval goes through CIBA. This is the strongest option and the most UI work.
- **B. Admin-defined intent templates ("purposes").** Like AAuth missions or RAR types. The console or agent picks a purpose id, and WAAG fills in constraints from policy. Cheap; it doesn't need the human's words.
- **C. Intent derived from the first A2A message** (LLM-written). This is the weakest and must be treated as *untrusted*. At most, map it to a purpose *proposal* that needs option A or B confirmation for risky classes.
- **D. Accept upstream intent tokens.** Inbound `Txn-Token` (tctx), AP2/VI mandates, or AAuth `mission_s256`. Verify, then adopt.

### 13.3 Bind (claim layout sketch, WAAG-owned schema with a documented mapping to Txn-Token)
```json
{
  "iss": "https://gw.whiteswan/sts", "sub": "human:alice", "txn": "<trace_id>",
  "scope": "purpose:payments.refund",                      // Txn-Token-style stable purpose
  "tctx": {
    "intent": { "v": 1, "purpose": "payments.refund",
      "caps": ["stripe.refund", "payments.lookup"],
      "constraints": [{"type":"max_amount","currency":"USD","value":250},
                      {"type":"target","field":"payment_id","value":"pay_X"}],
      "exp": 1790500000,
      "approval": {"method":"ciba|login","acr":"...","at":1790499000} },
    "intent_s256": "<b64url sha256(JCS(intent))>"
  },
  "authorization_details": [{"type":"https://whiteswan.io/rar/hop/v1","actions":["stripe.refund"]}],
  "act": {"sub":"agent:advisor"}, "act_chain": [...], "cnf": {"jkt":"..."}, "exp": "<+120s>"
}
```
- The per-hop OBO copies `tctx` unchanged; only `authorization_details`, `act`/`act_chain` and `exp` change per hop.
- The PDP gets flattened, typed attributes, for example `context.intent.purpose`, `context.intent.capsContains`, `context.intent.maxAmount`. These fit the current engine's `==`, `.contains` and integer comparison. **No semantic operators are needed.**
- Cost is one SHA-256 and a JCS canonicalization per mint (sub-millisecond, [J]), which is small next to the ~12 ms/hop governance overhead measured in grounding §13(d).

### 13.4 Propagate
- **A2A.** Signed OBO (already on the wire) plus the WAAG intent extension (metadata reference). The gateway mints `contextId` and binds it to `txn`.
- **MCP.**
  - Gateway-side: bind by `txn` from the inbound OBO or session.
  - Optionally emit `_meta["io.whiteswan/intent"]` = `{txn, intent_s256}` to downstream MCP servers that opt in.
  - Map `trace_id` ↔ `traceparent`.
- **Cross-domain.** Use identity chaining (token exchange + JWT grant) and transcribe `tctx.intent` into the partner's assertion.

### 13.5 Enforce
- **Deterministic first.**
  - Capability ∈ `intent.caps`.
  - Arguments satisfy each typed constraint.
  - Annotation class is compatible with the purpose.
  - Child `tctx` == root `tctx`.
  - Intent not expired.
  - Approval present when the policy requires it.
- **Third outcome.** "Approval required" is expressed AuthZEN-style as an obligation and fulfilled through:
  - MCP: MRTR URL-mode elicitation, or the SEP-2848 task pattern;
  - A2A: `AUTH_REQUIRED`;
  - IdP-backed: CIBA with `binding_message`.
- **Probabilistic signals** (SLM or LLM "does this hop serve the intent?") become *attributes*, such as `context.intentDriftScore`. They can only **tighten**: escalate to approval or deny. They never grant. This is the "deterministic IBAC" shape Reva describes, built from standard parts.
- **Audit.** Every decision row carries `txn`, `intent_s256` and the constraint results (the "decision snapshot" in AuthZEN, AP2 receipts and the AAuth mission log).

---

## 14. Maturity and adoption matrix (September 2026)

| Standard | Body / standing | Status | Adoption evidence | Intent role | Adopt now? [J] |
|---|---|---|---|---|---|
| RFC 8693 Token Exchange | IETF PS | RFC (2020) | Broad (Keycloak, Okta, Entra and others) | lineage (`act`, `may_act`) | Yes: already used; emit a standard `act` too |
| RFC 9396 RAR | IETF PS | RFC (2023) | Growing: FAPI/open banking; ID-JAG uses it | **typed intent shape** | Yes: as the internal intent schema shape |
| RFC 9470 Step-up | IETF PS | RFC (2023) | Moderate | freshness/strength only | Yes, for pre-mint freshness |
| CIBA Core 1.0 | OIDF Final | Final (2021) | Banking/FAPI; endorsed by AIMS and the OIDF paper for agents | human approval | Next: after the PDP gains obligations |
| Transaction Tokens | IETF OAuth WG | draft -11, waiting for write-up; IESG target Dec 2026 | Implementations exist [open question: which ones] | **immutable purpose/context** | Yes: copy the claim layout; don't hard-depend on a TTS |
| AuthZEN Authz API 1.0 | OIDF Final | Final (January 2026) | Several PDP vendors [S] | PDP API with obligations/step-up | Yes: target shape for the PDP API |
| Identity chaining | IETF OAuth WG | at IESG (-17) | Early | cross-domain carriage | Later |
| ID-JAG | IETF OAuth WG | draft -04 | MCP Enterprise-Managed Authorization (stable ext) | carries RAR | Watch / interop |
| AAuth | Individual I-D | -11 (2026-09-25) | SDKs in Node, Go, .NET; exploratory PS | **missions + `mission_s256` + log** | Borrow the pattern; don't depend on it |
| WIMSE AIMS | IETF WIMSE WG | -00 (2026-09-15) | New | framing; mission unbound | Cite in pitch; track |
| IAA / Intent Token / Agentic JWT / AAP / OBO-for-agents / Txn-for-agents / A2A Txn profile | Individual I-Ds | mixed; several expired | none material | various intent claims | Prior art only |
| MCP 2026-07-28 | MCP spec (LF) | current | Very broad | none in core; annotations, `_meta`, elicitation, Tasks | **Must support** (statelessness) |
| MCP SEP-2848 | MCP SEP | open draft | none | immutable call binding + async approval | Track; design-compatible |
| A2A v1.0 | LF | released 2026-04-09 | 150+ orgs (LF claim) | extensions, metadata, `AUTH_REQUIRED` | Yes: WAAG intent extension |
| AP2 v0.2 | FIDO Alliance (donated) | v0.2 | 60+ partners (vendor claim) | **open/closed mandates, typed constraints** | Pattern yes; verify mandates if presented |
| Mastercard Verifiable Intent | Open spec (Mastercard) | v0.1 draft | Commitments claimed from Google, Fiserv, IBM and others [VC] | layered SD-JWT intent chain | Pattern only (payments) |
| W3C VC 2.0 / SD-JWT RFC 9901 | W3C Rec / IETF RFC | final (2025) | EUDI wallets and others | credential format, selective disclosure | Later, for privacy-preserving intent disclosure |
| SPIFFE / WIMSE | CNCF / IETF WG | mature / drafts | broad (SPIFFE) | identity under intent (`cnf`) | Already on roadmap |
| OWASP ASI 2026, LLM 2025/2026 | OWASP | published | industry reference | threat framing | Map controls in collateral |
| NIST AI RMF / AI 100-2 E2025 / CAISI | NIST | RMF 1.0 in revision; 100-2 final | government reference | assume-injection design; agent ID&A work | Cite; watch NCCoE |
| CSA MAESTRO | CSA | published 2025 | reference | layer-7 goal manipulation | Use in threat models |

---

## 15. Product view [J]

- **Buyer questions this answers.**
  - Zscaler's "readiness for intent-aware authZ".
  - Netskope RFI Q10 (scope to the task, not the user's full rights) and Q15 (adaptive access by risk and intent).

  A standards-mapped answer carries more weight than a proprietary "intent engine": "we bind the purpose in a Txn-Token-shaped context, typed like RAR, approved through CIBA, and carried as an A2A extension".
- **Differentiation that holds.**
  - Most standards stop at the *token or protocol*. None defines **per-hop enforcement across heterogeneous MCP and A2A chains for agents you don't own**. That is WAAG's gateway-first position (memory: the Uber precedent).
  - AAuth's PS and AP2's verifiers are the closest rivals in concept, and both need agent or merchant cooperation.
- **Don't claim** any of these:
  - "standard intent claim";
  - "Cedar";
  - "intent-aware today";
  - "blocks prompt injection".

  Do claim "deterministic enforcement of a human-approved purpose at every hop; models only tighten".
- **Sequencing reality** (brief §12). The engine's ignored heads, the `/a2a` status and `jti` gaps, and the missing third outcome must be fixed first, or intent sits on a leaky base. MCP 2026-07-28 statelessness is a new, time-bound prerequisite: clients will move to it.

---

## 16. Vendor claims and unverified items (keep out of collateral unless re-verified)
- Reva: p90 below 40 ms; 98% drift-detection accuracy; "patent-pending" deterministic IBAC (source 01) [VC].
- Intent Token draft: 5 ms enforcement target (spec aspiration) [VC-like].
- LF: A2A 150+ organizations and 22k+ stars. AP2: 60+ organizations. Mastercard VI partner commitments [VC].
- OWASP "intent capsule" recommendation and "least agency" principle: secondary sources only [S].
- "NIST AI 100-5 agentic profile" and "Agent Interoperability Profile Q4 2026": secondary, contradicted or unverified.
- AP2 v0.2 release date: GitHub digest year inconsistent; third parties say April 2026.
- Whisper-attack success rates (56–90%): peer-review status unknown (arXiv preprint).

## 17. Open questions
1. Which Txn-Token implementations exist? Does Keycloak (WAAG's IdP) support RAR, CIBA or Txn-Token issuance natively, or must the WAAG STS do all of it?
2. Will the Txn-Token claim names (`scope`/`tctx`) change again before the IESG? Pin to -11 and re-check in December 2026.
3. Which MCP protocol versions do WAAG's target front doors (the console, claude-desktop, Kore.ai) speak? When do they move to 2026-07-28, which breaks the session-based door filter?
4. Do real A2A callers set or accept server-minted `contextId`? Will they honour a `required:true` WAAG extension?
5. Should the intent schema be purpose-code-first (admin templates, option B) or human-confirmed typed constraints (option A)? This is a product and UX decision, not a standards one.
6. Does the customer's IdP have a CIBA-capable authenticator? If not, is a WAAG-hosted approval page (MCP URL-mode elicitation) acceptable as the "verifiable grant" that AIMS asks for?
7. Will MCP SEP-2848 be picked up by a WG after the governance change? Its immutable-call-binding model is the one WAAG should be compatible with.
8. Should WAAG pursue NCCoE or CAISI engagement (agent identity and authorization project) and FIDO Agentic Authentication WG membership for standards credibility?
9. Is a user-held signing key (WebAuthn, as in SEP-2672, AP2 and VI) needed for regulated buyers, or is gateway-minted-after-fresh-login enough?

## 18. Sources (accessed 2026-09-26)
- Txn-Tokens -11: https://datatracker.ietf.org/doc/draft-ietf-oauth-transaction-tokens/ ; https://www.ietf.org/archive/id/draft-ietf-oauth-transaction-tokens-11.html ; -02: https://www.ietf.org/archive/id/draft-ietf-oauth-transaction-tokens-02.html
- Txn-Tokens for agents: https://datatracker.ietf.org/doc/draft-araut-oauth-transaction-tokens-for-agents/ ; https://datatracker.ietf.org/doc/draft-oauth-transaction-tokens-for-agents/
- A2A Txn profile: https://datatracker.ietf.org/doc/html/draft-liu-oauth-a2a-profile-00
- RFC 9396: https://www.rfc-editor.org/rfc/rfc9396.html ; RFC 8693: https://www.rfc-editor.org/rfc/rfc8693.html ; RFC 9470: https://www.rfc-editor.org/rfc/rfc9470.html ; RFC 9901: https://www.rfc-editor.org/info/rfc9901/
- CIBA: https://openid.net/specs/openid-client-initiated-backchannel-authentication-core-1_0.html
- AAuth: https://datatracker.ietf.org/doc/draft-hardt-oauth-aauth-protocol/ ; https://www.ietf.org/archive/id/draft-hardt-oauth-aauth-protocol-11.html ; https://github.com/dickhardt/AAuth
- WIMSE AIMS: https://datatracker.ietf.org/doc/draft-ietf-wimse-aims/ ; https://www.ietf.org/archive/id/draft-ietf-wimse-aims-00.html ; WIMSE docs: https://datatracker.ietf.org/wg/wimse/documents/ ; klrc: https://datatracker.ietf.org/doc/draft-klrc-aiagent-auth/
- IAA: https://datatracker.ietf.org/doc/draft-jiang-oauth-intent-admission/ ; Intent Token: https://www.ietf.org/archive/id/draft-williams-intent-token-00.html ; Agentic JWT: https://datatracker.ietf.org/doc/draft-goswami-agentic-jwt/ ; AAP: https://datatracker.ietf.org/doc/draft-aap-oauth-profile/ ; OBO for agents: https://datatracker.ietf.org/doc/draft-oauth-ai-agents-on-behalf-of-user/
- ID-JAG: https://datatracker.ietf.org/doc/draft-ietf-oauth-identity-assertion-authz-grant/ ; Identity chaining: https://datatracker.ietf.org/doc/draft-ietf-oauth-identity-chaining/ ; AuthZEN claims: https://datatracker.ietf.org/doc/draft-gazitt-oauth-authzen-claims/
- AuthZEN 1.0: https://openid.net/authorization-api-1-0-final-specification-approved/ ; https://openid.github.io/authzen/
- OIDF: https://arxiv.org/abs/2510.25819 ; https://arxiv.org/html/2510.25819 ; https://openid.net/cg/artificial-intelligence-identity-management-community-group/ ; https://openid.net/oidf-responds-to-nist-on-ai-agent-security/
- MCP 2026-07-28: https://modelcontextprotocol.io/specification/latest ; /2026-07-28/changelog ; /basic/index ; /server/tools ; /client/elicitation ; /basic/authorization ; schema.ts https://github.com/modelcontextprotocol/specification/blob/main/schema/2026-07-28/schema.ts ; ext-auth https://github.com/modelcontextprotocol/ext-auth
- MCP SEPs: https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2848 ; /pull/2787 ; /pull/2672
- A2A: https://a2a-protocol.org/latest/specification/ ; https://a2a-protocol.org/latest/topics/extensions/ ; LF press: https://www.linuxfoundation.org/press/a2a-protocol-surpasses-150-organizations-lands-in-major-cloud-platforms-and-sees-enterprise-production-use-in-first-year
- AP2: https://ap2-protocol.org/ ; https://ap2-protocol.org/ap2/specification/ ; https://ap2-protocol.org/ap2/agent_authorization/ ; https://raw.githubusercontent.com/google-agentic-commerce/AP2/main/docs/ap2/specification.md ; https://github.com/google-agentic-commerce/AP2/releases
- FIDO / VI: https://fidoalliance.org/building-the-trust-layer-for-agentic-payments-with-ap2-and-verifiable-intent/ ; https://www.helpnetsecurity.com/2026/04/29/fido-alliance-ai-agents-authentication-payments-standards/ ; https://github.com/agent-intent/verifiable-intent/
- Whisper attacks: https://arxiv.org/abs/2609.11757
- W3C VC 2.0: https://www.w3.org/press-releases/2025/verifiable-credentials-2-0/ ; SPIFFE: https://spiffe.io/docs/latest/spiffe-about/overview/
- OWASP ASI: https://genai.owasp.org/2025/12/09/owasp-top-10-for-agentic-applications-the-benchmark-for-agentic-security-in-the-age-of-autonomous-ai/ ; secondary: https://docs.modulos.ai/frameworks/owasp-top-10-agentic ; https://auth0.com/blog/owasp-top-10-agentic-applications-lessons/ ; https://adversa.ai/blog/asi01-agent-goal-hijack-a-practical-security-guide/
- OWASP LLM: https://genai.owasp.org/llmrisk/llm01-prompt-injection/ ; https://genai.owasp.org/llmrisk/llm062025-excessive-agency/ ; 2026: https://www.helpnetsecurity.com/2026/08/06/owasp-2026-llm-top-10-released/
- NIST: https://www.nist.gov/itl/ai-risk-management-framework ; https://csrc.nist.gov/pubs/ai/100/2/e2025/final (PDF https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.100-2e2025.pdf) ; https://www.nist.gov/artificial-intelligence/ai-agent-standards-initiative
- CSA MAESTRO: https://cloudsecurityalliance.org/blog/2025/02/06/agentic-ai-threat-modeling-framework-maestro
- Internal: `docs/others/gateway-grounding.md` §13–14; `docs/others/Agentic-Gateway-Product-Brief.md` §9.5, §12; `docs/features/a2a-missing-governance-checks.md`; sources 01–05 in `intent-research/sources/`.
