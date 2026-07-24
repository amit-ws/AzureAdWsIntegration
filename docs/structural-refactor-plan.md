# Structural Refactor Plan — "agentic gateway", not "MCP gateway with a rename"

**Goal:** make the code read like an agentic gateway designed for multiple protocols from the ground up —
spine (central services) + pluggable protocol adapters — without breaking a working system.

**Source:** a 5-dimension audit (naming, package placement, schema, public surface, dead code) produced
**130 findings**; **35 are already correct** (`MCP_SPECIFIC_OK` 17 + `NEUTRAL_OK` 18) and are explicitly left
alone. The **95 actionable** items were each adversarially checked for what would actually break.

---

## Target architecture (the owner's model, adopted)

```
wsAgenticSecurityGateway/
├── orchestration/      SPINE — HopOrchestrator, Hop, RequestContext, CapabilityType
├── sts/ pdp/ audit/ agentRegistry/ capabilityRegistry/   SPINE — governance (protocol-neutral)
├── security/           SPINE — auth wiring (today trapped inside wsServer)
├── admin/              SPINE — admin/dashboard APIs
├── common/             SPINE — context, crypto, listeners
└── protocol/
    ├── spi/            ProtocolAdapter + neutral CapabilityResult  ← the swap point
    ├── mcp/            inbound/ outbound/ transport/ session/      ← all of today's wsServer + wsClient
    └── a2a/            slots in later, symmetric with mcp/
```

**Rule of thumb used throughout:** if it would still exist in a gateway that had *never heard of MCP*, it
belongs in the spine. If it only exists because MCP exists, it belongs under `protocol/mcp/`.

---

## Decisions locked by the owner (do NOT change these)

1. **Admin API auth stays permissive here.** This code is ported into `ws-backend`, a production app that
   already requires admin login. Flipping deny-by-default in this repo would only break the dev dashboard.
2. **The Playground tool-call bypass stays.** `POST /api/mcp/servers/{server}/tools/{tool}` skips the
   governed path (no PDP/STS/act_chain) by design, as an admin affordance. Note it *is* audited
   (`auditClientToolInvocation`), so it is not invisible — it is unevaluated, not unrecorded.
   → Therefore `McpClientController` is **NOT deleted**; the dashboard consumes it
   (`api.js:65-69`: servers, tools, resources, prompts, callTool).

---

## Safety protocol (this is the point of the document)

Refactoring a working system is riskier than greenfield. Containment rules:

- **Compiler is the proof.** Java package moves and renames either compile or they don't. ~80% of the work
  is in that class — there is no silent breakage path.
- **One risk band at a time. One reviewable commit at a time.** Each commit: compiles + 49 tests green +
  owner live-tests. The owner commits manually; nothing lands unreviewed.
- **Never mix a move with a behaviour change in the same commit.** If something breaks, `git bisect` must
  point at a real cause, not a 60-file move.
- **Stop-anywhere property.** After every step the gateway is fully working. Abandoning mid-plan always
  leaves the codebase better than it started, never half-migrated.
- **Band 0 first.** The current 49 tests are thin for this, and there is no end-to-end test of the governed
  request path. Characterization tests come *before* anything moves.

### Risk classes

| Band | Work | Risk | What proves it correct |
|------|------|------|------------------------|
| 0 | Add characterization tests | none (pure addition) | new tests pass against current behaviour |
| 1 | Delete verified-dead code | **zero** | repo-wide grep + compiler + tests |
| 2 | Rename inside the spine | very low | compiler |
| 3 | Package moves | very low | compiler |
| 4 | Seam / behavioural change | **real** | Band 0 tests + live test |
| 5 | Data & contract changes | **real** | DB migration + coordinated dashboard change |

---

## Band 0 — Safety net (do first, breaks nothing)

Pin current behaviour of exactly the paths later bands touch:

- End-to-end governed tool call: identity → capability filter → PDP → STS mint → adapter dispatch → audit.
- Same for prompts and resources (the two paths with the least coverage today).
- Stateless request path (synthetic exchange → same governed flow).
- Governance refusals: PENDING / BLOCKED / DEPROVISIONED agent.
- `HttpMcpAuditFilter` admission decisions.

These tests must pass unchanged after every later band. They are the contract.

---

## Band 1 — Deletions (zero risk; 25 findings)

Every item below was verified unreferenced by repo-wide search.

- **3 committed zero-byte files:** `Gateway.java`, `wsServer/McpServerConfig.java`, `wsServer/McpServerRunner.java`.
- **Dead file-config island:** `McpConfigLoader` + `McpConfigFile` + `ConfigVariableResolver`, plus the
  `mcp.config.*` block in `application.yml`. Superseded by DB-backed configs (`McpClientInitializer` →
  `serverConfigService.getStartupConfigs()`). *Side effect: retires `mcp_config.json` and its plaintext creds.*
