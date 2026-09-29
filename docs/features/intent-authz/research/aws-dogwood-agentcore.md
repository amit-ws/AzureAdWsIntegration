# AWS Dogwood, AgentCore Policy/Identity, and real Cedar for Java: dossier for WAAG behavioural authorization

Research date: 2026-09-26. Author: research agent (AWS/Dogwood/Cedar area). Every external fact carries a URL. Internal facts cite the grounding doc (GG §n = `docs/others/gateway-grounding.md`) or the product brief (PB = `docs/others/Agentic-Gateway-Product-Brief.md`).

## Executive summary (10 lines)

1. Dogwood (AWS, announced 2026-08-06, Apache-2.0, Rust) is a history-aware add-on to Cedar. Every Cedar policy is valid Dogwood. It adds `when temporal {…}` conditions over a trace of timestamped `request`/`response`/`error` events.
2. The language is past-only, bounded MFOTL. It has three operators (`formerly`, `previous`, `since`), windows that are mandatory and capped at 24h by default, `count`/`sum` aggregates, `exists`, macros (`count_within`, `sum_within`, `count_distinct_within`, `bind`), and sandboxed "information providers" for computed scores.
3. Dogwood compiles temporal clauses into plain Cedar that reads a boolean `context.<id>` slot. A separate stateful temporal engine fills that slot from event history. This split is the key design lesson for WhiteSwan.
4. The public repo is a reference interpreter. It says it is not for production use, keeps history in memory with no cap, and publishes no latency numbers. It is a read-only mirror that takes no contributions, and there are no Java bindings.
5. AgentCore Policy (GA 2026-03-03) enforces Cedar at the AgentCore Gateway on every tool call. Temporal policies (2026-08-06) key their history on a session id that the caller supplies. There are hard quotas: 20 temporal policies per engine, 3 operators per policy, 24h windows. Only one authorization per session can run at a time, and every policy change invalidates open sessions.
6. AWS ships no "intent" feature. What it has is authoring-time NL→Cedar/Dogwood conversion, trajectory rules, and Bedrock Guardrails scores (prompt attack, content, PII) used as policy inputs. AWS itself calls those scores non-deterministic.
7. AgentCore Identity provides workload/agent identities, a token vault, 2LO/3LO OAuth, a signed opaque Workload Access Token that carries session + caller + service chain, and OBO. In OBO the customer's IdP does the exchange (RFC 8693/7523); AWS does not mint it.
8. cedar-java is alive (4.10.0, 2026-05-12, JDK 17+, JNI via JSON strings). I checked the `-uber` jar's own zip index: it bundles macOS arm64/x86_64, Linux glibc aarch64/x86_64 and Windows x86_64. The plain jar has no native library at all.
9. The March 2026 `UnsatisfiedLinkError` on ARM64 therefore most likely came from using the plain jar instead of the `uber` classifier (inferred, not reproduced). The ARM64 build had been in every release branch since 3.1.x. Alpine/musl and Windows-arm64 really are unsupported.
10. Bottom line: yes. Adopt real Cedar through `cedar-java:uber` now. Add Dogwood-style temporal leaves computed by a WhiteSwan Java engine over a per-trace event store keyed by the verified OBO trace/root human, and let Cedar make the final decision. Keep the Dogwood syntax as the authoring and interchange format. Do not ship the Rust reference interpreter on the request path.

---

## 1. Scope, method, confidence legend

- Inputs read in full: source 04 (Reva on Dogwood) and source 03 (CEO idea + Jev chat). I also read GG §6 (PDP), §12.4, §13 and §14, and the PB rows on the March cedar-java decision (PB:372, :422, :622, :802).
- External reading: the AWS OSS Dogwood post; the Dogwood README, CHANGELOG, Cargo manifests and guide chapters 00/03/04/05/06/07/08/11 (fetched raw from GitHub); the AgentCore Policy, Temporal, Session, Authoring, Guardrails, NL, Core-concepts, Identity and OBO docs; four AWS ML/Security blogs; the Maven Central listing and module metadata for `com.cedarpolicy:cedar-java`; the cedar-java `build.gradle`, `LibraryLoader.java` and FFI source; and the zip central directory of the uber jars (a byte-range read of the index only, with nothing executed).
- Nothing was cloned, installed or executed. The GitHub REST API rate-limited me midway, so later repo facts come from raw.githubusercontent.com.
- Legend: **[V]** verified from a primary source; **[VC]** vendor claim (marketing, not independently checked); **[I]** my inference; **[OQ]** open question.

---

## 2. Dogwood: what it is

