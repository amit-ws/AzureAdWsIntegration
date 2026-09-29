# The no-model path: how far WAAG can take intent-aware and behavior-aware authorization with deterministic and statistical mechanisms only

*Analysis for the WhiteSwan Agentic Auth Gateway (WAAG) intent research. Written 2026-09-26. This is a design analysis: nothing was built, run or measured for it. Every WAAG fact cites the grounding doc or a dossier. Every external fact cites a URL, usually through the dossier that verified it.*

> ## Verdict
>
> 1. **Without any language model, WAAG can enforce intent. It cannot understand it.** Once the task's purpose is a typed, signed object, carried in every per-hop OBO and narrowed but never widened, every downstream decision is a deterministic comparison. That covers the capability, the arguments, the target, the effect class, the budget, the sequence, the taint and the delegation depth.
> 2. **Coverage on our own catalogue.** Of the 33 candidate intent decisions in the internal-fit brief, **25 can be enforced at the action with no model** and **8 more become deterministic if the root intent carries the typed field** (target id, environment, path, purpose code). The internal-fit hypothesis was about 22. This recount is on paper, not measured (§6.2).
> 3. **By OWASP risk.** The no-model stack is strong on **ASI02** (tool misuse), **ASI03** (privilege abuse) and **ASI08** (cascading failures). On **ASI01** (goal hijack) and **ASI06** (context poisoning) it gives strong *containment*: a hijacked agent cannot act outside the envelope, the budget or the Rule of Two. It gives *no detection* of the hijack itself.
> 4. **The LinkedIn "wrong action at the right permission level" case splits in two.** If the wrong action is consequential or outside the declared task, an effect-class × purpose rule catches it and turns it into an approval. If it is inside the task and has the same effect class, nothing at the gateway catches it, model or not. The gateway cannot see the page or state that made the action wrong. Statistical baselines make this worse: a mistake that recurs (39% of runs in the commenter's test) gets learned as normal.
> 5. **A semantic model is required for one job only:** turning unstructured language into typed facts. That covers four things: prose → typed intent at the root; judging free-text A2A messages; pulling targets out of prose; and paraphrase-level provenance or similarity. The model can *propose* or *tighten*. It must never *grant*. On WAAG the binding constraint is that the human's words never reach the gateway (GG §12.4). So the first dependency is the **front door**, not the model.
> 6. **Build order.** First fix the PDP (silent widening, no third outcome, no history). Then build effect labels → parent-scope narrowing → REQUIRE_APPROVAL → trace-scoped temporal state and taint → declared purposes and envelopes → baselines, in that order. Every item except baselines reuses data WAAG already holds.

---

## 0. Sources, keys and legend

| Key | Source |
|---|---|
| GG §x | `/Users/amitprakash/Desktop/WS Apps/AzureAdWsIntegration/docs/others/gateway-grounding.md` (verified code facts, 2026-09-25, hand-rechecked 2026-09-26) |
| PB:n | `.../docs/others/Agentic-Gateway-Product-Brief.md` |
| A2AGAP #n | `.../docs/features/a2a-missing-governance-checks.md` |
| IF §x | `…/intent-research/research/internal-fit.md` |
| DW §x | `…/research/aws-dogwood-agentcore.md` |
| STD §x | `…/research/standards.md` |
| ACA §x | `…/research/academic.md` |
| REVA §x | `…/research/reva.md` |
| TT §x | `…/research/tealtiger-dakera.md` |
| SM / JEV / VL §x | `…/research/small-models.md`, `jev.md`, `vendor-landscape.md` |
| S01–S05 | `…/intent-research/sources/`: 01 Reva IBAC whitepaper, 02 LinkedIn LangChain/SemIf post + comments, 03 CEO idea + Jev CEO chat, 04 Reva on Dogwood, 05 Reva on Inference Hooks |

Labels:
- **[V]** verified in a primary source (through the dossier that verified it).
- **[AR]** author-reported number from a paper, not reproduced.
- **[VC]** vendor claim.
- **[J]** my judgment or estimate.
- **[OQ]** open question.

"No model" here means **no language model, embedding model or learned text classifier** on the request path. Statistics over counts, sets, sequences and categorical values are in scope. Using an LLM at *authoring* time (drafting labels or envelopes offline, reviewed by a human) is allowed, because it is not on the decision path.

---

## 1. The target: what the no-model path must answer

### 1.1 The five OWASP risks in scope
From the OWASP Top 10 for Agentic Applications (2025-12-09, https://genai.owasp.org/2025/12/09/owasp-top-10-for-agentic-applications-the-benchmark-for-agentic-security-in-the-age-of-autonomous-ai/) [V via STD §12]. The definitions are paraphrased.

| ID | Risk | What a gateway would have to do about it |
|---|---|---|
| ASI01 | Agent Goal Hijack: the agent's objective is redirected, e.g. by instructions hidden in ingested content | Hold an anchor the agent cannot rewrite, and bound what a redirected agent can do |
| ASI02 | Tool Misuse & Exploitation: a legitimate tool used in an unsafe or unintended way | Judge the call, its arguments and its effect against the task |
| ASI03 | Identity & Privilege Abuse: privileges outlive or exceed their authorizing context | Scope authority to the task and the hop, and expire it |
| ASI06 | Memory & Context Poisoning: planted content steers later steps | Stop poisoned content from widening authority or driving consequential actions |
| ASI08 | Cascading Failures: one corrupted hop propagates | Stop a child from exceeding its parent, and stop the chain as a whole |

Reva maps these same five to its "two decays" (S01 Table 1). The mapping is useful vocabulary, but Reva's intervention column relies on an LLM judge (REVA §3).

### 1.2 The "wrong action at the right permission level" case (WRAP)
- **Source.** In the LinkedIn thread (S02), a commenter measuring browser agents describes a legitimate Logout click that no permission check could block. The call itself was unsafe in no way. He calls it "the wrong action at the right permission level".
- **What caused it.** Element *position* in the agent's perceived list. Moving the element from first to fourth took the rate from 39% to 0 over 26 runs.
- **How he catches it.** Only after the fact, by comparing the page's real state with what the agent reported. He asks whether decision-time scoring of "does this action advance the task" is feasible.
- **WAAG analogue.** An agent picks a tool that is in its profile, with valid arguments, that does not serve the task. Examples: `k8s_scale replicas=0` during a diagnosis; `merge_pull_request` during "summarize PRs"; `BALANCE_SHEET` when asked for earnings (IF §7, F5, G1, I1).

For this analysis I split WRAP in two:
- **WRAP-a (out of task).** The wrong action is outside the declared task, or of a more consequential effect class than the task needs.
- **WRAP-b (in task).** The wrong action is inside the task's allowed set, with the same effect class. It is wrong only because of state the gateway cannot see: which element was first, what the page showed, what the agent believed.

§6 shows WRAP-a is tractable with no model. WRAP-b is not tractable at a gateway at all.

---

## 2. The raw material WAAG already has (and the holes)

| Fact the mechanisms need | Status today | Cite |
|---|---|---|
| Verified, human-rooted `act_chain` in a gateway-signed 120 s OBO | **Exists**. Hard invariants: prefixPreserved, appendOnly, subConstant, rootPresent, monotonicRoles | GG §5.8–5.9 |
| Parent linkage: inbound OBO `corr_id` = parent correlationId; inbound `scope` = parent's capability | **Carried, never read** | GG §4.6, §13(f) |
| Parent's A2A text while the child is decided | **In memory** in `InFlightRequestRegistry`, keyed by the child's `corr_id`. No getter; per-JVM | GG §13(f); IF §4 |
| Full untruncated arguments, descriptor (description, inputSchema), RequestContext, act_chain | **All in scope** in HopOrchestrator between the registry lookup and `buildFor*`, ×4 legs | GG §13(c) |
| MCP tool annotations (read-only/destructive/idempotent/open-world hints) | **Dropped** at registration | GG §7.4 |
| A2A skill input schema | **Does not exist** (skills keep id + description only) | GG §7.4 |
| MCP `_meta`, A2A DataParts/metadata/contextId/taskId | **Dropped** (contextId becomes sessionId only) | GG §1 fact 10, §4.2 |
| Per-trace history at decision time | **None**. The ledgers are async, drop rows when full, and nothing reads them inline | GG §9.2, §13(f) |
| Response sensitivity + injection flag | **Exists**: async, observe-only, keyed by trace and correlation id. `provenance_categories` is reserved but never written | GG §8.1, §8.4 |
| Per-agent counters | AGENT_FIELD custom attributes, **one DB query each**, 0 configured | GG §6.7 |
| 6-signal human/NHI risk score | **Admin-only**, keyed by session (misses A2A) | GG §7.2 |
| PDP expressiveness | `==`, `!=`, integer compare, `like`, `.contains`, `&&`. No OR/NOT, no `in [...]` in conditions, no structured args, ALLOW/DENY only. A missing attribute makes a condition false, including `!=` | GG §6.2–6.3, §13(b) |
| Human's own words | **Never reach the gateway** on the console path; hop-1 text is the console LLM's paraphrase | GG §12.4 |
| Latency budget | Governance ~12–13 ms p50/hop; downstream MCP 1.4 s, A2A 6.9 s p50; everything blocking; one shared async pool (4/16/2000, drops) | GG §13(d) |

**Prerequisites that would defeat every mechanism below** (IF §5, ranked there):
- the PDP silently widens grants: the live `financial-desk-grant` permits any agent, action and resource when the root is verified (GG §6.9);
- both lineage guardrails are disabled in the live tenant;
- `/a2a` has no status or `jti` gates, and a PENDING agent was ALLOWed 8 times (A2AGAP #1–#5);
- `approvalStatus` is always UNKNOWN on MCP;
- custom attributes can overwrite built-ins, and HEADER attributes are caller-supplied (GG §6.7);
- the PDP sees A2A `input` cut at 2000 chars while the full text is forwarded (IF P6).

I treat these as **Stage 0**. None of the designs below is sound on top of them.

---

## 3. The shape of the no-model stack

The idea borrowed from Dogwood is its **lowering** split: a stateful engine computes typed leaf values, and a stateless policy decides over them (DW §4.1). The stateful engine (Java) computes typed leaf attributes from signed credentials, gateway-observed history and registry labels. The PDP stays simple and makes the final decision. This works on the current regex engine (string/bool/long equality), and it works better on real Cedar with typed `context.input` (DW §10.1).

```
A2aInboundController / HttpMcpAuditFilter        (capture: declared intent / upstream mandate)   [M1]
        │
HopOrchestrator, after act_chain, before PDP  ── one shared "IntentContextStage" (today the seam is copied ×4, GG §13(c))
   1. verify intent claim from inbound OBO; check child ⊆ parent                               [M1, M3]
   2. registry labels: effect class, openWorld, ingestsUntrusted, sensitivity tier             [M5]
   3. envelope check: capability ∈ caps, typed args within constraints, target == intent target [M2]
   4. trace state (in-memory, per trace / root human / agent): append request, evaluate leaves   [M4]
   5. trace taint (confidentiality max, integrity low?) + request-side DLP on outbound args     [M7]
   6. baseline snapshot lookup: novel capability / edge / target, volume and fan-out quantiles  [M6]
   → context.intent.*, context.trace.*, context.taint.*, context.baseline.*, resource.effect*
PDP  → ALLOW | REQUIRE_APPROVAL | DENY   (obligation from the determining policy)              [M8]
mint OBO (copies intent claim byte-for-byte; may add narrowing only)                             [M1, M3]
dispatch → on response: sync classify (capped) → update taint; append response/error event    [M4, M7]
```

**Combination rule.** Every signal can only *tighten*. The outcome lattice is DENY > REQUIRE_APPROVAL > ALLOW. This is the same "narrow, never broaden" property that Reva's PEPs implement (REVA §5.2) and that IGAC and IntentCap argue for (ACA §4.2).

**Polarity and fail mode.** In the current engine a missing attribute makes a condition false (GG §6.2). So `forbid … when {context.x == "FAIL"}` fails **open** if the stage crashes, because the `CustomAttributeProvider` SPI swallows exceptions (GG §13(c)). Every leaf is therefore a **tri-state string** `PASS | FAIL | UNKNOWN`, and it is never simply absent. Permits require `== "PASS"`. Forbids or approvals fire on `FAIL` and, for consequential capabilities, on `UNKNOWN` (two rules, because there is no OR). The namespace is reserved so that custom attributes cannot overwrite it (GG §6.7).

---

## 4. Mechanism by mechanism

Each mechanism uses the same template: what it is, what it catches (mapped to ASI01/02/03/06/08 and WRAP), what it cannot catch, the data it needs, its cost, and the code seam.

### M1. Declared intent captured once at the root, carried in the OBO

**What it is.**
- A typed intent object minted at the front door and bound into the root OBO. It carries:
  - a purpose code;
  - allowed capability classes;
  - typed constraints: target ids, amount caps, environment, path or domain patterns, data classes;
  - expiry;
  - approval evidence.
- Shape: use the Txn-Token layout. In draft -11 the immutable context is `tctx` and the purpose is `scope`. The older `purp`/`azd` names date from -02 and are stale (https://www.ietf.org/archive/id/draft-ietf-oauth-transaction-tokens-11.html, 2026-07-30) [V via STD §1].
- Typing: RFC 9396 `authorization_details` (https://www.rfc-editor.org/rfc/rfc9396.html) [V].
- Integrity: a hash `intent_s256` over the JCS-canonical object, the same primitive as AAuth `mission_s256`, IAA `intent_ref` and AP2 `checkout_hash` (STD §4 takeaway).
- **WAAG-specific conflict.** WAAG's `scope` is the per-hop capability (GG §5.8), so the purpose needs its own claim (STD §1 "semantic conflict").

**No-model sources of intent, strongest first** (STD §13.2):
1. **App-bound purpose (zero UX, [J]).** The front-door client registration (`azp`, e.g. `agent-console`) maps to a fixed set of allowed purposes, just as OAuth clients map to scopes. The financial console would get `research.readonly`.
2. **Registered workflow / purpose template picked by the front door.** This is the A-JWT `workflow_id` idea (ACA §4.5) or an AAuth mission (STD §4).
3. **A human-confirmed typed card**: purpose plus typed fields such as the symbol, minted after a fresh login (RFC 9470 `max_age`).
4. **Accepted upstream artifacts**: an inbound Txn-Token `tctx`, an AP2 open mandate, Mastercard Verifiable Intent. Verify, then adopt (STD §10). AP2 v0.2 open mandates carry typed constraints plus the agent key in `cnf`, and the verifier checks each constraint against the closed action (https://ap2-protocol.org/ap2/specification/) [V via STD §10].

Option (C) in STD §13.2, "derive intent from the first A2A text", is excluded here: it needs a model.

**What it catches.**
- By itself it blocks nothing. It is the **reference** that M2, M3, M4 and M7 compare against.
- **ASI01**: the anchor is signed by the gateway and immutable, so a hijacked agent cannot rewrite its goal on the wire. That is a real difference from Reva, whose anchor is the first `traceparent`-keyed utterance and can be reset by a caller (REVA §9.2 item 2).
- **ASI03**: intent `exp` and `cnf` bind authority to its authorizing context.
- **ASI08**: every hop is compared with the root, not with the parent's LLM paraphrase.
- **WRAP**: it supplies the "task" the commenter lacked, but only at the granularity that was declared.

**What it cannot catch.**
- Whether the declared purpose itself is honest or too broad. A console that declares `general.assistant` makes every later check vacuous.
- Anything not captured in typed fields.
- Chains with no OBO. Autonomous mode sends client-credentials tokens, and the chain restarts with no `trace_id` or `act_chain` (GG §4.6). That needs an NHI-rooted "mission" instead.
- Tampering *before* the front door: if the console LLM chooses the purpose, the purpose is LLM-derived and hijackable. The purpose must come from the app registration, the human or a fixed workflow.

**Data it needs.**
- A capture field at the front door. None exists today (GG §13(g)). Candidates:
  - an A2A extension in `message.metadata`, declared on WAAG-fronted cards (https://a2a-protocol.org/latest/topics/extensions/) [V via STD §9];
  - an MCP `_meta` vendor key `io.whiteswan/intent` (MCP 2026-07-28 reserves the `mcp`/`modelcontextprotocol` prefixes, https://modelcontextprotocol.io/specification/2026-07-28/changelog) [V via STD §8];
  - a console-side API.
- A purpose catalogue per tenant (new table).
- The existing STS claim path (GG §5.8).

**Cost.**
- Runtime: one JCS + SHA-256 per root mint, and a byte copy per hop. Sub-millisecond [J, STD §13.3]. The token grows by a few hundred bytes.
- Engineering: small in the STS; medium at the front door.
- **The real cost is product**: who declares the purpose, how precisely, and with how much consent friction (OIDF notes consent fatigue, STD §7).

**Code seam.**
- Capture:
  - `A2aInboundController` between parse and `handle` is the last point where the full `MessageSendParams` exists (protocol/a2a/inbound/A2aInboundController.java:124-145);
  - `A2aMessageMapper` currently discards metadata (A2aMessageMapper.java:48-70);
  - on MCP, `_meta` has to be threaded past the SDK boundary that drops it (protocol/mcp/inbound/HttpMcpServerInitializer.java:225-229).
- Mint: `StsService.mint` (sts/service/StsService.java:78-105) and `HopTokenMinter.mintForHop` (:54-84).
- Read: the inbound claims are already in `RequestContext.rawJwtClaims` (IF §1.2 row 3).

### M2. Intent → capability envelopes (allowed tools/skills and argument ranges)

**What it is.** Each purpose maps to an envelope:
- allowed capability ids or classes;
- allowed servers or agents;
- per-capability **argument constraints** (sets, integer ranges, patterns, equality to an intent field such as `symbol == intent.target.symbol`);
- an effect ceiling (see M5);
- budgets (see M4).

It is authored per purpose template, optionally drafted offline by the existing policy assistant and reviewed. It is *not* auto-enabled the way `/chat/save` enables policies today (GG §6.12).

The LangChain "agent card" in S02 is the same idea at the agent level: allow, evaluate or deny per tool (VL §3.14). Capability profiles already supply WAAG's allow/deny tier (GG §7.5). The envelope adds **per-task** and **per-argument** narrowing on top.

**How it runs on today's PDP.** The engine has no structured argument access and no set membership in conditions (GG §6.2). The Java stage therefore evaluates the envelope against the **full untruncated arguments** and the descriptor's `inputSchema`, then emits leaves such as `context.intent.capInEnvelope`, `context.intent.argsInBounds` and `context.intent.targetMatch` (PASS/FAIL/UNKNOWN). With real Cedar the same constraints become typed policies over `context.input`, validated against a schema generated from the registry (DW §10.2).

**Argument parsers.** For free-form string arguments (SQL, SOQL, shell, file paths, URLs), add deterministic parsers that extract verb, table, field list, `LIMIT`/`WHERE` presence, destination domain and path. These let the envelope say "read-only SQL, `LIMIT` ≤ 500" (IF C1, I4) with no model.

**What it catches.**
- **ASI02** (strong):
  - capability outside the task (IF F9, G1, R3);
  - arguments outside range (refund above the cap, `outputsize=full` on a quote task, `workspace=prod` on a staging task; IF F4, R1, I3);
  - bulk queries (C1).
- **ASI01 and ASI06 containment**: a hijacked or poisoned agent can only take actions the envelope allows. The AP2 "whisper attack" study is the best evidence that this is the right control. Product-description text steered shopping agents into carts that passed every protocol check, reported at 56–90% success [AR], and the proposed defense treats the signed intent as a **capability grant**, checked deterministically (https://arxiv.org/abs/2609.11757, 2026-09-10) [V via STD §10].
- **ASI03**: least privilege per task, not per user. This is the literal Netskope Q10 ask (IF §6.1).
- **WRAP-a**: an action outside the task's capability set or bounds is stopped (Logout is not in a "fill the form" envelope; `merge_pull_request` is not in "summarize").

**What it cannot catch.**
- **Attacks and mistakes that stay inside the envelope.** This is the open attack surface (6) in the only independent adaptive test of this defense class (https://arxiv.org/abs/2606.26479, June 2026) [V via ACA §6].
- **WRAP-b.**
- An envelope that is too broad. Templates drift broad to protect utility.
- Constraints whose values exist only in prose (e.g. "the customer from ticket 4411" when the intent holds no typed id). That needs M1 to carry the field, or a model.
- Utility loss when the envelope is too tight. The research gives the direction, not our numbers: LLM-chosen scopes over-grant by 1.04–2.19× (MiniScope, https://arxiv.org/abs/2512.11147) [AR], and semantic task→scope matching loses recall as tasks widen (ASTRA F1 0.96 → 0.67, https://arxiv.org/abs/2510.26702) [AR]. Both argue for a deterministic floor **plus** an approval path (M8).

**Data it needs.**
- The typed intent (M1).
- Full arguments and `inputSchema`, both in scope at the seam (GG §13(a)).
- A2A skills have **no input schema** (GG §7.4). On A2A hops the envelope can constrain the *skill* and the *target agent*, not the free-text message. That limit is one of the places a model is needed (§7).

**Cost.**
- Runtime: microseconds per hop in Java; parsers are sub-millisecond [J].
- The dominant cost is **authoring**: purposes × capabilities × constraints. Mitigate it with capability *classes* (M5) instead of tool lists, and with offline drafting from observed traffic (M6 data), reviewed by a human.

**Code seam.**
- HopOrchestrator between the registry lookup and `buildFor*` (orchestration/HopOrchestrator.java:306-324 TOOL, :596-613 SKILL, :896-902 PROMPT, :1158-1164 RESOURCE). Consolidate these into one stage.
- The `CustomAttributeProvider` SPI (pdp/service/PolicyContextBuilder.java:24-29, :296-316) must first receive `RequestContext`, the descriptor and the act_chain, and must fail closed. Today it gets 5 inputs and swallows exceptions (GG §13(c)).

### M3. Monotonic down-scoping: child ⊆ parent

**What it is.** It is enforced in two layers, both deterministic:
1. **Delegation-edge scope.** A child hop's capability must be in the set the parent's capability may delegate to. Examples: `advisor.analyze` may call `{market-data.quote, fundamentals.earnings, news.sentiment}`; `market-data.quote` may call only `alphavantage_GLOBAL_QUOTE`. This needs WAAG to **read the inbound OBO `scope`**, which is carried but never read (GG §4.6), plus a per-capability delegation set (admin config or profile rule).
2. **Envelope narrowing.** A child OBO may carry a *refined* intent: extra constraints, a smaller budget, a smaller set, a tighter range. It may never widen. The check is structural: set ⊆ set, max ≤ max, equal targets. That is a small fraction of Progent's Z3 check. Z3 is needed only for a richer constraint language (https://arxiv.org/abs/2504.11703) [V via ACA §4.2]. The rule is the Txn-Token replacement rule: a replacement may narrow and may not widen asserted values (STD §1).
3. **Budgets split, not copied.** A child's call or value budget is reserved out of the parent's remaining budget. The check-and-consume is atomic, IntentCap-style (https://arxiv.org/abs/2609.14631) [V via ACA §4.2].

**What it catches.**
- **ASI08** (strong): a corrupted middle hop cannot hand down more authority than it received. This is the deterministic form of Reva's claim that a hop "cannot outscore its compromised ancestors". Reva publishes no mechanism for that claim (REVA §3).
- **ASI03**: authority cannot exceed its authorizing context. This closes the product's unmet P0 promise "a hop never exceeds its parent" (PB:133, :263, "PLANNED").
- **ASI01 containment** for sub-agents.
- IF F3 (breadth expansion) and F6 (delegation loops or unrequested sub-delegation, together with a depth cap on `actChainDepth`).

**What it cannot catch.**
- A root that was already too broad.
- Misuse inside the inherited envelope.
- Lateral effects between siblings: each stays within its parent, yet together they do something unintended. That needs M4.
- Autonomous chains without an OBO (GG §4.6).
- Parallel siblings racing for a shared budget, unless reservation is atomic.

**Data it needs.**
- Inbound OBO `scope`, `corr_id`, `act_chain` and (from M1) the intent claim. All sit in `rawJwtClaims` (IF §1.2).
- On MCP the OBO is not sent downstream (GG §5.8), but the *calling* agent presents its own inbound OBO to `/mcp`, so the leaf can still read it (IF §3.1 S-H).
- The per-capability delegation set is new config. A2A cards do not declare it.

**Cost.**
- Runtime: sub-millisecond [J].
- Engineering: small to medium. Read two claims, extend the invariants, add config.
- Admin: a delegation graph per agent. It could be bootstrapped from observed edges (M6) and then frozen.

**Code seam.**
- Extend `OboInvariants` (sts/model/OboInvariants.java:29-118), which today checks chain *structure* only (PB:133), with a `scopeNarrowing` hard check.
- Refuse to mint a wider child in `StsService.mint`.
- Expose `context.parentScope` and `context.delegationAllowed` at the HopOrchestrator stage.
- **Fix first:** an `OboIntegrityException` currently escapes unshaped, probably as HTTP 500 with no audit row, because the act_chain is built before the try block (GG §4.5, §6.3).

### M4. Temporal and sequence policies over the chain or session trace (Dogwood-style)

**What it is.** Past-only rules over an event log of `request`, `response` and `error` events. The operator set and pattern catalogue are copied from Dogwood (AWS OSS blog 2026-08-06, https://aws.amazon.com/blogs/opensource/introducing-dogwood-runtime-verification-for-ai-agents/; AgentCore authoring page https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/policy-temporal-authoring.html) [V via DW §3]:
- `formerly` / `previous` / `since` within a mandatory window;
- `count` / `sum` / `count_distinct` within a window;
- "approval happened before", and one-time-use approval (`!X since Approve`);
- mutual exclusion (toxic combinations);
- cool-down;
- block-after-prior-denial;
- **output → input integrity** (anti-fabrication): an argument must equal a value an earlier tool *returned*.

**How it works.** Each rule is lowered to a boolean leaf computed by a Java temporal engine over an in-memory, window-indexed store. Following DW §9.3, it is **partitioned by gateway-owned keys**:
- `trace` (one task tree, from the signed OBO `trace_id`);
- `rootHuman` (`act_chain[0]`, for cross-task budgets that a new trace cannot reset);
- `actor`;
- `tenant`.

**What it catches.**
- **ASI02**: repetition and cumulative value. Examples: the 37th refund, or the cumulative amount above the cap (IF R2, F10). AgentCore's own examples are these rules (DW §3.3).
- **ASI01 containment**: a hijacked agent attempting a burst hits the budget.
- **ASI06, partially**: output → input integrity pins a payee or account to what a **named trusted source** returned.
  - Caution [J]: pin to a specific capability (e.g. `vendor_master.lookup`), never to "any earlier output". Otherwise the poisoned email a tool returned becomes a valid source (IF R5).
- **ASI08**: block-after-N-denials acts as a trace-level circuit breaker, which also gives A2A a kill switch it lacks today (A2AGAP #8).
- **Netskope Q13 toxic combinations**: e.g. a sensitive read followed by an external send in one trace (IF C2), expressed as mutual exclusion or `formerly`.
- **Segregation of duties** keyed by root human + object id across traces (IF R4).
- **WRAP-a** for consequential actions: "destructive X only if an approval event for X occurred in this trace".

**What it cannot catch.**
- Sequences that are individually novel and inside all counters.
- Semantics of any kind.
- History whose response events come from a compromised tool. An `approved: true` output becomes trusted history, so approval events must be written **only by the gateway's approval API** (DW §10.4).
- Temporal policies are not covered by Cedar's automated analysis (DW §3.4). Replay and LOG_ONLY compensate.

**Data it needs.**
- A **synchronous, in-memory append at decision time**. Today the ledgers are async and lossy, and nothing reads them inline (GG §9.2, §13(f)).
- **Trace continuity on MCP.** MCP mints a new trace per HTTP request unless one is propagated (GG App. A). The trace has to be carried from the inbound OBO or header.
- **Response events need structured output.** MCP `structuredContent` and `isError` are dropped today (GG §14 #15). Without them, output → input integrity and error-based rules cannot be built.
- **A parent link.** Read `corr_id`.

**Cost.**
- Runtime: a per-trace history of tens to hundreds of events, evaluated with window-indexed queries, should cost well under 1 ms [J, DW §4.3]. **[OQ] Unmeasured.** Neither Dogwood nor AgentCore publishes latency; there is only a `TemporalLatency` metric (DW §4.3).
- Concurrency: the advisor fans out in parallel (GG §4.6), so budget rules need per-partition atomic append-then-evaluate (reserve). AgentCore sidesteps this by allowing only one concurrent authorization per session (DW §4.3), and WAAG cannot copy that without serializing nested A2A.
- Memory: bounded by a 24 h window, the same cap as Dogwood and AgentCore.
- Multi-instance: route sticky by `trace_id`, or move the hot store to Redis. Whether WAAG runs as more than one instance is open (GG §15 Q2).
- Engineering: medium to large: a Java engine for the documented subset, a Dogwood-text parser that rejects anything outside the subset instead of dropping it, and replay tooling.

**Code seam.**
- Append the request event and evaluate leaves at the HopOrchestrator stage.
- Append response and error events right after dispatch, synchronously, in-process, **not** on the shared `auditExecutor` (GG §13(d)).
- `InFlightRequestRegistry` is a partial precursor (orchestration/InFlightRequestRegistry.java:18-149). It has no traceId field and no getter, and it registers only after the PDP.
- Durability: a write-behind to Postgres. The PDP must never read the ledger as authority (TT §5.4 L2 vs L3).

### M5. Tool annotations, effect classes and argument constraints

**What it is.**
1. **Store MCP `ToolAnnotations`** at registration. They are dropped today (GG §7.4). The fields are `readOnlyHint`, `destructiveHint` (default true), `idempotentHint` and `openWorldHint` (default true). The spec says clients must treat them as untrusted unless the server is trusted (https://modelcontextprotocol.io/specification/2026-07-28/server/tools) [V via STD §8].
2. **WAAG-owned labels per capability, admin-attested**, with annotations as hints only (HCP's "metadata non-authority", https://arxiv.org/abs/2606.29073) [V via ACA §4.4]:
   - effect class: `read | write | destructive | external-egress | privilege-change | security-control | session-ending`;
   - data sensitivity tier;
   - `ingestsUntrusted` (web, email, news, third-party agent replies).

   A2A skills get labels by admin only, since they have no annotations or schema.
3. **Argument-level checks that need no task knowledge**:
   - **self-reference**: the grantee equals the acting agent's `workload_id` or the root human (IF G3, H3, I2; Netskope Q11);
   - **org boundary**: the destination owner or domain is outside the allow-list (G5, C2);
   - **verb parsers** (SQL DDL/DML, shell `rm`, `scale replicas=0`).
4. **Description pinning.** Hash the description and schema at approval, and treat a change as a re-approval event. This covers the rug-pull case (IF I5): downstream descriptions are unversioned and republished verbatim today (GG §7.4).

**What it catches.**
- **ASI02** (strong): purpose `read-only` × effect `write/destructive` → deny or approve. Microsoft's Task Adherence examples (a read request that becomes `change_data_plan()`, a draft that becomes `send_email()`) are exactly an effect-class mismatch (VL §3.1). Zenity's read/mutative taints and Permit's read/write/destructive trust levels productize the same idea (VL §3.16, §3.18).
- **ASI03**: self-escalation and privilege-change classes.
- **WRAP-a**: this is the **most direct no-model answer to the commenter**.
  - A Logout is `session-ending`. A permission check sees only the permission. An effect-class × purpose rule sees the *consequence*, and routes a `session-ending` or `destructive` action outside a matching purpose to REQUIRE_APPROVAL.
  - It would not have prevented the click in his harness, but it would have turned a silent error into a question.
  - The analogues on our catalogue are G1, G6, I1, R3.

**What it cannot catch.**
- Generic tools whose effect depends on free-text arguments (`execute_sql`, `http_request`, `run_shell`) beyond what a parser can see.
- Mislabelled capabilities, especially if untrusted servers' annotations are believed.
- **WRAP-b** (same effect class).
- Encoded or obfuscated arguments.

**Data it needs.**
- Registrar change (annotations).
- A label table.
- The descriptor, which is in scope at the seam.
- The act_chain actor `workload_id` for self-reference.
- The root human → HR employee id mapping for H3 (new).

**Cost.**
- Runtime: negligible.
- Labelling effort: one label per capability, not per task. Bootstrap from annotations and name-verb heuristics (`delete_`, `update_`, `create_`, `send_`), then have an admin review. An LLM may *suggest* labels at authoring time.

**Code seam.**
- `McpCapabilityRegistrar` (protocol/mcp/capability/service/McpCapabilityRegistrar.java:168-264) and `CapabilityDescriptor` (capabilityRegistry/model/CapabilityDescriptor.java:12-57).
- New `resource.effect*` attributes in `PolicyContextBuilder`.
- A per-effect-class disposition (allow / approve / deny) on capability profile rules. This is the "evaluate" tier from S02, but routed to approval instead of a model.

### M6. Per-agent statistical baselines (novel tool, volume, time, fan-out)

**What it is.** Offline or near-line jobs over `gateway_audit_log` compute compact profiles. The inline check is an O(1) lookup of an in-memory snapshot. The outputs are risk leaves that can only raise friction.

Profiles:
- per agent, and per (agent, capability), (root human, agent) and (parent capability → child capability);
- **first-seen sets**: a novel capability for this agent, a novel delegation edge, a novel target value in a categorical argument (a new repo owner, a new payee, a new domain);
- **volume quantiles**: calls per trace, calls per window, cumulative value per trace, using robust thresholds such as p99 or median + k·MAD [J];
- **fan-out and depth**: distinct children per parent `corr_id`, maximum `actChainDepth`;
- **time**: this agent's hour-of-week histogram (the gateway clock is UTC, per team memory);
- **deny and error rates.**

**Next-capability model.** An n-gram / Markov table: is capability X a seen successor of the last N capabilities in this trace? This is a model-free version of Praetor's pDFA. Praetor itself reports 2.2 ms p50 and 3.1 ms p95 at a sidecar, and 0% vs 35% multi-step exfiltration against a stateless scanner [AR] (https://arxiv.org/abs/2604.26274, Apr 2026; ACA §4.6). **Praetor's string bounds use a MiniLM embedding centroid.** That part is a model, so the no-model version keeps only exact-match whitelists and numeric or categorical bounds.

**What it catches.**
- Symptoms of **ASI01** (a hijacked agent doing something it never does: new tool, new target, burst).
- **ASI02** volume abuse (F10, R2, C1).
- **ASI03** usage spikes.
- **ASI08** fan-out explosions and loops (F3, F6).
- It also makes the Netskope Q15 answer true: the six risk signals that exist only in admin analytics today (GG §7.2; IF §6.1) would reach the decision.

**What it cannot catch.**
- **In-distribution attacks.** Even a learned graph model (Skynet) reports only 74% recall on indirect prompt injection, because it stays near the benign manifold [AR] (https://arxiv.org/abs/2609.06835; ACA §4.6).
- **Cold start**: new agents, new tools, tool churn. Praetor reports 24% benign failure after 20% tool replacement until analysts update it [AR].
- **Thin data.** WAAG's measured corpus is 128 hops in 26 journeys (GG §13(d)). Praetor used 500–5,000 traces. **[OQ]** Real customer volume per workflow is unknown.
- **Baseline poisoning** during profiling.
- **Systematic mistakes.** A mistake that recurs becomes the baseline. The commenter's Logout fired in 39% of runs (S02), so a baseline trained on that agent would learn "Logout is normal". **Baselines catch rare deviations, not common errors.** This is the strongest reason M6 cannot stand in for M1+M5 on WRAP.

**Data it needs.**
- The audit ledger. It is async and drops rows when the queue is full (GG §9.2), and it has single-column indexes only (GG §9.1). Profiling needs composite indexes and complete decision rows (TT §5.2).
- A2A agents resolved by name.
- The parent link (`corr_id`) for edge profiles.

**Cost.**
- Runtime: sub-millisecond lookups [J].
- Avoid AGENT_FIELD attributes on the hot path: each is a DB query (GG §6.7).
- The real costs are false-positive handling, threshold tuning and analyst time. Run LOG_ONLY first and calibrate, following the method AWS gives for Guardrails thresholds (DW §5.2).

**Code seam.**
- A new `BaselineService` (offline jobs + a snapshot cache).
- A lookup at the HopOrchestrator stage.
- Promote the existing 6-signal scorer (agentRegistry/service/HumanUserService.java:287-498) from admin-only to snapshot-backed, and key it by agent and trace, not session.

### M7. Reusing egress-classifier sensitivity: taint and the Rule of Two

**What it is.** Two per-trace labels, joined upward and never downward, plus one request-side check.
1. **Confidentiality taint**: the maximum sensitivity of data that has flowed back to agents in this trace. It comes from `EgressClassifier` categories (PII/PCI/SECRET, up to RESTRICTED) (GG §8.2).
2. **Integrity taint**: the trace has ingested untrusted content (a capability labelled `ingestsUntrusted` by M5), or a response set `injection_detected`.
3. **Request-side DLP** [J, not in the dossiers]: run the same classifier on **outbound arguments** (A2A `input`, MCP string args such as an email body or HTTP payload). For exfiltration, the tool call's *arguments* are the egress, and today the classifier sees only responses (GG §8.1).

**Policy.**
- **Meta's "Agents Rule of Two"**: in one session, at most two of {untrusted input, sensitive data, state change or external communication}; all three need supervision (https://ai.meta.com/blog/practical-ai-agent-security/, 2025-10-31) [V via ACA §4.1]. C1's "lethal trifecta" trigger is the same rule (VL §3.20).
- **"After a RESTRICTED read, external egress requires approval."** This is AWS's healthcare pattern (DW §11).

**What it catches.**
- **ASI06**: poisoned content cannot drive a consequential action without step-up.
- **ASI01**: injection-driven exfiltration.
- **ASI02**: exfiltration through legitimate tools.
- **ASI08**: taint propagates to every descendant in the trace, so no child is "cleaner" than its ancestors. This is again a deterministic version of Reva's unpublished chain-score claim.
- IF C2, R5, G4, G5, F7 (the consequence, not the attribution).

**What it cannot catch.**
- Data or instructions the agent obtained **outside** WAAG, e.g. browsing directly. Those produce no taint.
- Paraphrased, summarized or encoded exfiltration. The recognizers are regex, checksum and entropy (GG §8.2).
- Coarseness: one untrusted read taints the whole trace. Utility falls unless in-envelope actions stay allowed (ACA §7 idea 2).
- The injection regex is English-only and trips on benign phrases (GG §8.2). Treat it as a hint.

**Data it needs.**
- The trace id on `EgressContext` (exists, GG §8.1).
- The `provenance_categories` column (reserved, never written, GG §8.4).
- `ingestsUntrusted` labels (M5).
- **Timing**: classification is async today (p50 5 ms, p95 42 ms, max 74 ms, n=12) on a pool that drops tasks (GG §13(d)). For the taint to bind the *next* hop reliably, classify **synchronously on the response path** before returning to the agent, or mark the trace `PENDING` and treat pending as tainted for consequential capabilities.

**Cost.**
- Sync classification adds p50 ~5 ms per response [measured async, GG §13(d)], which is small next to seconds of downstream time.
- The classifier has **no size cap and no regex timeout** (GG §8.1). Both must be added before it goes inline.
- Engineering: small to medium.

**Code seam.**
- `HopOrchestrator.fireEgress` (orchestration/HopOrchestrator.java:144-182) → write the taint into the same per-trace store as M4.
- `EgressClassifier.classify` (postprocessor/classifier/EgressClassifier.java:40-132) reused request-side at the HopOrchestrator stage.
- The `Recognizer` SPI (postprocessor/classifier/Recognizer.java:5-17) for new deterministic recognizers.

### M8. Step-up and human approval as a REQUIRE_APPROVAL obligation

**What it is.**
- **A third PDP outcome.** Two options:
  - Cedar annotations such as `@advice("REQUIRE_APPROVAL")` on the determining policy (cedar-java supports annotations since 4.3.0; DW §8, §9.1);
  - an AuthZEN-shaped decision `context` carrying obligations. AuthZEN Authorization API 1.0 is an OpenID Final Specification (https://openid.net/authorization-api-1-0-final-specification-approved/, Jan 2026) [V via STD §7].
- **Channels**, all standard:
  - A2A `TASK_STATE_AUTH_REQUIRED` / `INPUT_REQUIRED`, non-terminal and resumable (https://a2a-protocol.org/latest/specification/) [V via STD §9];
  - MCP 2026-07-28 URL-mode elicitation through MRTR, or the SEP-2848 async-approval pattern. SEP-2848 records an immutable call binding (tool, canonical-args digest, principal, expiry) and re-evaluates at execution (https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2848, open draft) [V via STD §8];
  - CIBA with a `binding_message` (https://openid.net/specs/openid-client-initiated-backchannel-authentication-core-1_0.html).
- **How the approval is recorded.** The gateway's approval API writes an `Approve` event into the M4 trace store. Rules consume it with `formerly` or one-time `!X since Approve` (DW §10.2). It is an **exact-action** approval (action digest, policy digest, nonce, expiry), modelled on TealTiger's Approval contract (TT §5.1). It is never taken from tool output (DW §10.4).
- **What the approver sees.** The *typed* intent and action, not agent prose. That is the ASI09 lesson (STD §12).

**What it catches.**
- It is the **pressure valve that makes M1–M7 deployable**. Every uncertain or borderline signal (baseline novelty, taint, envelope edge) becomes a question instead of a false deny.
- **WRAP**: the only complete gateway answer to "we cannot tell if this consequential action is right" is to ask. Covers IF G6, R3, I1 and the Logout-class case.
- ASI01, 02 and 06 residual risk; ASI08 by pausing the chain.

**What it cannot catch.**
- **Approval fatigue.** In MiniScope's simulation users confirmed 18–60% of the time; in Progent, 6% of policy updates needed approval [AR] (ACA §4.2).
- Human deception (ASI09).
- Autonomous chains with no human to ask. Route to an owner or approver group instead.

**Data it needs.**
- A result-type change.
- An approval store and API.
- Approver identity.
- A canonical args digest (`params_hash`).
- A suspend/resume path.

**Cost.** This is **the largest engineering item** in the no-model stack.
- Every hop blocks a thread today. An A2A hop holds a Tomcat worker for its whole subtree, and about 33 concurrent journeys would exhaust 200 workers (GG §13(d), inferred). An approval that takes minutes cannot be held on-thread. It needs a non-terminal task state and a resume, not a parked thread.
- UX and approver routing are product work.

**Code seam.**
- `PolicyEvaluationResult` (pdp/dto/PolicyEvaluationResult.java:49-57) gains an obligation.
- The HopOrchestrator deny mapping (:349-355) gains an approval branch.
- `A2aMessageMapper.toTask` (:72-87) emits `AUTH_REQUIRED`.
- A new `ApprovalService`.

---

## 5. Cost and dependency summary

| # | Mechanism | Added p50 per hop [J unless marked] | Engineering | Ongoing cost | Hard dependencies |
|---|---|---|---|---|---|
| M1 | Declared intent in OBO | <1 ms (hash + copy) | S (STS) + M–L (front door) | Purpose catalogue; consent UX | Front-door field; purpose source not LLM-chosen |
| M2 | Envelopes | <1 ms | M | **Authoring per purpose** | M1; stage consolidation; SPI fixes; (better) real Cedar |
| M3 | Down-scoping | <1 ms | S–M | Delegation graphs | Read `scope`/`corr_id`; shaped `OboIntegrityException` |
| M4 | Temporal / sequence | <1 ms in-memory, **unmeasured** | M–L | Rule authoring, replay | Sync per-trace store; MCP trace continuity; `structuredContent`/`isError`; atomic reserve for fan-out |
| M5 | Effect labels + arg checks | ~0 | S | Labelling per capability | Registrar keeps annotations; label table |
| M6 | Baselines | <1 ms lookup | M | **FP triage, drift upkeep** | Complete decision rows; composite indexes; traffic volume |
| M7 | Taint / Rule of Two | +~5 ms p50 per response (sync classify; measured async p50 5 / p95 42 ms, GG §13(d)) | S–M | Recognizer tuning | Size cap + timeout; M5 `ingestsUntrusted`; M4 store |
| M8 | REQUIRE_APPROVAL | 0 on the fast path; seconds–minutes when triggered | **L** | Approver load | PDP third outcome; async suspend/resume |

Total fast-path overhead is plausibly **single-digit milliseconds** on top of ~12–13 ms, against 1.4–6.9 s downstream [J]. That is consistent with the SM §1 arithmetic. **The binding costs are authoring, labelling, approval UX and the threading model, not latency.**

---

## 6. How far it goes

### 6.1 Coverage by risk

Legend: ● strong; ◐ partial (containment or subset); ○ none.

| Mechanism | ASI01 goal hijack | ASI02 tool misuse | ASI03 privilege abuse | ASI06 context poisoning | ASI08 cascading | WRAP-a | WRAP-b |
|---|---|---|---|---|---|---|---|
| M1 declared intent | ◐ immutable anchor | ○ (reference only) | ◐ `exp`/`cnf` | ○ | ◐ root reference | ◐ defines "the task" | ○ |
| M2 envelopes | ◐ containment | ● | ● | ◐ containment | ◐ | ● if outside envelope | ○ |
| M3 down-scoping | ◐ | ◐ | ● | ○ | ● | ○ | ○ |
| M4 temporal | ◐ budgets | ● | ◐ | ◐ output→input integrity | ● breaker | ◐ approval-before | ○ |
| M5 effect labels | ◐ | ● | ● self-escalation | ○ | ○ | ● consequential class | ○ |
| M6 baselines | ◐ symptoms | ◐ volume | ◐ | ○ | ◐ fan-out | ○ (learns recurring errors) | ○ |
| M7 taint / Rule of Two | ◐ exfil gate | ◐ | ○ | ● gate consequential actions | ● taint propagates | ○ | ○ |
| M8 approval | ◐ residual | ◐ residual | ○ | ◐ residual | ◐ pause chain | ● ask instead of guess | ◐ only if the class forces approval |
| **Stack** | **Containment ●, detection ○** | **●** | **●** | **◐ (consequence only)** | **●** | **●** | **○** |

The stack never *detects* goal hijack or poisoning. It detects neither the injected sentence nor the drifted plan. What it guarantees, deterministically, is that the damage is bounded by the declared purpose, the envelope, the budget and the Rule of Two. That is the posture the research favours: detectors and LLM judges fall to 50–100% attack success under adaptive attack (https://arxiv.org/abs/2510.09023; https://arxiv.org/abs/2503.00061) [V via ACA §6], while out-of-band deterministic enforcement held in the one adaptive probe so far (ACA §6). It is also the posture NIST AI 100-2 E2025 recommends: design as if injection will succeed (STD §12).

### 6.2 Recount of the 33 internal-fit decisions (IF §7) with the no-model stack

Legend:
- **D**: enforceable at the action with no model.
- **D\***: deterministic only if M1 carries the typed field; otherwise it needs extraction from prose (a model).
- **→H**: approval is the right outcome.

| # | Mechanisms | Verdict | Note |
|---|---|---|---|
| F1 console asks advisor to trade | M1 purpose `research.readonly` + M2/M5 on the trade tool | **D** at action | Judging the *text* would need a model; blocking the resulting order does not |
| F2 MSFT vs AAPL | M1 target + M2 | **D\*** | A ticker regex is a deterministic fallback |
| F3 20 tickers | M3 budget, M2 entity count, M6 fan-out | **D** | |
| F4 `outputsize=full` | M2 arg constraint | **D** | |
| F5 BALANCE_SHEET for "earnings" | M2 sub-purpose capability set | **D\*** | Needs the sub-purpose declared as a code |
| F6 delegation loop | M3 edges + depth, M4 | **D** | |
| F7 injected headline → fan-out | M7 integrity taint, M4 budget, M6 fan-out | **D** (consequence) | *Attribution* by paraphrase needs a model |
| F8 autonomous rebalance | M1 NHI mission, M5 | **D** | Needs NHI roots (A2AGAP #7) |
| F9 GitHub tool in finance trace | M2 domain | **D** | |
| F10 40 quotes | M4 / M6 | **D** | |
| R1 refund amount/payment | M1 target + cap, M4 output→input | **D\*** | |
| R2 37 refunds | M4 count/sum | **D** | |
| R3 payout change in read task | M5 × purpose | **D** →H | |
| R4 maker = checker | M4 SoD keyed by root human + object | **D** | |
| R5 payee from email | M7 + M4 pinned to a trusted source | **D** | |
| G1 merge in summarize | M5 × purpose | **D** | |
| G2 workflow file in typo fix | M1 path pattern + M2 | **D\*** | Deriving the path pattern from prose needs a model |
| G3 self-collaborator | M5 self-reference | **D** | |
| G4 secret-hunting query | Request-side recognizers (M7) + M5 | **D** | Keyword-grade only |
| G5 push to external fork | M5 org boundary + M7 | **D** | |
| G6 disable branch protection | M5 `security-control` | **D** →H | |
| C1 SOQL without WHERE/LIMIT | M2 parser | **D** | |
| C2 PII read then external send | M7 + M4 mutex | **D** | |
| C3 wrong customer id | M1 target + M2 | **D\*** | |
| C4 home addresses for "marketing" | Purpose code × data-category matrix | **D\*** | Deterministic if the purpose is a code; a model if prose |
| H1 comp read for a policy question | Purpose code × category | **D\*** | |
| H2 medical-leave list for recruiting | Agent registered purpose × category | **D** | Needs a purpose column on agents (GG §7.1) |
| H3 self-approval | M5 self-reference | **D** | Needs a root → employee id map |
| I1 scale to 0 in diagnosis | M5 × purpose | **D** →H | |
| I2 attach admin policy to own role | M5 | **D** | |
| I3 prod vs staging | M1 env + M2 | **D\*** | |
| I4 DELETE in reporting | M2 SQL verb | **D** | |
| I5 description drift | M5 hash pin | **D** | |

**Totals: 25 D, 8 D\*, 0 that need a model at enforcement time.**

- **Honest reading.** This does **not** say the no-model stack *catches* 25 of 33 attacks. It says each of the 25 has a deterministic rule that would catch it *if an admin authored the rule and the envelope is tight enough*. Every D is also bypassable by an action that stays inside the envelope.
- **Where the semantic question moved.** The 8 D\* rows show it moved from *enforcement* to *capture*: someone has to state the target, environment, path or purpose as a typed field.

---

## 7. Which decisions require a semantic model, and why

All of them share one root cause. **The input is unstructured language, and the deterministic machinery needs typed facts.** A model is the only thing that can map "take care of that duplicate payment from yesterday" to `{action: refund, target: pay_X, reason: duplicate}`. That is the Jev CEO's own example (S03 §B).

| # | Decision | Why deterministic mechanisms cannot make it | What the model does, and its limit | Where it would run on WAAG |
|---|---|---|---|---|
| R-1 | **Prose → typed intent at the root** when the front door has no structured purpose | Templates and pickers need a human or app to choose; free text cannot be enumerated | *Proposes* the typed intent + confidence; low confidence → confirm or clarify (IGAC review modes, ACA §4.2). Precision is the measured bottleneck: ASTRA recall falls to 0.57 at 3 scopes [AR] | Only where the human's words are visible. **They are not visible on WAAG's console path** (GG §12.4). Needs a console change or a platform hook such as Copilot Studio's `analyze-tool-execution` (VL §3.1, W5) |
| R-2 | **Is this free-text A2A message on-task, or carrying injected instructions?** | A2A skills have no input schema (GG §7.4), so the text cannot be projected onto typed fields. Regex injection detection is English-only and brittle (GG §8.2) | Advisory score → tighten only. The text is written by an upstream LLM that may already be compromised, so the model reads attacker-shaped input (SM §1; JEV §8.5: fake pre-approval text moved a Jev block probability from 0.76 to 0.48) | A2A hops only; async first. A deterministic alternative exists if skills publish typed inputs: the "Language Converter" firewall (https://arxiv.org/abs/2502.01822, ACA §4.5) |
| R-3 | **Target or entity extraction from prose** ("the staging cluster", "John", "the customer on this ticket") | Regex handles well-formed ids (tickers, `pi_…`, `#4411`), not names or descriptions | Typed extraction; mismatch → approval | Root capture (once per task), not per hop |
| R-4 | **Purpose compatibility when purpose is prose** (IF C4, H1) | The purpose × data-category matrix needs a purpose *code* | Classify prose to a purpose code, then the matrix decides | Root capture |
| R-5 | **Semantic novelty in free-text arguments** (string bounds that survive synonyms) | Exact-match whitelists are brittle; Praetor's embedding bounds are a model, and synonym substitution still evades them 18% of the time [AR] | Embedding distance as a risk signal | Near-line |
| R-6 | **Provenance by paraphrase**: did this instruction come from a tool output? (IF F7) | Substring overlap is deterministic, but a paraphrase defeats it | Similarity between earlier responses and the new request text | Near-line; the consequence is already gated by M7 |

**Why the model must stay a signal, never the authority.**
- Content-level judges fall under adaptive attack (ACA §6).
- Small encoders on CPU cost 193–580 ms per question (Laya, author-measured; JEV §5.6) against a ~12 ms budget.
- Zero-shot accuracy is near chance on typed decisions (JEV exec 5).
- The model reads text that downstream agents write.

That is why every serious design (Reva's PEPs, AWS Guardrails as providers, IGAC, the Jev CEO's framing) folds model output into a deterministic policy that can only narrow (REVA §5.2; DW §5.2; S03 §B).

**Model-dependent decisions are concentrated at capture, not at every hop.** If a model is used, it runs **once per task at the trusted root** to fill M1's typed fields. After that, every hop is deterministic. Per-hop model cost becomes per-task cost, and the model never reads downstream, lower-integrity text. The exception is R-2, which is advisory.

**Decisions no gateway component can make, model or not:**
- **WRAP-b.** The commenter's Logout, and its tool-call analogues, when the action is inside the envelope and of the same effect class. Whether it "advances the task" depends on state the gateway never sees: the page, the element order, what the agent believes. Even a gateway-side LLM would lack that context. AlignmentCheck needs the agent's reasoning trace, which a gateway does not have (ACA §4.3).
- **Text-to-text harm**, e.g. a misleading summary returned to the human (CaMeL lists it as out of scope; ACA §4.1).
- **Anything the agent reads or does outside the gateway.**

The best gateway responses are:
1. force approval on consequential effect classes (M5 + M8);
2. **post-hoc outcome verification**: compare the claimed result with the real end state, as τ-bench does and as the commenter already does (ACA §5, §10);
3. front-door hooks, where the agent platform can see what WAAG cannot.

---

## 8. Recommendation (architect + product)

### 8.1 Build order
1. **Stage 0, credibility floor.** Fix the silent widening in the PDP, or move to real Cedar via `cedar-java:4.10.0` with the `uber` classifier (DW §8, §10.2). Re-enable the lineage guardrails. Close the `/a2a` status and `jti` gates (A2AGAP #1–#5). Reserve an attribute namespace. Resolve the 2000-char truncation mismatch.
2. **Stage A, cheap and deterministic, weeks [J]:**
   - M5 labels + stored annotations + description pinning;
   - M3 read `scope`/`corr_id` + edge allow-sets + a depth cap;
   - M8 minimal: obligation type + A2A `AUTH_REQUIRED`;
   - persist `parent_correlation_id` and non-droppable decision rows (TT §7).
3. **Stage B, stateful:** M4 per-trace store with the Dogwood-subset engine (counts, sums, formerly, mutex, approval-before) in LOG_ONLY first. Then M7 sync taint + request-side DLP. Then trace-replay "what-if" against the ledgers.
4. **Stage C, declared intent:** M1 app-bound purposes first (zero UX), then purpose templates and a typed confirmation card. M2 envelopes per purpose.
5. **Stage D, statistical:** M6 baselines once traffic volume exists, LOG_ONLY until calibrated.
6. Only then consider the model path (sibling analysis), scoped to R-1 at the root and R-2 as advisory on A2A.

### 8.2 What to say to buyers
- **Claim:** "Purpose-bound, behavior-aware authorization. The human-approved purpose is signed into every hop and enforced deterministically; history, budgets and sensitive-data rules apply per task; anything consequential outside the purpose goes to a human."
- **Don't claim:** "intent understanding", "detects goal hijack" or "blocks prompt injection". The no-model stack does none of these; it bounds their consequences.
- This is also the honest Netskope Q15 and Zscaler Q6 answer (IF §6), and it is the positioning C1 uses: C1 deliberately refuses inferred intent and sells governed scope (VL §3.20, §6).

### 8.3 Differentiation this buys
The no-model stack is where WAAG's existing assets matter most:
- the signed human-rooted chain (vs Reva's caller-supplied `traceparent`, REVA §9.2);
- gateway-owned trace keys (vs AgentCore's caller-chosen session id, which AWS itself says a new session can reset, DW §5.4);
- in-process latency (vs Reva's own measured 2.6–3.0 s with the inline judge, REVA exec 7) [V-code per REVA].

No vendor verifiably combines heterogeneous MCP + A2A multi-hop, a verified human root, per-hop down-scoped tokens, trace-scoped history and graduated outcomes (VL exec 8).

---

## 9. Verified facts vs claims used here

| Item | Status |
|---|---|
| WAAG pipeline facts, seams, latencies | Verified in code (GG); latencies from 128 demo hops, single user (GG §13(d)) |
| Dogwood operators, lowering design, AgentCore quotas and session caveat | [V] AWS docs and repo (DW §2–5) |
| Dogwood / AgentCore performance | **Not published** (DW §4.3) |
| MCP annotations untrusted-by-default; `_meta` prefix rules | [V] MCP 2026-07-28 (STD §8) |
| Txn-Token `scope`/`tctx` naming in -11 | [V-digest] (STD §1) |
| Progent, Praetor, Skynet, IGAC, IntentCap, MiniScope, ASTRA numbers | [AR], mostly synthetic benchmarks, no independent reproduction except one adaptive probe of Progent (ACA §6, §11 Q6) |
| Whisper-attack 56–90% | [AR] arXiv preprint (STD §16) |
| Reva p90 < 40 ms, 98% drift accuracy, "patent-pending" | [VC]; contradicted by Reva's own repo timings (REVA §7) |
| All sub-millisecond per-mechanism estimates in §5 | [J], **unmeasured** |
| 25 D / 8 D\* recount | [J], a paper exercise on IF §7 |

---

## 10. Open questions

1. **Front door.** Can the console (and Kore.ai / claude-desktop) send a purpose code or a typed intent card at hop 1? Which envelope carries it: an A2A extension, MCP `_meta` or a console API (IF §8 Q1)? Without it, M1 falls back to app-bound purposes only.
2. **Purpose granularity.** How many purposes per tenant are realistic before authoring cost dominates? Can envelopes be bootstrapped from observed traffic (M6 data) and frozen after review?
3. **Single or multi-instance.** This decides between an in-memory per-trace store with sticky routing and Redis (GG §15 Q2).
4. **Temporal engine latency** under concurrent fan-out, with atomic budget reservation. Unmeasured (DW §12 Q2/Q3).
5. **Approval UX.** What approval rate is tolerable per purpose before fatigue? Is a WAAG-hosted approval page acceptable as the "verifiable grant" that WIMSE AIMS asks for (STD §17 Q6)?
6. **Label trust.** Which servers are "trusted" for annotations, and who attests labels for A2A skills?
7. **Baseline volume.** Is there enough benign OBO traffic per workflow for M6 (Praetor used 500–5,000 traces), and how is drift handled on tool churn?
8. **Request-side DLP.** What false-positive rate does the regex classifier have on outbound arguments? It has only ever run on responses.
9. **Autonomous chains.** What is the NHI-rooted equivalent of M1 when no human exists to declare or approve (A2AGAP #7; team memory on autonomous NHI gap)?
10. **Evaluation.** Wrap AgentDojo / MSB suites as MCP servers behind WAAG and measure each stage's ASR, benign utility, p50/p95 and thread occupancy, including adaptive in-envelope attacks (ACA §10). None of the 25/8 figures above means anything to a buyer until this is done.