- **`McpAuditException`** — never thrown or caught anywhere — with its only consumer, the dead `auditError` method.
- **`SchedulingConfig`** — duplicate `@EnableScheduling` (already on the root application class).
- **`AgentCapabilityProfileRuleRepository`** — never injected; rules are owned by the profile aggregate.
- **~27 never-called public methods**, including:
  - the **three divergent `isAgentBlocked*` variants** — they disagree on whether `PENDING` means blocked.
    This is a latent bug removed by deletion; the exception-throwing enforcement path stays the single answer.
  - session-keyed identity twins whose subject-keyed counterparts are the live ones.
  - 8 unused `McpAuditService` methods.
- **Duplicate `CapabilityType` enum** — two identical enums, no conversion between them. Collapse to one.
- **Stale `logging.level` keys** pointing at the pre-refactor package `com.ws.wsAgenticSecurity.*`.

**Not deleted:** `McpClientController` (owner decision #2).

---

## Band 2 — Spine renames (compiler-verified; no DB, no API, no config)

Class/bean renames only — nothing persisted, nothing on the wire.

- `McpAuditService` → **`GatewayAuditService`** (90 methods, only ~20 MCP-shaped; injected in 34 files).
  The ~20 MCP-shaped `auditClient*`/`auditServer*` methods move behind an adapter-owned collaborator later.
- `McpAuditLogRepository` → `GatewayAuditLogRepository`; `McpAuditLogSpecification` → `GatewayAuditLogSpecification`.
- `McpErrorCode` → **`GatewayErrorCode`**, keeping only the `-33xxx` governance codes; the 5 JSON-RPC codes
  (all unused) move into the MCP adapter or are dropped. **The numeric values must not change** — they are
  persisted in `error_code` and read by the dashboard's error-breakdown query.
- Audit executor bean `mcpAuditExecutor` → `auditExecutor`, thread prefix `mcp-audit-` → `gw-audit-`
  (update the `@Async("...")` qualifier strings).
- `McpServerApplication` → `StdioMcpServerInitializer` (symmetry with the other two initializers).

---

## Band 3 — Package moves (compiler-verified; 33 findings)

Create `protocol/mcp/{inbound,outbound,transport,session}` and `protocol/spi/`.

**Move MCP-specific code out of the top level:**
- `wsServer/` → `protocol/mcp/inbound/` — `HttpMcpServerInitializer`, `StatelessMcpServerInitializer`,
  `StdioMcpServerInitializer`, plus `session/` and `transport/`.
  Extract the **triplicated helpers** (`resolvePublicNameByUri`, `parsePromptArguments`, `asToolMap`/`asPromptMap`)
  into one `McpCapabilityMapper`.
- `wsClient/` MCP parts → `protocol/mcp/outbound/` — `McpSessionManager`, `McpSession`, `HttpMcpTransport`,
  `McpClientInitializer`, `McpClientService`, `McpClientController`, health check.
- Transport renames: `StdioServerTransport` → `McpStdioServerTransport`;
  `ServerTransportProvider` → `McpStdioServerTransportProvider`; `HttpTransportConfig` → `McpHttpTransportConfig`.
  (Frees the neutral names for a real cross-protocol transport SPI.)
- `SessionManager` → `McpSessionRegistry`; `ClientSession` → `McpClientSession`.
- **`ToolCallOrchestrator` → `protocol/mcp/inbound/McpRequestFacade`** — its own javadoc calls it a "thin
  compatibility facade"; all three methods take/return MCP SDK types. *This is MCP orchestration currently
  sitting in the neutral `orchestration/` package — exactly the thing the target architecture forbids.*

**Lift neutral code OUT of the MCP packages:**
- → `security/`: `GatewaySecurityConfig`, `GatewayOAuth2Filter`, `OAuth2ProtectedResourceConfig`,
  `CorsConfig`, `TokenClassificationProperties`, `TokenClassificationService`.
- → `common/crypto/`: `ServerConfigCryptoService` → `SecretCryptoService` (AES/GCM, zero MCP content,
  consumed by STS and authConfig).
- → `admin/`: `DashboardController`.

---

## Band 4 — The seam (behavioural; requires Band 0; this is what makes it plug & play)

Today the spine is not actually protocol-neutral. Four concrete blockers:

1. **The seam interface is itself MCP-typed.** `ProtocolAdapter` imports `McpSchema` and returns
   `List<McpSchema.Content>` / `McpSchema.GetPromptResult` / `List<McpSchema.ResourceContents>`.
   *Its javadoc documents this as a deliberate Stage-0 deferral to be resolved "in the A2A phase" — that
   phase is now.*
   → Move to `protocol/spi/ProtocolAdapter` and re-type on a neutral **`CapabilityResult`**; each adapter maps
   its own wire types at the boundary.
2. **The spine reaches around its own seam.** `HopOrchestrator` injects `McpSessionManager` and calls
   `isConnected()` / `getSession()` at **6 sites** — an A2A hop would fail the connection precheck against an
   MCP session map it can never appear in.
   → Add `isTargetConnected(String)` and `downstreamSessionId(String)` to the adapter SPI.
3. **Six spine files import `io.modelcontextprotocol.*`**, including the two most neutral governance pieces:
   → Introduce **`orchestration/model/RequestContext`** (traceId, protocolRequestId, sessionId, callerName,
     identity map). Replace `McpSyncServerExchange` in `Hop`, `PolicyContextBuilder` (4 signatures) and
     `StatelessIdentityService` (split into neutral `RequestIdentityService` + a thin MCP binding).
   → `CapabilityRegistryService` ingest takes `List<CapabilityDescriptor>` instead of `List<McpSchema.Tool>`.
4. **Protocol identity leaks onto the wire and into routing.**
   → `ScopeDeriver` hardcodes `"mcp:"` into every minted OBO token scope (`mcp:tool:github:github_get_me`).
     Parameterise by protocol. **Breaking for any policy referencing existing scope strings** — coordinate.
   → `GatewaySecurityConfig` matches the literal `"/mcp"` prefix, so a future `/a2a` endpoint would be
     unauthenticated by default. Drive the matcher from adapter-registered route prefixes.

**Plug & play means adapters self-register**, rather than the spine hardcoding two branches. Each adapter
declares: its route prefixes (feeds security), its capability storage, its transport beans, and its mapping
to/from `CapabilityResult`.

Also here: extract the neutral half of `HttpMcpAuditFilter` into an `AdmissionControlService` the spine owns,
so A2A gets the same admission checks without duplicating the filter.

---

## Band 5 — Data & contract changes (migration-gated; highest care)

**There is no Flyway/Liquibase** — schema is `ddl-auto: update`, which will **silently create a new empty
table and orphan the old data** on a rename. Every item here needs a hand-written migration.

- `mcp_audit_log` → **`gateway_audit_log`** (+ `McpAuditLog` → `GatewayAuditLog`). Hardcoded in **8 native
  queries**. Ship a compatibility **view** named `mcp_audit_log` during transition for any external reader.
- Column `mcp_method` → `protocol_method`.
- **Add `protocol VARCHAR(20) NOT NULL DEFAULT 'MCP'`** to the tables recording per-request activity. This is
  the single most important A2A-readiness change: no table has a protocol discriminator today, so A2A
  activity cannot be distinguished or attributed. (It also retires the `stateless-` session-id prefix
  sniffing currently used to derive transport in the Activity view.)
- Capability tables: `mcp_server`/`mcp_tool`/`mcp_resource`/`mcp_prompt` → `capability_provider`/
  `capability_tool`/`capability_resource`/`capability_prompt` + `protocol` column, so an A2A skill has a home.
  (Repositories rename with them — all four have zero JPQL, so the code side is trivial.)
- `gateway_mcp_server_session` → `gateway_downstream_session`.
- **`AuditModule.WS_SERVER` / `WS_CLIENT` → `PROTOCOL_INBOUND` / `PROTOCOL_OUTBOUND`.**
  ⚠️ `@Enumerated(EnumType.STRING)` — these are persisted as literal strings. Renaming the constants makes
  Hibernate **fail to map existing rows**. Requires an `UPDATE` of the `module` column in the same migration.
- **Config namespace:** `ws.gateway.transport` (MCP-only values `http|stdio`) →
  `ws.gateway.protocols.mcp.transport`; `ws.gateway.stateless.*` → `ws.gateway.protocols.mcp.stateless.*`.
- **Dashboard-coupled API renames** — `/api/admin/mcp-servers` → `/api/admin/targets`. Requires a
  coordinated `ws-gateway-dashboard` change in the same commit.

---

## Sequencing

```
Band 0  →  Band 1  →  Band 2  →  Band 3  →  [ owner checkpoint ]  →  Band 4  →  Band 5
 tests     delete     rename     move                                  seam      data
```

Bands 0–3 make the code *read* like an agentic gateway and are all compiler-verified or pure deletion.
Band 4 makes it *behave* like one (and is the real prerequisite for A2A).
Band 5 makes the *data* honest and is the only band that can lose information if done carelessly.

Bands 0–3 can land safely before any A2A decision. Band 4 is the A2A enabler. Band 5 can trail behind.