| Item | Fact | Source |
|---|---|---|
| Announced | AWS Open Source Blog, 2026-08-06. Authors: Marc Brooker, Joseph Tassarotti, Jean-Baptiste Tristan **[V]** | https://aws.amazon.com/blogs/opensource/introducing-dogwood-runtime-verification-for-ai-agents/ |
| Positioning | A governance language for agent tool use that reasons over sequences of actions, not single requests **[V]** | same |
| Cedar relation | Extends Cedar. Every syntactically valid Cedar policy is valid Dogwood **[V]** | same; https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/policy-temporal.html |
| Formal basis | A bounded, past-only fragment of Metric First-Order Temporal Logic (MFOTL). Cites Basin et al., ACM 10.1145/2699444 **[V]** | guide ch.04: https://github.com/dogwood-policy/dogwood/blob/main/dogwood-docs/guide/04-temporal-expressions.md |
| Repo | `dogwood-policy/dogwood`: "Reference parser and interpreter". Rust (about 2.5 MB of source) plus Rhai. 402 stars, 30 forks. Created 2026-07-27, last push 2026-09-17 **[V]** (GitHub API, read 2026-09-26) | https://github.com/dogwood-policy/dogwood |
| License | Apache-2.0 **[V]** | repo LICENSE / API |
| Crate | `dogwood-language` 1.0.0 published on crates.io 2026-09-17 (42 downloads at read time). Depends on `cedar-policy` 4.11, `cedar-policy-core` 4.11, `cedar-policy-mcp-schema-generator` 0.6 and `rhai` 1 **[V]** | https://crates.io/crates/dogwood-language ; `dogwood-language/Cargo.toml` |
| Maturity | The README says the interpreter is **NOT intended for production use**. CONTRIBUTING says the repo is a read-only mirror of an internal Amazon repo and takes no external PRs or issues **[V]**. The CHANGELOG shows a breaking API change on 2026-09-11 **[V]** | README, CONTRIBUTING.md, CHANGELOG.md |
| Bindings | Rust library plus the `dogwood` CLI (`validate`, `lower`, `replay`, `schema mcp`). **No Java, Python or WASM bindings** exist **[V]** (the README gives only a Rust git dependency and CLI) | README |
| Production engine | The production engine is inside AgentCore Policy, which is closed source. The AWS docs publish no latency figures for it; they expose a `TemporalLatency` metric instead **[V]** | policy-temporal.html |
| AI-assistant skills | The repo ships agent skills that author Dogwood from natural language (Claude Code, Codex, Cursor, Copilot) **[V]** | README "AI agent integration" |

---

## 3. The language (what you can say)

### 3.1 Events and traces
- **Events.** An event is a timestamped occurrence of an action with a *kind* and named fields. The default schema declares three kinds: `request` (the decision kind, which carries inputs), `response` (inputs plus outputs, history only) and `error` (history only) **[V]** (guide ch.03). In AgentCore a denied request, or a tool error, is recorded as `error` **[V]** (https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/policy-temporal-authoring.html).
- **Trace format** used by the replay tool (from the OSS blog): each line is a timestamp, an action with its kind, the fields, and for decision events the verdict **[V]**. Events may interleave: a response can land after later requests **[V]**.
- **Pins** are schema-declared correlations appended to every predicate that cannot be bypassed. The default schema pins `callerPrincipal`, so history is evaluated **per principal key** ("key-local semantics"), and that also makes partitioned storage verdict-preserving **[V]** (ch.03, ch.04 "Evaluation semantics"). AgentCore's schema pins `sessionId` and requires `eventResource: resource` on every predicate **[V]** (authoring page).

### 3.2 Operators
| Construct | Meaning | Notes |
|---|---|---|
| `formerly within W φ` | φ held at some point within W | existential past |
| `previous within W φ` | the immediately preceding event matched φ and falls within W | false at t0 |
| `L since within W R` | R happened within W and L held at every step after it | the idiom is a **negated** left side, `!X since Y` ("no X since Y") |
| `&&`, `!`, `exists (x:T).`, `tp(t)` | conjunction, negation (anti-join), typed existential, timepoint binder | **no `||` inside a temporal block**; write two rules instead **[V]** |
| `count for (…) where φ`, `sum v for (…) where φ` | aggregates over deduplicated rows | no min/max/avg; an aggregate may only appear directly as a comparison operand **[V]** |
| Windows | `s`/`m`/`h`/`d`; inclusive bounds; **mandatory**; the `max_window` cap defaults to **24h** | a larger window needs a schema change **[V]** |
| Std-lib macros | `count_within`, `sum_within`, `count_distinct_within`, `bind` | defined in `DEFAULT_MACROS` (ch.06) **[V]** |
| Information providers | Sandboxed Rhai scripts (network access off by default) whose typed output is hoisted into `context.providers.<id>`. Bedrock Guardrails is AWS's provider | an erroring provider is *undefined*; the reference implementation denies (ch.00/05) **[V]** |
| Legality | The condition must be closed, safe-range (range-restricted variables), ordered producer-before-consumer and dependent on timepoints | a violation is a static error, not silent widening **[V]** (ch.04). Contrast WAAG, which drops unknown fragments (GG §6.2) |

### 3.3 The four behaviours the task asked about, as Dogwood (from AWS sources)
- **Count in a window**: forbid a transfer when `count_within(1h, …Transfer::request{input.amount:_}) > 5` (OSS blog). AgentCore's version is `exists (n: Long). (count for (t: Timepoint). where (formerly within 5m (…transfer_funds::request{eventResource: resource} && tp(t)))) == n && n > 3` **[V]**.
- **Cumulative value**: `sum_within(a, 1h, …Transfer::request{ input.amount: a }) > 5000` (OSS blog). AgentCore's version forbids once `sum amt … >= 3000` over 5 minutes, and there is a 24h `total < 60000` cap example **[V]**.
- **Approval happened before**: `formerly within 1h …ApproveSale::response{ input.stock: context.input.stock, input.shares: context.input.shares, output.approved: true }` (OSS blog) **[V]**.
  - **One-time-use approval**: `!…transfer_funds::response{…} since within 1h …get_account_balance::response{…}`, where the approval is consumed by the next completed transfer **[V]** (authoring page).
  - The AWS ML blog models human approval as an `approve_trade` **tool call** whose response event enters the trajectory **[V]** (https://aws.amazon.com/blogs/machine-learning/securing-ai-agents-with-temporal-policies-in-amazon-bedrock-agentcore/).
- **Sequences**:
  - "Tool B only after A": `formerly … A::response`;
  - multi-step chains, as one policy per link;
  - parallel prerequisites, as `formerly A && formerly B`;
  - mutual exclusion, as two symmetric forbids;
  - cool-down, as `formerly within 1m` on the same action's `response`;
  - "block after a prior denial", as `formerly … ::error`.

  All are **[V]** from the authoring page.
- **Output-to-input integrity (anti-fabrication)**: permit a transfer only if an earlier lookup *returned* that account (`output.accountId: context.input.toAccount`) **[V]**. This is the most intent-adjacent pattern AWS ships. It pins the agent's arguments to data the tools actually returned, not to what the LLM produced.

### 3.4 Limits stated by AWS
- Temporal conditions are **not covered by Cedar's automated-reasoning analysis**.
- Evaluation is stateful, and its cost can grow with the length of the event log.
- Both statements are **[V]**, from the OSS blog.
- Future work announced: absolute-time windows (for example daily quotas), liveness ("X must eventually happen"), and multi-agent orchestration **[V]**, from the OSS blog.

---

## 4. Evaluation model (who supplies the trace, where it lives, how fast)

### 4.1 Lowering + monitor
- Dogwood compiles each temporal (or provider) clause into a Cedar policy that reads a hoisted boolean `context.policy_N__temporal_M`. The README worked example shows the lowered form **[V]**.
- At authorization time a **TemporalEngine** computes each leaf's boolean and puts it into the context. Stock Cedar (`cedar_policy::Authorizer`) then decides **[V]** (ch.07 "The engine seam").
- The docs spell out the division of labour: Dogwood supplies the enriched context and the policy store makes the final Cedar decision **[V]** (ch.07, `is_self_contained_cedar()`).
- The engine seam is two pluggable traits:
  - `PolicyEngine`, which can be a remote Cedar store;
  - `TemporalEngine`, with `prepare(leaves, schema, events)`, `observe(event)` for every event in timestamp order, and `evaluate()` at each decision point **[V]**.
  - An `Err` from a backend fails the decision **closed** **[V]**.

### 4.2 Who supplies the trace
- **Library**: the host application builds `Event`s and feeds them to the stateful `Authorizer::is_authorized(&mut self, event)`. A request event must carry both the logged temporal record and the Cedar request context, supplied separately. The docs warn that providing only one of them silently weakens checks **[V]** (README security list; ch.07).
- **AgentCore**: the Gateway records the events itself. It records `request` on authorized calls, `response` when the tool returns and `error` on denial or failure. Recording is **session-scoped** and keyed by the `x-amzn-bedrock-agentcore-policy-session-id` header **[V]**.
- In AgentCore the response event is recorded shortly *after* completion. AWS tells clients to wait for the prior response before sending a dependent request **[V]** (policy-temporal.html "Sequencing"). **[I]** Recording is therefore asynchronous, the same hazard WAAG's async audit has (GG §13(f)).

### 4.3 Storage and scale
- The reference `InMemoryTemporalEngine` appends every event and **re-runs the interpreter over the trace so far** at each decision. It has no eviction, no size cap and no durability **[V]** (ch.07; README).
- Since 2026-09-11 there is optional "decision-leaf slicing", which evaluates only the leaves the current action can read **[V]** (CHANGELOG).
- AgentCore behaviour **[V]**:
  - it deletes events older than 24h;
  - a session expires after 24h idle;
  - "there can be no more than one concurrent authorization request per session" **[V]** (AWS ML blog, 2026-08-06).
- **Performance: no published numbers** from either the OSS project or AgentCore **[V]**. The only statement is the "depends on event-log length" caveat. AgentCore emits `TemporalLatency` in CloudWatch **[V]**. Cedar itself is described as O(n) in common cases, with no latency figures given **[VC]** (https://aws.amazon.com/blogs/security/why-policy-in-amazon-bedrock-agentcore-chose-cedar-for-securing-agentic-workflows/, 2026-05-20).
- **[I]** For WAAG the order of magnitude matters more than the exact figure. A per-trace history of tens to hundreds of events in memory, evaluated with window-indexed queries, should cost well under a millisecond. That is small next to 12-13 ms of governance and seconds of downstream time (GG §13(d)). This needs measuring.

---

## 5. Amazon Bedrock AgentCore Policy

### 5.1 Enforcement at the tool-call boundary
- Policy engines are attached to an AgentCore Gateway. The Gateway intercepts every agent→tool request and evaluates it before the tool runs, with default-deny and forbid-wins **[V]** (https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/policy.html; core concepts). GA was 2026-03-03 in 13 Regions **[V]** (https://aws.amazon.com/about-aws/whats-new/2026/03/policy-amazon-bedrock-agentcore-generally-available/).
- **Request model** **[V]** (https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/policy-core-concepts.html):
  - the principal is `AgentCore::OAuthUser`, taken from JWT `sub` with the other claims as *tags* (`principal.getTag("username")`), or `AgentCore::IamEntity`;
  - the action is `AgentCore::Action::"<Target>___<tool>"`;
  - the resource is `AgentCore::Gateway::"<arn>"`;
  - `context.input.*` holds the tool arguments, and `context.system.now` is available.
- **The schema is generated automatically** from the Gateway's tool definitions, with one action per tool and typed input records, so policies are validated at creation time **[V]**. Dogwood has the same MCP-manifest→Cedar generator: `dogwood schema mcp`, backed by the `cedar-policy-mcp-schema-generator` crate **[V]** (ch.11).
- **Modes**: `ENFORCE` and `LOG_ONLY`, set per engine or per policy **[V]**. AWS advises against switching production policies to `LOG_ONLY` **[V]** (ML blog).
- **Output control**: a `suppressOutput` effect blocks tool or agent output when guardrail checks fire. It supports only guardrail conditions, not Cedar or temporal ones **[V]** (https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/policy-guardrails-in-policies.html).

### 5.2 Guardrails as information providers (closest AWS thing to a content or intent signal)
- `BedrockGuardrails::PromptAttack` (JAILBREAK, PROMPT_INJECTION, PROMPT_LEAKAGE), `ContentFilter` and `SensitiveInformation` score selected `context.input.*` / `context.output.*` paths. Scores are discrete: 0, 0.2, …, 1.0 **[V]**.
- Default thresholds: 0.2 content, 0.4 prompt attack, 0.2 PII **[V]**. AWS says to tune them in LOG_ONLY, including by labelling with an LLM-as-judge **[V]**.
- The inline flow is: the Policy Evaluator calls Bedrock `InvokeGuardrailChecks`, then injects the scores into the Cedar context, then decides **[V]**.
- AWS itself states that guardrails are non-deterministic while policies are deterministic **[V]**. **[I]** This is the same architecture the Jev CEO proposed in source 03: a model produces a signal and the deterministic policy decides.
- There is a **doc inconsistency**. The Guardrails page says standard Cedar and guardrail conditions cannot be mixed. The temporal authoring page shows a single policy combining temporal, guardrail and Cedar `unless` blocks **[V]** (both pages). **[OQ]**
- Regional availability is narrower than Policy's **[V]**.

### 5.3 Natural-language authoring ("NL2Cedar" and NL→Dogwood)
- **NL2Cedar** turns English into Cedar, validates the result against the Gateway schema, and runs automated reasoning to flag always-allow, always-deny and unsatisfiable policies **[V]** (https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/policy-natural-language.html; core concepts).
- Inference may leave the Region but stays within the same geography (EU/US/APAC) **[V]**.
- **NL→Dogwood** (AWS ML blog, 2026-08-20, Swamy/Bai/Dong) runs four steps **[V]**:
  1. decompose the rules into atomic ones;
  2. route each rule by whether Dogwood can express it, and set the rest aside;
  3. autoformalize against the tool schema;
  4. validate with the open-source Dogwood CLI.

  The blog says a human must still review the output, because validation proves well-formedness, not meaning **[V]** (https://aws.amazon.com/blogs/machine-learning/authoring-dogwood-policies-from-natural-language-in-amazon-bedrock-agentcore/). It publishes no accuracy numbers **[V]**.
- **[I]** The docs say the service "interprets what the user intends". That is the *policy author's* intent at authoring time, not runtime intent.

### 5.4 Temporal policies in the product (the operational facts WhiteSwan should copy or beat)
- **Session.** The caller must send `x-amzn-bedrock-agentcore-policy-session-id` (1-128 characters of `[A-Za-z0-9-]`) **[V]** (https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/policy-session-based-temporal.html).
  - The Gateway does not generate one. If the engine has a temporal policy, a request without it fails.
  - Sessions are bound to the authenticated principal. On `authorizerType=NONE` all callers share a stream and the policy is "advisory only".
- **Security caveat (AWS's own words, paraphrased)**: a session-scoped count is not a hard limit against a determined caller, who can simply start a new session **[V]** (policy-temporal.html; authoring page).
- **Multi-hop continuity**:
  - The first Gateway mints a **Workload Access Token (WAT)** that carries the session id, the caller principal and the ordered workload chain (for example `[Gateway, Runtime, Gateway]`).
  - The token is AWS-signed, opaque and valid for 15 minutes.
  - It travels in the internal `X-Amz-Bedrock-AgentCore-Identity-WAT` header, and Runtime exchanges it for an extended WAT on every hop **[V]**.
  - This works **only within one AWS account and Region**. Non-AgentCore hops (your own API gateway or Kubernetes) must forward the WAT themselves **[V]**.
- **Quotas**: 20 temporal policies per engine, 3 temporal operators per policy, 24h maximum window **[V]** (policy-temporal.html).
- **Consistency**: adding or updating a temporal policy invalidates the engine's live sessions, and the next request gets HTTP 409 **[V]**.
- **Recording semantics**: only a permitted and completed action becomes a `response` event, and self-referential counts include the current request **[V]**.
- **Regions**: most commercial Regions, but not Hyderabad, Malaysia, Thailand, Milan or N. California **[V]**.
- **Rate limiting** was launched the same day and is separate from temporal policies. It caps requests, tokens and connection time per user or group across tools, models and agents, in per-second or per-minute windows **[V]** (https://aws.amazon.com/about-aws/whats-new/2026/08/temporal-policies-agentcore/; https://aws.amazon.com/blogs/machine-learning/control-agent-behaviors-and-cost-beyond-a-single-action-new-capabilities-in-amazon-bedrock-agentcore/).

---

## 6. "Intent" at AWS: what exists and what does not

- **No runtime intent feature exists in AgentCore** as of 2026-09-26 **[V]** (none in the Policy, Temporal, Guardrails or Identity docs; the temporal ML blog does not address intent or behaviour drift).
- AWS approximates purpose-alignment in three ways:
  1. **Trajectory constraints** (Dogwood): sequencing, anti-fabrication and budgets. These are behavioural, not semantic.
  2. **ML signals as policy inputs** (Guardrails prompt-attack, content and PII scores): content risk, not task alignment.
  3. **Authoring-time NL→policy**: the author's intent turned into rules.
- Reva (source 04, 2026-08-11) frames Dogwood as the "what happened before" dimension. It sells *intent* and *behaviour* on top as its own layer (the Reva Trust Gateway), plus trace-based policy analysis and HITL as a runtime workflow **[VC]**. Reva also says it uses Cedar internally and partners with AWS **[VC]**.
- **[I]** For WhiteSwan: Dogwood covers "behaviour-aware" authorization well, and intent needs a separate signal source. Dogwood's provider slot (`context.providers.<id>`) is the right place to plug in an intent-match score from a small local model (the CEO/Jev direction). Keep the model a *signal*, never the authority. Both AWS and the Jev CEO arrive at that same principle independently (source 03; Guardrails page).

---

## 7. AgentCore Identity (relevant parts)

- **Agent identities** are workload identities in an *agent identity directory*. Each has an ARN such as `…:workload-identity/directory/default/workload-identity/<agent>` and supports hierarchical governance **[V]** (https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/key-features-and-benefits.html; https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/identity-terminology.html).
- **Workload access token (WAT)** **[V]** (https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/get-workload-access-token.html):
  - It binds user identity and agent identity, and is used only against AgentCore first-party services.
  - Runtime and Gateway obtain it automatically through `GetWorkloadAccessTokenForJWT`, which validates the inbound IdP JWT's iss/sub.
  - `GetWorkloadAccessTokenForUserId` accepts an **unverified** user string. AWS recommends denying it wherever a JWT exists.
  - Service-managed identities cannot pull the WAT themselves.
- **Token vault**: KMS-encrypted OAuth tokens, client credentials and API keys. Only the same agent+user pair that stored a credential can read it back **[V]**.
- **OAuth**: 2LO (client credentials) and 3LO (auth code, with an optional AWS-hosted consent portal), plus built-in providers (Google, GitHub, Slack, Salesforce, Atlassian) **[V]**.
- **OBO** **[V]** (https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/on-behalf-of-token-exchange.html):
  - `GetResourceOauth2Token` with `oauth2Flow=ON_BEHALF_OF_TOKEN_EXCHANGE` brokers an exchange with **the customer's IdP**.
  - It supports RFC 8693, where the subject token is the inbound JWT and the actor token is `M2M`, `AWS_IAM_ID_TOKEN_JWT` or `NONE`, and RFC 7523 JWT-bearer (Entra OBO).
  - The IdP makes the final grant decision.
- **Contrast with WAAG** **[I]** from GG §5.8-5.9:
  - WAAG *mints* its own per-hop, 120-second, single-capability OBO. That token carries an explicit `act_chain` rooted at a verified human, plus `trace_id`, `corr_id` and `ws_tenant`, and works across vendors.
  - AgentCore's lineage is opaque (the WAT), confined to AWS, and records only which *AgentCore services* were traversed. Its OBO depends on each IdP's support for token exchange.
  - WAAG's verified, gateway-minted lineage is a real differentiator for a temporal "session" key, but only if WAAG closes the trace-propagation gaps in §9.3.

---

## 8. The real Cedar library for Java: current status

| Item | Fact | Source |
|---|---|---|
| Coordinates | `com.cedarpolicy:cedar-java`. Latest **4.10.0**, released 2026-05-12. Earlier 4.x: 4.8.0 (2025-12-11), 4.9.0 (2026-05-12), 4.3.1 (2025-03-13) **[V]** | https://repo1.maven.org/maven2/com/cedarpolicy/cedar-java/maven-metadata.xml and directory listings |
| Two jars | `cedar-java-4.10.0.jar` is **116 KB and contains no native library**. `cedar-java-4.10.0-uber.jar` is **27.9 MB** and bundles the natives **[V]** (listing plus a zip-index read) | same |
| Bundled natives (uber 4.10.0, 4.9.0, 4.8.0) | `jne/macos/aarch64/libcedar_java_ffi.dylib`, `jne/macos/x86_64/…dylib`, `jne/linux/aarch64/…so`, `jne/linux/x86_64/…so`, `jne/windows/x86_64/cedar_java_ffi.dll` **[V]** | zip central directory of the uber jars |
| Build targets | `aarch64-apple-darwin`, `aarch64-unknown-linux-gnu`, `x86_64-apple-darwin`, `x86_64-pc-windows-gnu`, `x86_64-unknown-linux-gnu`, cross-built with cargo-zigbuild. The **same list appears on release/3.1.x, 4.0.x, 4.2.x, 4.3.x, 4.8.x, 4.9.x and 4.10.x** **[V]** | `CedarJava/build.gradle` on each branch (raw.githubusercontent.com/cedar-policy/cedar-java/…) |
| Not supported | musl (Alpine), Windows arm64, Linux on other architectures **[V]** (absent from the target list) | same |
| Loader | `LibraryLoader` uses `com.fizzed:jne` to extract and load `cedar_java_ffi` for the running OS and architecture. The environment variable `CEDAR_JAVA_FFI_LIB` overrides it with an absolute path **[V]** | `CedarJava/src/main/java/com/cedarpolicy/loader/LibraryLoader.java` (release/4.10.x) |
| Recommendation from upstream | The README says to use the `*-uber.jar` because it carries the FFI shared library; the Gradle example is `…:uber` **[V]** | https://github.com/cedar-policy/cedar-java |
| JNI design | Java serializes each request to a JSON string and calls `callCedarJNI(op, json)`; Rust calls `cedar_policy::ffi::is_authorized_json_str`. There are cached policy-set and schema entry points in the FFI (`removeCachedPolicySetJni`, `removeCachedSchemaJni`) **[V]** | `CedarJavaFFI/src/interface.rs`; `BasicAuthorizationEngine.java` |
| Requirements | JDK 17+. Runtime deps: jackson-databind 2.20, jackson-datatype-jdk8, jne 4.5.3, guava 33.5 **[V]** | `.module` / `.pom` |
| Features | 4.3.0 added `Context`/`Entities` models, **policy annotations** and entity JSON. Unreleased on main: DateTime/Duration extensions, schema conversion, level validation, structured parse errors **[V]** | `CedarJava/CHANGELOG.md` |
| Lag | Cedar Rust is at 4.13.0 (2026-09-15); Dogwood builds on Cedar 4.11; cedar-java is 4.10.0 **[V]**. The README says CedarJava "typically lags" the Rust release **[V]** | https://crates.io/crates/cedar-policy ; README |

**Diagnosis of the March 2026 abandonment** (PB:372, :422). The failure was a `UnsatisfiedLinkError` on ARM64.
- The team's machine today is arm64 with OpenJDK 21 (local `uname -m`, `java -version`).
- The pom has no cedar dependency left, and git history holds no attempt, so the exact coordinates used are unknown **[V]** (local repo search).
- Ranked hypotheses **[I]**:
  1. **Most likely**: the plain jar was used without `<classifier>uber</classifier>`. It has no native library on *any* platform, so the first `isAuthorized` fails to link.
  2. The build ran in an Alpine/musl container, or on a JVM of the other architecture (x86 under Rosetta is fine; an arm Linux image with musl is not).
  3. JNE could not extract into a `noexec` or read-only temporary directory. This is a common hardening default; I have not verified it for JNE.
- **[OQ]** A 30-minute spike settles it:
  - add `cedar-java:4.10.0:uber`;
  - run one `isAuthorized` on macOS arm64 and on `linux/amd64` and `linux/arm64` glibc images (for example eclipse-temurin:17/21, not Alpine);
  - if the temporary directory is locked down, set `CEDAR_JAVA_FFI_LIB` to a pre-extracted library.
- A **fallback** with zero native code is also possible **[I]**: run Cedar's WASM build (`cedar-wasm`) on a pure-JVM WASM runtime (Chicory). That pattern is already used for OPA in Java (https://github.com/StyraOSS/opa-java-wasm). **[OQ]** No Cedar-on-Chicory project was found, and cedar-wasm is packaged for JS/TS (https://github.com/cedar-policy/cedar/blob/main/cedar-wasm/README.md). I would consider it only if JNI is blocked by customer hosting rules.

---

## 9. Mapping to WAAG today

### 9.1 What real Cedar fixes immediately (point-in-time)
- The silent widenings in GG §6.2 go away:
  - head `principal in AgentGroup` and `resource in Server` are ignored today;
  - unknown fragments are dropped;
  - `!`/`||` are mis-evaluated;
  - effect detection is keyword-first;
  - `resource == Tool` inside `when` never matches.

  In Cedar these either work, through entity hierarchy with parents, or fail to parse or validate **[I]**, based on Cedar semantics plus the Dogwood legality rules above.
- Cedar schemas give validation. **[I]** A per-tenant schema generated from the capability registry would also let the existing LLM policy assistant be checked mechanically: parse, validate, and run analysis for always-allow and always-deny, as NL2Cedar does. Today `/chat/save` enables unvalidated output (GG §6.12, §14 #11).
- **Obligations**. Cedar returns ALLOW/DENY plus the ids of the determining policies, and cedar-java supports annotations (4.3.0). **[I]** WAAG could read annotations such as `@advice("REQUIRE_APPROVAL")` on the determining forbid and turn the result into step-up or HITL. That gives the "REQUIRE_APPROVAL" outcome the Jev CEO describes without forking Cedar.

### 9.2 What temporal (Dogwood-style) adds, against WAAG's own data
WAAG already records exactly the raw material a trace needs (GG §9, §13(f)):
- every hop's action and capability;
- the sanitized arguments;
- the decision;
- response `fullText`;
- `trace_id`, `corr_id`, `act_chain`.

What is missing is **decision-time availability**:
- the PDP consults no history;
- audit is asynchronous and can drop rows;
- `InFlightRequestRegistry` is never looked up;
- MCP `structuredContent` and `isError` are dropped;
- MCP `trace_id` is minted per HTTP request unless it is propagated (GG §13(f), §14 #15, #28, App. A).

### 9.3 The "session" key: WhiteSwan can beat AgentCore here, but only after fixing propagation
- AgentCore uses a caller-chosen session id. Its own docs say that is weak as a hard limit.
- WAAG can key history on **gateway-minted, signed** values it already has: `trace_id` in the OBO, the root human id (`act_chain[0]`), the tenant, and the actor agent. Two gaps must close first **[I]**:
  1. mint the trace/task id at the *front door* and require it (and verify it) on every later hop, both MCP and A2A;
  2. key "per human" budgets on the root human across traces, so opening a new trace does not reset them.
- This mirrors Dogwood's *pin* concept. Offer the partition scopes explicitly: `trace` (one task tree across agents), `rootHuman` (cross-task budgets), `actor` (per-agent) and `tenant`.

### 9.4 Concurrency
- AgentCore allows only one concurrent authorization per session (§4.3).
- WAAG's canonical demo fans out *concurrently*: the advisor calls market-data, fundamentals and news in parallel (GG §4.6).
- **[I]** WAAG therefore needs linearizable append-then-evaluate per partition: a short per-trace lock or a single-writer queue. Evaluation must use the gateway's decision timestamp. Serializing whole hops is not an option, because it would deadlock or slow nested A2A.

---

## 10. Bottom line and "how"

**Can WhiteSwan adopt real Cedar plus Dogwood-style temporal policies? Yes, with a split architecture.** Take Dogwood's *design* and its *syntax*. Do not take its runtime. Real Cedar goes into the JVM through cedar-java; the temporal leaves are computed in Java.

### 10.1 Target architecture (request path)
```
HopOrchestrator (after act_chain, before PDP)
  1. build Cedar request: principal=Agent::"<azp>" (parents: AgentGroup::*), action=Action::"<cap>",
     resource=Tool|Skill::"<publicName>" (parent Server::"<s>"), context={input:{...typed args},
     chain:{rootType,rootId,rootVerified,actorVerified,depth}, time:{...}}
  2. TemporalEvents.appendRequest(partitionKeys, cap, args, ts)        // synchronous, in-memory
  3. leaves = TemporalEngine.evaluate(policiesNeedingLeaves(cap), partitionKeys, request)  // booleans
  4. providers = Providers.evaluate(...)   // e.g. intent-match score, injection score (optional, later)
  5. context.temporal.<id> = leaves; context.providers.<id> = providers
  6. cedar-java isAuthorized(request, cachedPolicySet, entities) -> decision + determining ids
  7. map annotations of determining policies -> ALLOW | DENY | REQUIRE_APPROVAL (obligation)
  8. on dispatch completion -> appendResponse(outputs) ; on deny/error -> appendError(...)
```
- The event store is an in-memory, window-indexed, per-partition structure with 24h eviction (the same cap as Dogwood and AgentCore), with a Postgres write-behind for durability and replay.
- If WAAG runs as more than one instance (unknown, GG §15 #2), either route requests sticky by `trace_id` or move the hot store to Redis.

### 10.2 Phased plan
1. **Days (P0 correctness)**:
   - Spike `cedar-java:4.10.0:uber` on arm64 and x86_64 glibc (§8).
   - Benchmark the JNI + JSON cost per call on a policy set cached in the FFI. **[OQ]** The number is unmeasured; the target is under 1 ms p50.
   - Replace the regex engine behind the existing `CedarPolicyEngine` facade.
   - Generate per-tenant Cedar schemas from the registry (tool `inputSchema` becomes `context.input`), following Dogwood's and AgentCore's MCP-schema approach.
   - Migrate the 21 stored policies. Fail the migration loudly wherever the old semantics were wider (GG §6.9 `financial-desk-grant`).
2. **Weeks (behavioural v1)**:
   - Build a Java `TemporalEngine` supporting the **AWS-documented pattern catalogue**: formerly, previous, negated-since, count/sum/count-distinct within a window, cool-down, mutex, block-after-error, and output→input integrity.
   - Store policies as **Dogwood text**. Parse only this subset in Java, and reject anything outside it (never drop it).
   - Mirror Dogwood's legality rules, which include mandatory windows and closed/safe-range conditions.
   - Record `request`/`response`/`error` events at the spine. Response events need the structured output, so fix the dropped `structuredContent` (GG §14 #15).
3. **Weeks (governance UX)**:
   - **Trace replay / what-if**: run a draft policy against real recorded trajectories from `pdp_audit_log`/`gateway_audit_log` before enabling it. This is the capability Reva markets and AWS lacks for temporal policies, because Cedar analysis does not cover them.
   - **Export traces in the Dogwood `.log` format**, so the team can use the Dogwood CLI offline as a *differential-test oracle* for the Java engine. The CLI would run in CI or a developer sandbox, never in the gateway. This is the team's decision to make later.
   - Add **LOG_ONLY per policy**, and **session-version stamping** instead of AgentCore's hard 409: record which policy version a trace started under.
4. **Later (intent)**: add providers:
   - an intent/task-alignment score from a local small model on A2A `input` text (source 03 direction);
   - WAAG's existing prompt-injection regex from the egress classifier, reused request-side.

   Each is exposed as `context.providers.*` with explicit thresholds and a LOG_ONLY calibration loop (the AWS Guardrails method). HITL uses approval *events*: an approval API writes an `Approve::response` event, which policies consume with `!X since Approve`. This is AWS's pattern, and it removes the need for an approval tool the agent could call on itself.

### 10.3 Why not the Dogwood Rust interpreter directly
Every point here is backed by §2 and §4.
- The repo says it is not for production.
- History has no cap, no durability and no tenancy; a single history is shared by default.
- There are no Java bindings. Options would be a JNI shim written in-house or a sidecar, and both add a second native or network dependency on a 12 ms path.
- Upstream takes no external PRs or issues.
- The API is still breaking (2026-09-11).

**[I]** Revisit if AWS publishes Java bindings or a production temporal engine.

### 10.4 Risks
- **Semantic drift** from Dogwood if the Java subset reimplements it wrongly. Mitigation: differential replay tests, and support only the subset the replay tests cover.
- **Event integrity**. Response events come from downstream servers, so an "approved: true" output from a compromised tool becomes trusted history **[I]**. Approvals should come only from the gateway's own approval API, not from tool outputs.
- **Temporal policies cannot be statically analysed**. Compensate with trace replay and LOG_ONLY.
- **Memory and PII**. Stored arguments and outputs are sensitive. The 24h eviction and tenant partitioning must be enforced; the README calls both out as production duties.
- **Latency**. Pre-PDP time already reaches p95 6-8 ms because of DB reads (GG §13(d)). The temporal store must stay off the DB on the hot path.

---

## 11. Product view (for the owner)

- **Positioning versus AgentCore** **[I]**:
  - AgentCore brings Cedar plus temporal plus guardrails to AWS-hosted tools, and only inside one account and Region.
  - WhiteSwan can bring the *same policy language family* to heterogeneous MCP + A2A estates across clouds.
  - WhiteSwan's history key is **verified human-rooted lineage** instead of a caller-chosen session header.
  - The message is "Cedar/Dogwood-compatible policies, enforced on any agent, keyed to the real human". That lowers switching cost for AWS-literate buyers and avoids a proprietary DSL.
- **Features that demo well** (all shown by AWS as the canonical cases):
  - anti-fabrication (transfer only to an account a tool returned);
  - cumulative budget per human per day;
  - one-time approval;
  - no external send after a sensitive read (the AWS healthcare example);
  - cool-down.
- **Do not claim** "intent-aware" for the temporal layer. It is behaviour- and trajectory-aware. Claim intent only once a provider signal exists, and label it probabilistic.
- **Credibility fix first**: the live regex PDP currently widens grants (GG §6.9). Moving to real Cedar is a precondition for selling any richer authorization.

---

## 12. Open questions

1. Exactly which cedar-java artifact/classifier and runtime (OS image, libc, temporary directory) produced the March `UnsatisfiedLinkError`? It is not in git or the pom.
2. What is the per-call JNI + JSON latency of cedar-java 4.10 at WAAG's policy counts, and does its public Java API expose the FFI's cached policy set (`removeCachedPolicySetJni` exists in Rust)?
3. What is AgentCore's real temporal evaluation latency and history-size limit? Only the metric name is public.
4. Can AgentCore combine guardrail and standard Cedar conditions in one policy? Its docs contradict each other.
5. Will AWS release Java bindings or a production temporal engine for Dogwood, and when will the promised liveness and absolute-time operators land?
6. Is WAAG single-instance in production? This decides between an in-memory store with sticky routing and Redis.
7. Which trace/task id should anchor "session" for MCP-only callers whose `trace_id` is minted per HTTP request?
8. Do the parallel A2A children of one trace need a strict total order for `previous`/`since` semantics, or is ordering by gateway timestamp enough?
9. How do we authenticate approval events? Through a gateway-owned approval API, with the approver's identity recorded in the event?
10. Is there a maintained Cedar-on-Chicory (WASM) path, as a JNI-free fallback?

---

## 13. Sources

- AWS OSS Blog, *Introducing Dogwood: Runtime Verification for AI Agents*, 2026-08-06: https://aws.amazon.com/blogs/opensource/introducing-dogwood-runtime-verification-for-ai-agents/
- Dogwood repo and guide: https://github.com/dogwood-policy/dogwood (README, CONTRIBUTING.md, CHANGELOG.md, Cargo.toml); https://dogwood-policy.github.io/dogwood/ ; guide chapters under `dogwood-docs/guide/` (00, 03, 04, 05, 06, 07, 08, 11)
- crates.io: https://crates.io/crates/dogwood-language ; https://crates.io/crates/cedar-policy
- AgentCore Policy: https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/policy.html ; …/policy-core-concepts.html ; …/policy-temporal.html ; …/policy-session-based-temporal.html ; …/policy-temporal-authoring.html ; …/policy-guardrails-in-policies.html ; …/policy-natural-language.html
- AgentCore Policy GA (2026-03-03): https://aws.amazon.com/about-aws/whats-new/2026/03/policy-amazon-bedrock-agentcore-generally-available/
- Temporal policies + rate limiting (2026-08-06): https://aws.amazon.com/about-aws/whats-new/2026/08/temporal-policies-agentcore/ ; https://aws.amazon.com/blogs/machine-learning/control-agent-behaviors-and-cost-beyond-a-single-action-new-capabilities-in-amazon-bedrock-agentcore/
- *Securing AI agents with temporal policies…* (2026-08-06): https://aws.amazon.com/blogs/machine-learning/securing-ai-agents-with-temporal-policies-in-amazon-bedrock-agentcore/
- *Authoring Dogwood policies from natural language…* (2026-08-20): https://aws.amazon.com/blogs/machine-learning/authoring-dogwood-policies-from-natural-language-in-amazon-bedrock-agentcore/
- *Why Policy in AgentCore chose Cedar* (2026-05-20): https://aws.amazon.com/blogs/security/why-policy-in-amazon-bedrock-agentcore-chose-cedar-for-securing-agentic-workflows/
- AgentCore Identity: https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/identity.html ; …/key-features-and-benefits.html ; …/identity-terminology.html ; …/get-workload-access-token.html ; …/on-behalf-of-token-exchange.html
- cedar-java: https://github.com/cedar-policy/cedar-java (README; `CedarJava/build.gradle` on release/3.1.x-4.10.x; `CedarJava/CHANGELOG.md`; `LibraryLoader.java`; `CedarJavaFFI/src/interface.rs`); Maven Central https://repo1.maven.org/maven2/com/cedarpolicy/cedar-java/
- WASM fallback references: https://github.com/StyraOSS/opa-java-wasm ; https://github.com/cedar-policy/cedar/blob/main/cedar-wasm/README.md
- Captured source 04: Reva, *AWS Dogwood and the Emerging Architecture for Agent Authorization* (2026-08-11): https://www.reva.ai/blog/aws-dogwood-and-the-emerging-architecture-for-agent-authorization
- Internal: `docs/others/gateway-grounding.md` (§4.6, §6, §9, §12.4, §13, §14, §15); `docs/others/Agentic-Gateway-Product-Brief.md` (:372, :422, :622, :802)
