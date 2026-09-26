# WhiteSwan Agentic Gateway: Grounding Notes

> The shared reference for the gateway, to be read before intent-aware authorization research begins. It records facts only and contains no design proposals.

| Item | Value |
|---|---|
| Repo | `/Users/amitprakash/Desktop/WS Apps/AzureAdWsIntegration`. This is the only repo in scope; the prod `backend` repo was not read. |
| Gateway source root | `src/main/java/com/ws/wsAgenticSecurityGateway` (package `com.ws.wsAgenticSecurityGateway`) |
| Tests | `src/test/java/com/ws/wsAgenticSecurityGateway` |
| Date | 2026-09-25 |
| Method | 12 subsystem readers covered MCP inbound, A2A, the orchestration spine, PDP, identity/STS, the post-processor, registries, audit, governance UI back-ends, platform, callers and tests. 2 end-to-end tracers followed an MCP `tools/call` and the A2A financial-demo chain. Each reader was checked by an independent verifier. A completeness critic resolved contradictions between readers. A gap-fill step answered 4 follow-up questions, including read-only `SELECT`s against the local Postgres `ws_local`. A sweeper read the docs nobody had read yet. |
| Mode | Read-only. Nothing was built, run or tested. The live numbers come from existing audit rows. |
| Hand-verified (2026-09-26) | These claims were re-checked directly in code after the workflow: no Cedar library in `pom.xml` (regex engine); head `principal in AgentGroup` / `resource in Server` ignored by `matches()`; `/api/admin/**` on the `permitAll` chain; MCP `_meta` dropped in `handleToolCall`; `approvalStatus` UNKNOWN on MCP (`TenantContext` never set on the handler thread); the `* 2.*` copies (277 byte-identical, untracked; written 2026-09-25 20:41 IST, not by the grounding agents). |

### How to read this document
- Cites have the form `path:line` or `path:line-line`.
  - Plain paths are relative to the gateway source root, for example `orchestration/HopOrchestrator.java:331`.
  - A `repo:` prefix marks other files in this repo, for example `repo:pom.xml:324`.
  - Absolute paths mark files outside the repo: sample agents, dashboard, console and Maven jars.
- **(inferred)** means the claim follows from the code but was not proven at runtime. **(unverified)** means a reader made the claim and the verifier could not confirm it.
- Where a verifier corrected a reader, this document uses the corrected version (Appendix A lists the corrections). Where readers contradicted each other, it uses the critic's resolution.
- No secret values appear anywhere. Config is referred to by key name only.
- **Working-tree hazard (resolved 2026-09-26).** When this doc was written, the tree held 279 untracked, byte-identical `<Name> 2.<ext>` copies: 203 in the gateway root, 52 under `src/test`, and the rest in docs, resources and the graph cache. They appeared on 2026-09-25 at 20:41 IST, most likely from an iCloud Desktop sync conflict. All were removed after a byte-for-byte check against the originals. Every cite in this doc points at the unsuffixed original. `target/` may still hold copies until the next `mvn clean`.

### Glossary
| Term | Meaning in this document |
|---|---|
| MCP | Model Context Protocol: agent-to-tool calls (`tools/call`, `prompts/get`, `resources/read`) over JSON-RPC. The gateway acts as an MCP *server* toward agents and as an MCP *client* toward real tool servers. |
| A2A | Agent-to-Agent protocol: agent-to-agent calls made with the JSON-RPC method `message/send`. |
| Hop / leg | One governed call through the gateway, i.e. one `HopOrchestrator.handle`. A multi-agent request forms a tree of hops. |
| PDP | Policy decision point. Here it is `CedarPolicyEngine`, an in-house regex evaluator for a Cedar-like language. |
| STS | Security Token Service. It lives inside the gateway and mints a short-lived token for each hop. |
| OBO token | "On-behalf-of" JWT minted for each hop. TTL is 120 s. It carries the delegation lineage. |
| act_chain | The ordered list of principals in a delegation. The root (a human or an NHI) comes first and the current actor comes last. |
| NHI | Non-human identity, such as a service account or machine token. Stored in the registry table `gateway_nhi_registry`. |
| Tenant | The row-level partition key `ws_tenant_name`. At runtime it is carried either in the ThreadLocal `TenantContext` or in a per-session cache. |
| traceId / correlationId | `traceId` spans a whole multi-hop journey. `correlationId` is a fresh 16-hex id for each leg. |
| Capability | A tool, prompt or resource (MCP), or a skill (A2A). Each is addressed by a gateway "public name". |
| Door filter | `HttpMcpAuditFilter`. It is the servlet filter that runs identity and status gates before the MCP SDK sees a request, and only on `/mcp/*`. |

---

## 1. The gateway in one page

**What it is.** WAAG is an inline gateway: agents call it instead of calling tools or other agents directly. It ships as a single Spring Boot 3.3.4 / Java 17 fat jar listening on port 9492 (repo:pom.xml:8, :30; repo:src/main/resources/application.yml:89-91). It shares its JVM and its Postgres database with older co-hosted modules for Azure AD, K8s JIT and OPA (repo:src/main/java/com/ws/AzureAdWsIntegrationApplication.java:1-11). It exposes three surfaces:
- **MCP** for agents, on `/mcp` (Streamable HTTP, the default), on `/stateless/mcp`, or over stdio (protocol/mcp/transport/HttpTransportConfig.java:45-50).
- **A2A** for agents, on `POST /a2a` (JSON-RPC `message/send` only), plus a public Agent Card at `GET /.well-known/agent-card.json` (protocol/a2a/inbound/A2aInboundController.java:72-116).
- **Admin REST** under `/api/admin/**`. The dashboard uses it, and it has no authentication (security/GatewaySecurityConfig.java:74-84).

**What happens to one request (one hop).** Every governed call is turned into a protocol-neutral `Hop` and run through `HopOrchestrator` (orchestration/HopOrchestrator.java:120-136). The order for a tool leg (orchestration/HopOrchestrator.java:231-509) is:

1. **Door checks**, on `/mcp` only: revocation, sender constraint, human/NHI/agent status, and session identity pinning (protocol/mcp/transport/HttpMcpAuditFilter.java:124-393).
2. **Governance gate**: reject if the agent is DEPROVISIONED, BLOCKED or PENDING, looked up by session (orchestration/HopOrchestrator.java:248-256).
3. **Capability-profile allow-list**, applied only if the agent id resolves (:258-277).
4. **Registry lookup** of the public name in an in-memory map (:291-316).
5. **Build the act_chain** (:318).
6. **Build the PDP request and evaluate it synchronously.** Fails closed (:320-367).
7. **Connectivity gate** (:373-383).
8. **In-flight registration** (:385-422).
9. **Mint the per-hop OBO token.** Fails closed (:424-437).
10. **Dispatch downstream** through a protocol adapter. This call blocks (:439-450).
11. **Audit** asynchronously, and fire the **egress classifier** asynchronously in observe-only mode (:452-467).

**Main building blocks**

| Block | Main classes | Role |
|---|---|---|
| Inbound MCP | `HttpTransportConfig`, `HttpMcpAuditFilter`, `McpGatewayContextExtractor`, `HttpMcpServerInitializer`, `ToolCallOrchestrator` | Transport, door gates, per-request context bag, SDK handlers |
| Inbound A2A | `A2aInboundController`, `A2aMessageMapper`, `A2aRequestContextFactory` | JSON-RPC parsing, skill and arguments, context |
| Spine | `HopOrchestrator`, `ProtocolAdapter` (`McpAdapter`, `A2aAdapter`) | Governance pipeline and dispatch |
| Identity | `GatewaySecurityConfig`, `MultiIssuerJwtDecoder`, `GatewayOAuth2Filter`, `TokenClassificationService`, `AgentAssertionVerifier`, `TenantResolver` | Authentication, claims, token class, tenant |
| STS | `HopTokenMinter`, `StsService`, `ActChainBuilder`, `StsKeyService`, `StsRevocationService` | OBO minting, lineage, keys, revocation |
| PDP | `PolicyContextBuilder`, `CedarPolicyEngine`, `PolicyService`, `CustomAttributeService`, `PolicyLlmService` | Request building, evaluation, storage, AI-assisted authoring |
| Registries | `AgentRegistryService`, `CapabilityRegistryService`, `AgentCapabilityFilterService`, `McpSessionManager` | Identities, capabilities, allow-lists, outbound MCP |
| Egress | `EgressClassificationService`, `EgressClassifier` | Asynchronous tagging of responses |
| Audit | `GatewayAuditService`, `AuditQueryService` | Two ledgers and the trace views |
| Reporting | `ciso/*`, `compliance/*`, `accessgraph/*`, `admin/DashboardController` | Read-only governance reporting |

**Ten facts that surprise first-time readers.** Each one is covered in detail later.
1. The "Cedar" PDP is not the Cedar library. It is a regex parser for a Cedar-like subset (repo:pom.xml:324-326; pdp/service/CedarPolicyEngine.java:41-122). See §6.
2. Two policy-head forms are silently ignored: `principal in AgentGroup::"g"` and `resource in Server::"s"`. As a result, the live `financial-desk-grant` policy allows **any agent, any action, any resource** whenever the root principal is verified, and the audit ledger shows it doing exactly that (§6.9).
3. The only natural-language input the PDP ever sees is `context.argumentsFlat`, a flat `k=v` string. On A2A it contains the message text as `input=...`. On MCP it contains only tool arguments. Through the real front door, even hop 1 carries text written by the console's own LLM, not the human's words (§12, §13).
4. Nothing on the request path uses an LLM, an ML model or a request-side classifier. There are three Anthropic `claude-haiku-4-5` assistants, and all of them run only at admin/authoring time (§11).
5. The egress post-processor only looks at responses, runs asynchronously, and only observes. It never blocks or redacts anything (§8).
6. On MCP, `principal.approvalStatus` at the PDP is always `UNKNOWN`, because `TenantContext` is null on the MCP handler thread. Live data: 210 of 210 rows (§6.4).
7. `/api/admin/**` has no authentication. Its tenant comes from a caller-supplied `X-WS-Tenant` header, and that same header also overrides the tenant derived from the token on the data plane (§5.7).
8. `application.yml` has secret literals committed: an Anthropic key default, a GitHub token, datasource credentials and Azure credentials (§10, §14).
9. `/stateless/mcp` and `/a2a` do not pass through the `/mcp` door filter. So they have no human/NHI status gate, and `/stateless/mcp` also has no sender-constraint check (§3.5, §4.8).
10. MCP `_meta` is never read. It is dropped at the SDK handler boundary (protocol/mcp/inbound/HttpMcpServerInitializer.java:225-229).

---

## 2. Package map

| Package (under the gateway root) | Responsibility | Key classes |
|---|---|---|
| `protocol/mcp/transport` | HTTP and stdio transports, the `/mcp` door filter, per-request context | `HttpTransportConfig`, `HttpMcpAuditFilter`, `McpGatewayContextExtractor`, `StdioServerTransport`, `ServerTransportProvider` |
| `protocol/mcp/inbound` | MCP servers (session, stateless, stdio) and the facade from handler to spine | `HttpMcpServerInitializer`, `StatelessMcpServerInitializer`, `StatelessIdentityService`, `StdioMcpServerInitializer`, `ToolCallOrchestrator`, `McpRequestContextFactory` |
| `protocol/mcp/session` | stdio session objects and the idle-session reaper | `SessionManager`, `ClientSession`, `SessionReaperService`, `SessionLifecycleProperties` |
| `protocol/mcp/outbound` | The gateway acting as MCP client to real servers; server configs; health checks | `McpSessionManager`, `HttpMcpTransport`, `McpClientService`, `ServerConfigService`, `WsClientHealthCheckService`, `McpClientController`, `ServerConfigController` |
| `protocol/mcp/capability` | Persisting and indexing MCP tools, prompts and resources | `McpCapabilityRegistrar`, `McpServer/Tool/Resource/PromptEntity` |
| `protocol/a2a/inbound` | `/a2a` and the gateway Agent Card | `A2aInboundController`, `A2aMessageMapper`, `A2aRequestContextFactory`, `A2aAgentCardService` |
| `protocol/a2a/outbound` | Maps agent name to base URL | `A2aAgentDirectory` |
| `protocol/a2a/{capability,source,web,wire}` | Card ingestion, startup reconcile, admin API, rewriting of the role enum | `A2aAgentIngestionService`, `A2aCapabilityRegistrar`, `AgentSourceReconciler`, `SelfDescribeAgentSource`, `A2aAdminController`, `A2aRoleWire` |
| `protocol/a2a` (root) | Registers the route and its filter | `A2aTransportConfig` |
| `orchestration` (+`adapter`, `model`) | Governance spine, protocol adapters, protocol-neutral model | `HopOrchestrator`, `InFlightRequestRegistry`, `McpAdapter`, `A2aAdapter`, `OboTokenHolder`, `Hop`, `RequestContext`, `RequestAttributeKeys`, `CapabilityResult` |
| `capabilityRegistry` | Global in-memory capability index and its events | `CapabilityRegistryService`, `CapabilityDescriptor` |
| `agentRegistry` | Agents, humans, NHIs, sessions, capability profiles, the profile AI assistant | `AgentRegistryService`, `HumanUserService`, `NhiService`, `AgentCapabilityFilterService`, `CapabilityProfileService`, `CapabilityProfileChatService`, `AgentAnalyticsService` |
| `pdp` | Policy request building, the engine, policy storage, custom attributes, the policy AI assistant, policy activity | `PolicyContextBuilder`, `CedarPolicyEngine`, `PolicyService`, `CustomAttributeService`, `PolicyLlmService`, `PolicyActivityService`, `PolicyController` |
| `sts` | OBO minting, lineage, keys, rotation, revocation, JWKS | `HopTokenMinter`, `StsService`, `ActChainBuilder`, `ScopeDeriver`, `OboInvariants`, `ActChain`, `Principal`, `StsKeyService`, `StsRotationService`, `StsRevocationService`, `StsJwksController`, `StsAdminController` |
| `security` (+`workload`) | Filter chains, JWT decoding, claim extraction, token classification, agent assertion, tenant, CORS, OAuth metadata | `GatewaySecurityConfig`, `MultiIssuerJwtDecoder`, `StsJwtDecoder`, `GatewayOAuth2Filter`, `TokenClassificationService`, `AgentAssertionVerifier`, `TenantResolver`, `ProtocolRouteRegistry`, `CorsConfig`, `OAuth2ProtectedResourceConfig`, `JwtWorkloadIdentitySource` |
| `authConfig` | IdP config stored in the DB; a JWT decoder that can be swapped at runtime | `AuthConfigService`, `DelegatingJwtDecoder`, `AuthConfigController` |
| `audit` | The two audit tables, the async writer, query views, trace and identity graphs | `GatewayAuditService`, `AuditAsyncConfig`, `AuditQueryService`, `GatewayAuditLog`, `PdpAuditLog`, `AuditController` |
| `accessgraph` | Graph of observed versus entitled access | `AccessGraphService` |
| `postprocessor` | Egress classifier, rules and templates, insights, the rule AI assistant | `EgressClassificationService`, `EgressClassifier`, `BuiltInRecognizers`, `ClassifierRuleService`, `RuleAssistantService`, `PostProcessor*Service` |
| `ciso` | Executive dashboard, posture, accountability, blast radius, point-in-time view, activity trails, legacy compliance | `CisoDashboardService`, `PostureService`, `AccountabilityService`, `BlastRadiusService`, `PointInTimeService`, `AgentActivityTrailService`, `CisoController` |
| `compliance` | Standalone SOC 2 / SOX evidence packs | `ComplianceService` (bean `complianceModuleService`), `ComplianceController` |
| `admin` | Operations dashboard | `DashboardController` |
| `common` | Tenant plumbing and crypto | `TenantContext`, `TenantInterceptor`, `TenantEntityListener`, `SecretCryptoService` |
| `docs` | Two design notes that live inside the source root | `policy-versioning-design.md` (proposed, not built), `ciso-accountability-data-contract.md` |
| **Co-hosted legacy** (`com.ws.*` outside the gateway root) | Azure AD, Azure resources, K8s JIT, an OPA/Rego PDP (`mcpAgenticAIMgmt`), MCP access management | Not wired into the gateway, with three exceptions. `SecretCryptoService` falls back to the legacy `Constant.ENCRYPTION_KEY` (common/crypto/SecretCryptoService.java:3). The legacy `JacksonConfig` defines the app-wide `ObjectMapper` (repo:src/main/java/com/ws/mcpAgenticAIMgmt/config/JacksonConfig.java:12-18). The legacy `K8ResourceScheduler` runs every 20 s in the same JVM (repo:src/main/java/com/ws/scheduler/K8ResourceScheduler.java:26). |

---

## 3. Request lifecycle: MCP

### 3.1 Transports and wiring
| Endpoint | Server | Servlet filters | Notes |
|---|---|---|---|
| `POST/GET/DELETE /mcp` | `McpSyncServer` "ws-mcp-gateway" on the SDK's `HttpServletStreamableServerTransportProvider` | `GatewayOAuth2Filter` (order 1), then `HttpMcpAuditFilter` (order 2) | The default. It has sessions, identified by `Mcp-Session-Id`. |
| `POST /stateless/mcp` | `McpStatelessSyncServer` "ws-mcp-gateway-stateless" | `GatewayOAuth2Filter` only | Creates a new random session id for every request. |
| stdio | `McpSyncServer` over `ServerTransportProvider` | none | Selected with `ws.gateway.transport=stdio`. Serves a single agent; intended for development. |

Cites: protocol/mcp/transport/HttpTransportConfig.java:27, :52-155; protocol/mcp/inbound/StatelessMcpServerInitializer.java:58, :89-91; protocol/mcp/inbound/StdioMcpServerInitializer.java:37.
- The MCP Java SDK version is 0.12.1 (repo:pom.xml:392-398). `ws.gateway.transport` defaults to `http` via matchIfMissing, and yml also sets `http` (repo:src/main/resources/application.yml:127).
- There is no legacy HTTP+SSE transport. SSE appears only as the response framing of Streamable HTTP (protocol/mcp/transport/HttpMcpAuditFilter.java:738-755).
- **Thread model.** Handlers are registered with `McpServer.sync(...)` and `immediateExecution` is never set. In SDK 0.12.1 that makes each sync handler run as `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. Meanwhile the servlet thread calls `startAsync()`, sets the timeout to 0 (infinite) and parks in `block()` until the response has been written. So every in-flight `tools/call` holds **one Tomcat worker plus one boundedElastic worker**. ThreadLocals set in servlet filters, `TenantContext` in particular, are not visible to the handler (protocol/mcp/inbound/HttpMcpServerInitializer.java:87-89; /Users/amitprakash/.m2/repository/io/modelcontextprotocol/sdk/mcp/0.12.1/mcp-0.12.1.jar: `McpServerFeatures$AsyncToolSpecification.fromSync`, `HttpServletStreamableServerTransportProvider.doPost`). The stateless server uses the same offload.

### 3.2 `initialize` on sessionful `/mcp`
1. Spring Security decodes the bearer token (in oauth2 mode only; see §5.1). `GatewayOAuth2Filter` copies the claims into `jwt.*` request attributes and classifies the token (§5.3, §5.4).
2. Before the SDK runs, `HttpMcpAuditFilter` does two things. On `initialize` it refuses a human or NHI that is BLOCKED by subject, using uncached DB queries, with codes -33009 / -33010 (protocol/mcp/transport/HttpMcpAuditFilter.java:355-393). The agent status gate needs an `Mcp-Session-Id`, so it does **not** run on `initialize` (:282).
3. The SDK answers `initialize` and returns `Mcp-Session-Id` in a response header.
4. After the SDK has answered, `handleInitialize` runs (:484-689). In order, it:
   - adds the session to `knownSessionIds`;
   - resolves the tenant with `TenantResolver`: header, then the `ws_tenant` claim, then an issuer lookup in `gateway_auth_config`, then `"default"` (security/TenantResolver.java:33-57);
   - takes the client IP from the first `X-Forwarded-For` entry;
   - takes the agent name from the verified `client_id`, else the OBO `actor.*` claim (dead code for STS tokens; see §5.8), else the self-asserted `clientInfo.name`;
   - runs optional Tier-1 introspection, which happens only when `ws.gateway.auth.token-classification.mode=introspect`; the default is `jwt-signals`, so normally no network call is made;
   - calls `discoverAgent`, which stores name, version, protocolVersion and the client-capabilities JSON;
   - calls `discoverHumanUser` for HUMAN_DELEGATED tokens or `discoverNhi` for AUTOMATED_AGENT tokens;
   - calls `registerSession` to write a `gateway_agent_session` row;
   - calls `registerSessionIdentity`, which fills the audit identity cache, including the tenant;
   - pins the founding JWT `sub` to the session;
   - disconnects older sessions that have the same identity and agent;
   - writes the audit event `SERVER_SESSION_INITIALIZED`.
5. `discoverAgent` can throw `AgentBlockedException` (for BLOCKED or DEPROVISIONED), or the NHI can turn out to be BLOCKED. Either way, the session goes into `blockedSessionIds`. The client has **already received a successful initialize**, and is refused only from its next request on, with -33007 (:571-613, :671-683).

MCP `initialize` carries no purpose field. What it does carry is `clientInfo`, `protocolVersion` and the declared client capabilities.

### 3.3 `tools/list` (and `prompts/list`, `resources/list`, `resources/templates/list`)
- The SDK answers from specs built out of the registry: `name = publicName`, plus `description` and `inputSchema`, both copied **verbatim from the downstream server** (protocol/mcp/inbound/HttpMcpServerInitializer.java:450-457). The PDP is not consulted.
- If the in-memory `sessionToAgentId` map resolves the session's agent, the filter buffers the response (plain JSON, or the SSE `data:` line) and keeps only items whose `name` is in that agent's allow-set. An agent with no profile gets an empty list. If the agent does not resolve, the list goes out unfiltered (protocol/mcp/transport/HttpMcpAuditFilter.java:397-411, :703-800; agentRegistry/service/AgentCapabilityFilterService.java:94-104).
- The audit row `SERVER_TOOLS_LIST_REQUESTED` records the total size of the registry, not the filtered count the agent actually received (HttpMcpAuditFilter.java:802-807).
- Refresh behavior. A change in the registry is diffed by signature (`publicName|description|inputSchema`), and `list_changed` is sent only if something changed. A change to a profile broadcasts `list_changed` to every client unconditionally (HttpMcpServerInitializer.java:159-187, :265-403, :431-448).

### 3.4 `tools/call`, step by step (sessionful `/mcp`, oauth2)
1. **JWT decode.** `MultiIssuerJwtDecoder` reads the unverified `iss` only to choose a decoder. Gateway STS tokens go to `StsJwtDecoder`; all other tokens go to the global IdP decoder (security/MultiIssuerJwtDecoder.java:32-47; security/StsJwtDecoder.java:48-80; authConfig/service/DelegatingJwtDecoder.java:25-69).
2. **Claims.** `GatewayOAuth2Filter` sets the `jwt.*` attributes and `jwt.token_type`. It audits `OAUTH2_AUTH_SUCCESS` once per session (security/GatewayOAuth2Filter.java:64-162).
3. **Door filter** (protocol/mcp/transport/HttpMcpAuditFilter.java:124-353):
   - DELETE tears the session down.
   - An unknown session id gets -32001.
   - The POST body is cached and parsed, but only `id` and `method` are read from it.
   - Then the gates run in this order:
     - `blockedSessionIds` → -33007;
     - revoked session id or revoked inbound `jti` → -33015;
     - `X-Agent-Assertion` is verified, and if the OBO's `cnf.workload_id` differs from the presenter's azp → -33016. A verified presenter's roles and groups overwrite `jwt.all_roles`, `realm_roles` and `groups`. `client_roles` and the principal are left unchanged;
     - human status by `sub`: BLOCKED is refused on every method (-33009), PENDING only on execution methods (-33013);
     - NHI status: -33010 / -33014, same rule;
     - agent status (needs a session): DEPROVISIONED -33012, BLOCKED -33007, PENDING on execution methods -33011;
     - identity pinning: a different `sub` than the founding one → -32001.
   - Every rejection is an HTTP 200 carrying a JSON-RPC error (:859-867).
4. **Context bag.** `McpGatewayContextExtractor` builds the `McpTransportContext` from headers and servlet attributes only. It **never reads the body** (protocol/mcp/transport/McpGatewayContextExtractor.java:17-138). It contains:
   - `traceId`: the `X-Trace-Id` header, else the OBO `trace_id` claim, else a new UUID;
   - `authorization`: the raw header value (a credential inside the bag);
   - `agentName`: the `X-Agent-Name` header;
   - `correlationId`: the `X-Correlation-Id` header;
   - `clientIp`: `getRemoteAddr()`, not `X-Forwarded-For`;
   - the JWT-derived attributes, present only if `client_id` or `sub` exists;
   - `_httpHeaders`: every header except `authorization`, `cookie`, `set-cookie`, `proxy-authorization` and `www-authenticate`. `X-Agent-Assertion` is **not** excluded.
   - `jsonRpcRequestId` is never set on HTTP, so `requestId` is null.
5. **SDK handler** (on boundedElastic). It calls `ensureAgentRegistered`, a fallback registration that uses the unverified `clientInfo.name`, then `orchestrate(exchange, request.name(), request.arguments())`. **`CallToolRequest.meta()` (`_meta`) is dropped here** (protocol/mcp/inbound/HttpMcpServerInitializer.java:189-229).
6. **Hop.** `ToolCallOrchestrator` builds `Hop(TOOL, publicName, arguments, null, RequestContext(clientInfo, sessionId, read-through attributes))` (protocol/mcp/inbound/ToolCallOrchestrator.java:36-46; protocol/mcp/inbound/McpRequestContextFactory.java:25-60).
7. **Spine entry.** The traceId goes into MDC. A 16-hex correlationId is generated; the `X-Correlation-Id` header is **not** used. `sessionId` comes from the RequestContext; `SessionManager` is used only in stdio mode. `clientName` is `"name vX"`. `recordRequest` runs asynchronously (orchestration/HopOrchestrator.java:120-136, :199-206, :231-246, :1329-1377).
8. **Governance gate.** DEPROVISIONED, BLOCKED or PENDING by session, checked in memory with a DB fallback (:248-256, :1415-1427).
9. **Capability profile.** Applied only if an agent id resolves. Returns -33008 if denied, and writes a grant or deny audit row (:258-277; agentRegistry/service/AgentCapabilityFilterService.java:85-108).
10. **Registry lookup.** An in-memory map. Returns -33001 if the name is missing. The descriptor holds `description` and `inputSchema`, and **neither is passed to the PDP** (:288-316; capabilityRegistry/service/CapabilityRegistryService.java:66-68).
11. **act_chain.** `ActChainBuilder.fromTransportContext` runs **outside** the PDP try block, so an `OboIntegrityException` escapes without any error shaping (:318, :1379-1406; sts/service/ActChainBuilder.java:33-145).
12. **PDP.** The sequence is:
    - `buildForToolCall`;
    - `setActChain`;
    - async `PDP_EVALUATION_REQUESTED`;
    - `cedarPolicyEngine.evaluate(auditService.resolveTenant(sessionId), req)`;
    - async `PDP_DECISION_RENDERED`, wrapped in try/catch so an audit failure cannot bypass the decision.

    A DENY returns `CallToolResult(isError=true, "[-33003] ... Policy violation: <reason>")`, and any exception also denies (:320-367; audit/service/GatewayAuditService.java:1027-1128).
13. **Adapter selection** uses `descriptor.protocol`. MDC `protocol` is set only at this point, so every audit row written earlier in the leg is stamped `MCP` (:369-371, :215-225).
14. **Connectivity gate.** `McpSessionManager.isConnected` needs three things: an in-memory session, a `CONNECTED` DB row (**queried on every call**), and `transport.isConnected()`. Failure returns -33002 (:373-383; protocol/mcp/outbound/config/McpSessionManager.java:421-449).
15. **In-flight registration.** The args JSON is stored truncated to 2000 characters (:385-422).
16. **Mint** (§5.8). A revoked session fails closed. A null tenant skips minting. On MCP the token **is not put on the wire** (:424-437; sts/service/HopTokenMinter.java:54-84).
17. **Credentials.** `applyCredentials` works only in stdio (via `SessionManager`). In HTTP mode the downstream call always uses the server-config headers (:439-441; orchestration/adapter/McpAdapter.java:243-312).
18. **Downstream call.** `McpClientService.callTool` sends `new CallToolRequest(originalName, args)`, with no `_meta` and no identity. The SDK's default request timeout of 20 s applies; `HttpMcpTransport` sets connect timeout = config timeout (default 30 s) and read timeout = 0. Only `content()` is kept; **`isError` and `structuredContent` are dropped**, so a downstream tool error comes back as a success. The audit row `CLIENT_TOOL_INVOCATION` stores the full arguments and the full content (protocol/mcp/outbound/service/McpClientService.java:211-255; protocol/mcp/outbound/config/HttpMcpTransport.java:64-156; protocol/mcp/outbound/config/McpSessionManager.java:98-105).
19. **Response.** `CallToolResult(content verbatim, isError=false)`. The audit row `ORCHESTRATION_RESPONSE_RETURNED` has no body for tools. `updateLastActivity` runs asynchronously (orchestration/adapter/McpAdapter.java:73-80, :142-170; orchestration/HopOrchestrator.java:452-476; protocol/mcp/inbound/ToolCallOrchestrator.java:41-45).
20. **Egress.** `fireEgress` enqueues asynchronous, observe-only classification of `fullText` (orchestration/HopOrchestrator.java:144-182; §8).

### 3.5 `/stateless/mcp`: differences
- Each request gets session id `stateless-<UUID>`. `StatelessIdentityService.bootstrap` then does the following (protocol/mcp/inbound/StatelessIdentityService.java:77-165):
  - runs a revocation check. The session check is dead code, because the id is freshly random; only the `jti` check can ever match;
  - resolves the tenant: header, then issuer lookup in the DB, then `"default"`. There is **no `ws_tenant` claim step**, so a gateway OBO presented here falls back to `"default"` (inferred);
  - sets MDC `wsTenant`;
  - calls `discoverAgent(client_id, version "stateless")`, which runs `saveAndFlush` on every request;
  - calls `discoverHumanUser` only for HUMAN_DELEGATED tokens, and never discovers an NHI;
  - links the identity in memory only;
  - evicts the audit identity after `ws.gateway.stateless.identity-evict-grace-seconds` (default 60).
- The exchange is synthetic, with a null session and null clientInfo. SDK elicitation and sampling are therefore unusable here (inferred) (protocol/mcp/inbound/StatelessMcpServerInitializer.java:290-338).
- **What is missing** because `HttpMcpAuditFilter` is not registered on this path (protocol/mcp/transport/HttpTransportConfig.java:149): the sender-constraint (`cnf`) check, the role override from the assertion, the human/NHI BLOCKED/PENDING gate, identity pinning, and list filtering. **What remains**: the `jti` revocation check, `AgentBlockedException` from `discoverAgent`, the spine's `governanceDenial` (agent status only), and capability profiles.

### 3.6 stdio
- A single `currentSession` model. `HopOrchestrator.resolveSessionId` prefers it whenever a `SessionManager` is wired, which is unsafe with concurrent agents (protocol/mcp/session/SessionManager.java:34-39; orchestration/HopOrchestrator.java:1329-1349).
- Credential-like keys (14 names, including `token`, `apiKey`, `key` and `secret`) are harvested recursively from **every** message, including tool arguments. `McpAdapter` forwards the token family as `Authorization: Bearer` and `apiKey`/`api_key` as `X-API-Key` (protocol/mcp/transport/StdioServerTransport.java:264-299; orchestration/adapter/McpAdapter.java:243-312).
- Raw messages are logged at INFO without masking (StdioServerTransport.java:61, :184-191).
- Identity is self-asserted through `clientInfo.name`. Tools are frozen at startup. `Thread.join()` blocks the runner thread (protocol/mcp/session/ClientSession.java:77-92; protocol/mcp/inbound/StdioMcpServerInitializer.java:142-183).
- stdio is the only MCP transport that sets `jsonRpcRequestId` (protocol/mcp/transport/ServerTransportProvider.java:40-44).

### 3.7 Prompts and resources
- They follow the same pipeline, with these differences:
  - failures are thrown as `RuntimeException("[code] ...")` rather than returned as `isError` results (protocol/mcp/inbound/ToolCallOrchestrator.java:48-67);
  - the PDP gets `arguments=null` even though prompt arguments are on the Hop (pdp/service/PolicyContextBuilder.java:77-99);
  - the PDP decision audit is not try-wrapped (orchestration/HopOrchestrator.java:912-918, :1174-1180);
  - argument-serialization failures are ignored on prompts (:975-979).
- A resource's public name is resolved by exact match on the URI (protocol/mcp/inbound/HttpMcpServerInitializer.java:237-242, :541-549).

### 3.8 Idle reaping
- `SessionReaperService` runs every `ws.gateway.session.reaper-interval-seconds` (yml 30, Java default 60). It marks CONNECTED rows with `COALESCE(last_request_at, connected_at)` older than `idle-timeout-minutes` (yml 10, default 30) as DISCONNECTED, and removes the session's entry from `sessionToAgentId` (protocol/mcp/session/SessionReaperService.java:44-101; repo:src/main/resources/application.yml:139-141).
- It does **not** clear the filter's in-memory maps or the SDK session. A reaped session keeps working at the transport level but loses its agent link, so list filtering and the spine's profile check are both skipped (inferred).

### 3.9 JSON-RPC and gateway error codes
| Code | Meaning | Notes |
|---|---|---|
| -32001 | Stale session / identity mismatch | A hard-coded literal that collides with `REQUEST_TIMEOUT` (audit/error/GatewayErrorCode.java:15) |
| -32601 / -32602 | Unknown method / invalid params or no target skill | A2A |
| -33001 | CAPABILITY_NOT_FOUND | |
| -33002 | SERVER_UNAVAILABLE | The message says "Enterprise MCP server unavailable" even for A2A |
| -33003 | PDP_DENIED | Also used when the STS mint fails ("Delegation token unavailable") |
| -33004 | ORCHESTRATION_FAILURE | |
| -33007 / -33008 | AGENT_BLOCKED / CAPABILITY_NOT_ALLOWED | |
| -33009 / -33010 | HUMAN_BLOCKED / NHI_BLOCKED | |
| -33011 / -33013 / -33014 | AGENT / HUMAN / NHI pending approval | Applied to execution methods only |
| -33012 | AGENT_DEPROVISIONED | |
| -33015 | TOKEN_REVOKED | |
| -33016 | SENDER_CONSTRAINT_VIOLATION | |

Cite: audit/error/GatewayErrorCode.java:8-32.

---

## 4. Request lifecycle: A2A, including multi-hop delegation

### 4.1 Surface
- **`GET /.well-known/agent-card.json`** is public. It returns the gateway's own card, which lists **every SKILL in the global registry, across all tenants**. Each skill appears with `id` and `name` set to its publicName, its description, and `tags=["gateway"]`. The card declares streaming=false, push=false and transport JSONRPC, with url `ws.a2a.card.url` (default `http://localhost:9492/a2a`). The Javadoc gives the path as `/a2a/.well-known/...`, but that is wrong (protocol/a2a/inbound/A2aAgentCardService.java:16, :42-83; protocol/a2a/inbound/A2aInboundController.java:72-75).
- **`POST /a2a`** accepts `message/send` only. Any other method gets -32601. There is no `message/stream`, `tasks/get`, `tasks/cancel`, or push notification support (A2aInboundController.java:48, :113-116).
- **Admin**: `/api/admin/a2a/**` covers ingest by URL, import of card JSON, list, detail, health, skills, and delete (protocol/a2a/web/A2aAdminController.java:39-252).
- **SDK**: a2a-java 1.0.0.Final (spec, client and client-transport-jsonrpc modules), with Gson pinned to 2.11.0 (repo:pom.xml:346-357, :401-418). The client module is used only for `A2ACardResolver`. Outbound calls are hand-rolled, so the pom comment claiming "outbound goes through the a2a client" is out of date (repo:pom.xml:342-345).

### 4.2 Inbound hop, step by step
1. The security chain runs (only in oauth2 mode), then `GatewayOAuth2Filter` runs on the exact path `/a2a`. A2A requests carry no `Mcp-Session-Id`, so `OAUTH2_AUTH_SUCCESS`, with the raw claims as payload, is audited **on every call** (protocol/a2a/A2aTransportConfig.java:41-67; security/GatewayOAuth2Filter.java:138-148).
2. `TenantContext = TenantResolver.resolve(request)`. It is cleared in `finally` (A2aInboundController.java:86, :149).
3. The body is parsed with Jackson and `id` and `method` are read. The parse happens outside any JSON-RPC try block, so malformed JSON most likely produces HTTP 500 rather than -32700 (inferred) (:88).
4. **The sender constraint runs before the method check.** `X-Agent-Assertion` is verified. If the bearer token has `cnf.workload_id` and that value differs from the assertion's azp, or no valid assertion was sent, the request gets -33016. A verified assertion overwrites roles and groups (:95-111, :155-167).
5. `A2aRoleWire.toSdk` rewrites any JSON field named `role` whose value is `user` or `agent`, **at any depth**. That includes `message.metadata.arguments.role`. The params are then parsed with Gson into `MessageSendParams`; a parse failure gives -32602 (:118-128; protocol/a2a/wire/A2aRoleWire.java:88-109).
6. **Skill** resolution order: `message.metadata.skillId`, then `params.metadata.skillId`, then the one SKILL in the *global* registry if exactly one exists across all tenants. Otherwise the request gets -32602 "No target skill". The value must be the gateway publicName `<agent>.<skillId>` (protocol/a2a/inbound/A2aMessageMapper.java:39-45; A2aAgentCardService.java:71-74).
7. **Arguments**: all non-blank TextParts are joined with `\n` into `arguments.input`. Then `message.metadata.arguments`, if it is a Map, is merged over the result, so it can **overwrite `input`**. DataPart, FilePart, taskId, referenceTaskIds, extensions, other metadata keys and `params.configuration` are all ignored (A2aMessageMapper.java:48-70).
8. **Context**: `JwtWorkloadIdentitySource` provides `client_id`, `sub` and verified=true (security/workload/JwtWorkloadIdentitySource.java:23-33). `A2aRequestContextFactory` then builds a `RequestContext` (protocol/a2a/inbound/A2aRequestContextFactory.java:31-68):
   - clientInfo = ClientInfo(agentId);
   - **sessionId = `message.contextId`**;
   - attributes: agentClientId, jwtSubject, userIdentity, idpIssuer, tokenType, the role sets, groups, clientIp, `jsonRpcRequestId` = messageId, `rawJwtClaims`, and `traceId` (the OBO `trace_id` if present, otherwise the `X-Trace-Id` header);
   - **not set**: `_httpHeaders`, `customClaims`, `agentName`, `correlationId`.
9. The controller builds `Hop(SKILL, skillName, args, null, ctx)` and calls `HopOrchestrator.handle` (A2aInboundController.java:141-145).

### 4.3 How the spine differs for SKILL hops (orchestration/HopOrchestrator.java:515-803)
- **Agent id**: A2A contextIds are normally not MCP sessions, so the agent id is resolved from `clientName` with a tenant-scoped DB query (`resolveAgentIdByName`). If it resolves and the agent has no profile, the call gets -33008. If it does not resolve, the profile check is **skipped** (:542-566; agentRegistry/service/AgentRegistryService.java:621-630).
- **Governance gate**: `governanceDenial(contextId)` usually finds nothing and lets the call proceed (inferred). So a caller's BLOCKED, PENDING or DEPROVISIONED status is **not** enforced on A2A by this gate. A policy can still read `principal.approvalStatus`, which does resolve on A2A because `TenantContext` is set. Live data: 8 A2A calls from claude-desktop while it was PENDING were all ALLOWed.
- **PDP**: action `skillInvocation`, resourceType `SKILL`, arguments include `input` (pdp/service/PolicyContextBuilder.java:64-75).
- **Connectivity**: this is only a check that the agent name is in the in-memory `A2aAgentDirectory`. No health probe is made (orchestration/adapter/A2aAdapter.java:67-69).
- **Mint**: scope `a2a:skill:<agent>:<publicName>` and `cnf.workload_id = <target agent>`. The token is stored in the ThreadLocal `OboTokenHolder` and cleared in `finally` (:713-730, :798).
- **Failure mapping**:
  - `IllegalArgumentException` from `URI.create`, `HttpRequest.newBuilder` or `.timeout` is thrown *outside* the adapter's try block and maps to SERVER_UNAVAILABLE (-33002).
  - `send` and parse failures inside the try become `IllegalStateException`, which maps to ORCHESTRATION_FAILURE (-33004).
  - An agent missing from the directory is also ORCHESTRATION_FAILURE.
  - Cites: A2aAdapter.java:93-119; HopOrchestrator.java:771-796.
- **Response text**: `fullText` is audited as `ORCHESTRATION_RESPONSE_RETURNED.response_payload.content` and handed to the classifier. For SKILL hops, `fireEgress` does a synchronous DB lookup (`safeResolveAgentId`) before it enqueues (:751-760, :155, :185-191).

### 4.4 Outbound dispatch (`A2aAdapter.invokeSkill`)
- The adapter builds a **new** JSON-RPC `message/send` with a new id, a new messageId, `role: user` and `kind: message`. It contains **one text part**, which is `args.input`. If there is no `input`, it sends the whole args JSON as a string; if `input` is not text, it sends `""`. It also sets `metadata.skillId = originalName` (e.g. `analyze`). Headers are Content-Type, Accept, and `Authorization: Bearer <OBO>`, the last only if a token was minted (orchestration/adapter/A2aAdapter.java:89-149, :230-236).
- **Not forwarded**: contextId, taskId, the structured `metadata.arguments`, `X-Trace-Id`, `X-Agent-Assertion`, the caller's token. Trace and lineage continue **only through the OBO claims**.
- The call is a synchronous `java.net.http` send. Connect timeout is 10 s. Request timeout is `ws.a2a.outbound.timeout-seconds`, default 120, raised from 30 for fan-out (:48-58). The call goes to the stored base URL, not to the card's advertised url or supportedInterfaces.
- **Response handling**: non-2xx, unparseable JSON, or a JSON-RPC error all become `IllegalStateException`. On success, `summary` is the *first* text part and `fullText` is all text parts joined. The text comes from `result.parts` or `result.status.message.parts`. **`Task.artifacts`, data parts and file parts are ignored.** `itemCount` is always 1 (:151-211).

### 4.5 Reply to the caller
- `toTask` builds the reply with a new random taskId. contextId is the inbound one, or a new UUID if there was none. State is COMPLETED or FAILED. The status message is **`summary` only**, so multi-part replies are truncated for the caller (A2aMessageMapper.java:72-87).
- Spine failures come back as a FAILED Task inside a JSON-RPC `result` with text `"[code] message: skill ... — detail"`. For a PDP deny, that text includes the deny reason (orchestration/HopOrchestrator.java:1441-1462).
- JSON-RPC errors are used only for -32601, -32602 and -33016.
- `OboIntegrityException` and a `resolveAdapter` `IllegalStateException` escape uncaught. The gateway has no `@ControllerAdvice`, so these most likely produce HTTP 500 with no audit row (inferred) (HopOrchestrator.java:607, :660).

### 4.6 Multi-hop delegation: the financial demo and how act_chain grows
The chain is: console → `advisor.analyze` → {`market-data.quote`, `fundamentals.earnings`, `news.sentiment`}, run concurrently by the advisor's LLM → the specialists' MCP tools → Alpha Vantage. market-data and fundamentals can also delegate to `news.sentiment` (/Users/amitprakash/Desktop/WS Apps/a2a-sample-agents/advisor.py:12-19, market_data.py:13-16, fundamentals.py:11-14).

| Hop | Caller presents | tokenType | PDP principal | act_chain at the PDP | Minted OBO |
|---|---|---|---|---|---|
| 1. console → `/a2a` `advisor.analyze` | Keycloak user token (azp `agent-console`) | HUMAN_DELEGATED | `agent-console` | [human `amit-prakash` (verified), agent-console] (depth 2) | aud/cnf `advisor` |
| 2. advisor → `/a2a` `market-data.quote` | gateway OBO (aud `advisor`) + `X-Agent-Assertion` (azp `advisor`) | HUMAN_DELEGATED, because the OBO has `act.sub` (SIGNAL_2) | `advisor` (from the single `aud`) | [human, agent-console, advisor] (depth 3) | aud/cnf `market-data` |
| 3. market-data → `/mcp` `tools/call` alphavantage_* | OBO (aud `market-data`) + assertion | HUMAN_DELEGATED | `market-data` | [human, agent-console, advisor, market-data] (depth 4) | aud = server (not sent on the wire) |

Mechanics:
- When the inbound claims contain `act_chain`, `ActChainBuilder` appends the caller, skipping it if it duplicates the last principal. It then checks `OboInvariants` and throws `OboIntegrityException` on violation (sts/service/ActChainBuilder.java:56-70).
- On OBO hops, `userIdentity` is null, because an OBO has no `preferred_username`. The tenant comes from the `ws_tenant` claim, unless an `X-WS-Tenant` header overrides it (security/TenantResolver.java:37-46).
- A child's OBO carries `trace_id`, which continues the journey, and `corr_id`, which equals the *parent* leg's correlationId. Nothing on the decision path reads `corr_id` or `scope` from inbound claims (sts/service/StsService.java:86-91).
- **Not propagated**: the parent's text, the human's question, contextId, task ids, metadata. Each downstream agent sees only its own `input` text plus the OBO.
- **Autonomous mode** (`AGENT_AUTONOMOUS=1`): the agent sends its own client-credentials token instead of the OBO, so the chain restarts with no `trace_id` and no `act_chain` (/Users/amitprakash/Desktop/WS Apps/a2a-sample-agents/agent_identity.py:78-89). A session-less AUTOMATED caller never gets an NHI root; it gets an unverified human root from `sub` (inferred) (ActChainBuilder.java:78-97).
- **Timeout stacking**: every A2A level has its own 120 s timeout, and no deadline is passed down. When a parent times out, its children are not cancelled. The OBO TTL is also 120 s, so an agent that holds its OBO for longer than that before re-presenting it would fail validation (inferred) (sts/service/HopTokenMinter.java:33).

### 4.7 Agent registration (ingestion)
- Admin paths:
  - `POST /api/admin/a2a/agents {name, baseUrl}` fetches the card from the base URL.
  - `POST /agents/import` takes card JSON; the card's `url` becomes the base URL, and spec defaults are filled in.
- `register()` runs these steps in order (protocol/a2a/capability/A2aAgentIngestionService.java:76-94; agentRegistry/service/AgentRegistryService.java:255-288):
  1. same-tenant URL-conflict check;
  2. `directory.register`;
  3. `registrar.register`;
  4. `registerA2aEndpoint`, which persists `gateway_agent.speaks_a2a` and `a2a_base_url`; new rows start as PENDING;
  5. audit.

  The in-memory state is changed before the DB write and is not rolled back if the write fails (inferred).
- Skills are held **in memory only**. publicName is `<agent>.<skillId>` and the description is the skill description, or the skill name if there is none. Tags, examples, modes and any input schema are dropped. Skills with a null id are dropped (protocol/a2a/capability/A2aCapabilityRegistrar.java:36-66).
- At startup, `AgentSourceReconciler` (@Order 10) reads every `speaks_a2a` row across all tenants and fetches each live card. If a fetch fails, the endpoint is still registered but with **zero skills**. There is no periodic refresh (protocol/a2a/source/AgentSourceReconciler.java:42-49; A2aAgentIngestionService.java:124-145).
- The registrar does not publish `CapabilityRegistryChangedEvent`, so SKILL allow-sets are only recomputed when something else triggers it (§7.5).
- The directory and the registry are keyed by name only, with no tenant. A2A agent names share the `serverIndex` namespace with MCP server names, so a collision evicts the other side's capabilities (inferred) (capabilityRegistry/service/CapabilityRegistryService.java:30-62).

### 4.8 A2A governance gaps (summary)
- The door filter does not run on `/a2a`. So there is no human or NHI status gate, no identity pinning, and agent status is effectively unenforced (see 4.3).
- There is no per-`jti` revocation check on `/a2a`. The only revocation check is at mint time, keyed by the caller-chosen `contextId`. A revoked A2A session can be sidestepped by sending a new contextId (inferred) (sts/service/HopTokenMinter.java:61-64).
- A caller-controlled `contextId` is used as the sessionId for session-keyed lookups. If it collides with a live MCP session id, the call would pick up that session's agent and tenant (inferred risk).
- In `AUTH_MODE=none`, `/a2a` runs unauthenticated with an ANONYMOUS workload identity and no `cnf` check (security/GatewaySecurityConfig.java:56-64; security/workload/JwtWorkloadIdentitySource.java:29-31).

---

## 5. Identity, authentication and the token service

### 5.1 Filter chains and the effective auth mode
- **Chain Order(1)** applies when the path is in `ProtocolRouteRegistry` (a prefix `startsWith` match against `/mcp`, `/stateless/mcp`, `/a2a`) **and** the effective mode is `oauth2`. It is stateless, has CSRF off, uses `anyRequest().authenticated()`, and is a JWT resource server using `MultiIssuerJwtDecoder`. **Chain Order(2)** is `permitAll` for everything else: `/api/admin/**`, `/api/mcp/**`, `/.well-known/**`, `/authorize` and `/token` (security/GatewaySecurityConfig.java:47-84; security/ProtocolRouteRegistry.java:34-44).
- **Where the effective mode comes from**: a single global cached value (authConfig/service/AuthConfigService.java:112-114).
  - At startup it is set from the first *enabled* `gateway_auth_config` row (oldest first, any tenant). If there is no such row, it uses env `ws.gateway.auth.mode` (`${AUTH_MODE:none}`), otherwise `none` (:87-110; repo:src/main/resources/application.yml:129).
  - A poll every 60 s (`ws.gateway.auth.config-poll-interval-ms`) uses `findFirstByOrderByCreatedAtAsc`, which **includes disabled rows** and sets the mode to `none` if that row is disabled (:450-484).
  - Until `ApplicationReadyEvent` the mode is `none`, which leaves a short open window during boot (inferred).
- **Live local DB** (gap-fill): there is exactly one row. It has `auth_mode=oauth2`, is enabled, has tenant `amitdev.local`, a Keycloak issuer `http://localhost:8180/realms/ws-gateway`, `token_classification_mode=jwt-signals`, and an empty `audience`. So the effective mode is **oauth2 whatever `AUTH_MODE` is set to**.
- In mode `none`: `GatewayOAuth2Filter` sets nothing (security/GatewayOAuth2Filter.java:70-73), every subject-based gate is a no-op, the workload identity is ANONYMOUS, and the act_chain root is `unknown` and unverified.

### 5.2 JWT decoding
- `MultiIssuerJwtDecoder` looks at the **unverified** `iss`. If it starts with `<ws.gateway.sts.issuer-base>/sts/` (default `https://gateway.local`), the token goes to `StsJwtDecoder`; anything else goes to the IdP decoder (security/MultiIssuerJwtDecoder.java:32-47).
- **`StsJwtDecoder`** (security/StsJwtDecoder.java:48-80):
  - takes the tenant from the `iss` suffix;
  - loads ACTIVE+RETIRING keys **with a DB query on every decode** and builds a new Nimbus decoder each time;
  - verifies RS256 and the timestamps.
  - Its issuer check compares the token's own `iss` with itself, so **it can never fail**. What actually binds the token is that the issuer's tenant selects the key set. There is **no audience check**.
- **IdP decoder**:
  - It is one global `DelegatingJwtDecoder`, not one per tenant. After an issuer change it keeps a grace window, default 30 min (authConfig/service/DelegatingJwtDecoder.java:25-86; security/GatewaySecurityConfig.java:28-40).
  - It is built as `NimbusJwtDecoder.withJwkSetUri(...)` on a **trust-all TLS** RestTemplate with 10 s timeouts, on the config and env paths. No issuer or audience validator is added, so only the Spring default timestamp checks apply (inferred from library defaults). The stored `audience` is never enforced (authConfig/service/AuthConfigService.java:499-543, :660-690).
  - The grace-swap path builds its decoder with a *default* RestTemplate, so standard TLS applies there (:554).
  - Bug: a change to the JWKS URI alone never triggers a swap, because `setJwksUri` runs before the comparison (:226, :251-252).
- Authorities from `IdpAgnosticRoleConverter` feed only Spring. The chain uses `authenticated()` and never checks roles (security/GatewaySecurityConfig.java:92-131).

### 5.3 Claims extracted by `GatewayOAuth2Filter`
- Attributes set:
  - `jwt.client_id`: azp, then client_id, then the single `aud` value;
  - `jwt.subject`, `preferred_username`, `email`, `name` and its parts, `email_verified`, `jwt.issuer`, `jwt.jti`;
  - realm, client and all roles. Realm roles fall back to the flat `roles` claim, then `groups`;
  - `jwt.groups`, with the leading `/` stripped;
  - `jwt.custom_claims`: only claims prefixed `ws_gateway_*`;
  - `jwt.raw_claims`: every claim;
  - `jwt.access_token`;
  - `jwt.token_type` and `jwt.classification_signal`.
- Cite: security/GatewayOAuth2Filter.java:21-41, :89-162, :165-177, :216-224.
- `OAUTH2_AUTH_SUCCESS` is audited once per `Mcp-Session-Id`, or on every request when there is none. The payload is the raw claims. Each filter instance has its own dedup set, which is cleared once it exceeds 8192 entries (:54, :138-148).

### 5.4 Token classification
- There are exactly two classes: `HUMAN_DELEGATED` and `AUTOMATED_AGENT`. "NHI" is the name of the registry entity that `AUTOMATED_AGENT` sessions create (security/TokenClassificationService.java:20-21).
- The rules are ordered and use claims only: 19 rule returns plus a default.
  1. The `ws_gateway_token_type` claim.
  2. `act.sub` → HUMAN.
  3. `gty`/`grant_type`, then `idtyp`.
  4. `amr` with a human method → HUMAN.
  5. `auth_time` together with openid scope or a human identity → HUMAN.
  6. GCP service account, `xms_mirid`, `clientHost`/`clientAddress`, or `sub==iss` → AUTOMATED.
  7. `service-account-*` username or `sub==clientId` → AUTOMATED.
  8. openid, `scp`, `|` in `sub`, or a username → HUMAN.
  9. A client id with no human identity → AUTOMATED.
  10. Default → HUMAN (`SIGNAL_7_CONSERVATIVE_DEFAULT`).

  Cite: :43-166.
- At request time the filter always uses the jwt-signals rules, whatever mode is configured.
  - Tier-1 introspection runs only at MCP `initialize`, only in mode `introspect`, and only when credentials are configured.
  - It uses a plain `new RestTemplate()` with **no timeout**, and the property `introspection-timeout-ms` is never applied.
  - Its result changes only the tokenType used for registration. The per-session cache it writes is never read.
  - Cite: :38-41, :168-258; security/TokenClassificationProperties.java:12-34.
- **Consequence**: a gateway OBO presented back to the gateway is classified HUMAN_DELEGATED, because it carries an `act` claim, even when the root of its chain is an NHI (inferred).

### 5.5 Agent assertion and sender constraint
- `X-Agent-Assertion` carries the agent's own IdP JWT, usually a Keycloak client-credentials token; a `Bearer ` prefix is optional.
- It is verified by `MultiIssuerJwtDecoder`. The result is: workloadId = azp (else client_id), the realm roles, and the groups. If the header is absent, the token is invalid, or it has no client id, the result is null, and that alone does not reject the request.
- No audience, grant or proof-of-possession checks are made, so any valid IdP token with an azp is accepted, including a human's token (security/AgentAssertionVerifier.java:27, :40-80).
- **`cnf`** is minted only for `a2a:` scopes, as `{workload_id: <target agent>}` (sts/service/StsService.java:102-105). On `/mcp` and `/a2a`, if the presented OBO has `cnf.workload_id` and the assertion's azp differs, or is missing, the request gets -33016.
- **What the assertion overrides**: `ATTR_ALL_ROLES` and `ATTR_REALM_ROLES` become the assertion's realm roles, and `ATTR_GROUPS` becomes its groups. `ATTR_CLIENT_ROLES`, the principal name and the act_chain actor are left unchanged (protocol/mcp/transport/HttpMcpAuditFilter.java:208-215; protocol/a2a/inbound/A2aInboundController.java:105-111).
- The check is implemented only in `HttpMcpAuditFilter` (on `/mcp`) and in `A2aInboundController`. It is **absent on `/stateless/mcp`**.

### 5.6 Workload identity
- Identity sources plug in through a seam, `WorkloadIdentitySource`. The only implementation is `JwtWorkloadIdentitySource`, which sets agentId = `client_id`, subject = `sub`, verified=true and method JWT, or returns ANONYMOUS. SPIFFE is planned (security/workload/WorkloadIdentitySource.java:14-21; JwtWorkloadIdentitySource.java:23-33).
- `Principal` has fields for `workload_id` and `identity_source`. At decision time, `identity_source` is **always the hard-coded fallback `KEYCLOAK`** (sts/service/ActChainBuilder.java:134-145):
  - on MCP, the registry lookup is tenant-filtered and `TenantContext` is null there;
  - on A2A, the agentId is null.

### 5.7 Tenancy
- **Mechanism**:
  - Every gateway entity has a NOT NULL `ws_tenant_name` column.
  - The tenant is held in a ThreadLocal, `TenantContext` (common/context/TenantContext.java:5).
  - A default JPA listener stamps it on insert (repo:src/main/resources/META-INF/orm.xml:13-19; common/listener/TenantEntityListener.java:34-52).
  - The audit `TaskDecorator` copies MDC and `TenantContext` into audit threads (audit/config/AuditAsyncConfig.java:36-67).
  - There is **no** Hibernate multi-tenancy, no filter and no row-level security. All scoping lives in hand-written repository queries.

| Plane | How the tenant is resolved | Where it is carried | Notes |
|---|---|---|---|
| `/api/admin/**`, `/api/mcp/**` | The `X-WS-Tenant` header, which is required (400 if missing) | `TenantContext`, set by `TenantInterceptor` | No authentication, so the caller picks the tenant (common/interceptor/TenantInterceptor.java:24-34; security/CorsConfig.java:28-31) |
| `/mcp` | `TenantResolver` at initialize: header → `ws_tenant` claim → issuer mapped via `gateway_auth_config` → `"default"` | Session identity cache, read by `resolveTenant(sessionId)` | `TenantContext` is **null** on the handler thread |
| `/stateless/mcp` | header → issuer DB lookup → `"default"` (no claim step) | MDC `wsTenant` plus the identity cache | A gateway OBO lands on `"default"` (inferred) |
| `/a2a` | `TenantResolver` | `TenantContext` on the request thread | |
| PDP | `resolveTenant(sessionId)`: cache → `TenantContext`; **null means the global fallback set**, which is the union of every tenant's enabled policies | | (audit/service/GatewayAuditService.java:872-882; pdp/service/CedarPolicyEngine.java:198-201) |

- The header outranks the verified `ws_tenant` claim and the issuer mapping. So a caller holding a valid token can pick another tenant's policy set and registry scope (inferred) (security/TenantResolver.java:37-46).
- Sentinel values in use: `"default"` (resolver), `"system"` (audit fallback), `"unknown"` (filter).
- State that is **not tenant-scoped**:
  - `CapabilityRegistryService`
  - `A2aAgentDirectory`
  - `McpSessionManager.sessions`
  - the custom-attribute cache
  - `AgentCapabilityFilterService.getAllProfiles()`
  - `InFlightRequestRegistry`
  - the `PolicyLlmService` metadata cache key
  - the gateway's public Agent Card
- Live data: every data-plane decision ran under `amitdev.local`. 45 A2A audit rows are stamped `"system"` because the async audit writer did not propagate the tenant. That was fixed in commit 32a37f1 on 2026-08-18 (audit/config/AuditAsyncConfig.java:36-51).

### 5.8 STS mint
- `HopTokenMinter.mintForHop` (sts/service/HopTokenMinter.java:54-84):
  - if `isSessionRevoked(sessionId)`, it throws `StsMintException`. This fails closed and is reported as -33003 "Delegation token unavailable";
  - if the tenant is null, blank or `"unknown"`, it skips minting and returns null;
  - otherwise it derives the scope and mints with TTL 120 s;
  - it audits `STS_TOKEN_MINTED`. The receipt holds jti, trace_id, corr_id, kid, alg, iss, sub, aud, scope, ttl, act_chain, actor and obo_invariants. It does **not** hold the raw token, `act`, `cnf`, `ws_tenant` or `nbf`.
- `StsService.mint` (sts/service/StsService.java:50-123):
  - if the key fails to load, it throws `StsMintException`;
  - it checks the invariants and fails closed on structural violations, **but only when the chain is non-empty**. An empty chain is minted anyway with `sub="unknown"`;
  - it signs RS256 with the key's kid and typ `JWT`.

| Claim | Value |
|---|---|
| `iss` | `<issuer-base>/sts/<tenant>` |
| `sub` | the id of the act_chain root, or `"unknown"` |
| `aud` | the target server or agent name (`hop.serverName`) |
| `iat`, `nbf`, `exp` | now, now, now + 120 s |
| `jti` | a random UUID |
| `act_chain` | the flat list of principal maps, root first |
| `act` | RFC 8693 nested actor, present only with ≥2 principals |
| `scope` | `<protocol>:<type>:<server>:<publicName>`. Protocol and type are lowercased, protocol defaults to `mcp`, a blank type becomes `capability`, and a blank server or name becomes `unknown` (sts/service/ScopeDeriver.java:18-28) |
| `trace_id`, `corr_id`, `ws_tenant` | the journey trace, this leg's correlationId, the tenant |
| `obo_invariants` | the computed invariant flags |
| `cnf` | `{workload_id: <target>}`, only for `a2a:` scopes |

- The token has **no `azp` or `client_id` claim, no `actor` claim, and no purpose or intent claim.** That is why the `actor.*` fallbacks in `PolicyContextBuilder` and `HttpMcpAuditFilter` never fire for gateway tokens (pdp/service/PolicyContextBuilder.java:113-132; protocol/mcp/transport/HttpMcpAuditFilter.java:467-481).
- The token goes **on the wire only for A2A**, where `OboTokenHolder` puts it into `Authorization: Bearer`. On MCP it is an artifact used only for audit and enforcement (orchestration/HopOrchestrator.java:728-730; orchestration/adapter/A2aAdapter.java:97-106).

### 5.9 act_chain construction and invariants
- Each **principal** carries: `id`, `type` (`human|nhi|agent`, lowercase in the claim), `verified`, `idp`, `username`, `workload_id` (plus a legacy duplicate `clientId`), and `identity_source` (sts/model/Principal.java:20-58). `Principal.fromClaim` defaults an unknown type to AGENT.
- **Extension**: if the inbound claims contain `act_chain`, the current caller is appended and the invariants are checked. A failure throws `OboIntegrityException` (sts/service/ActChainBuilder.java:56-70).
- **Fresh root**, tried in this order (:81-97):
  1. a verified HUMAN, which requires HUMAN_DELEGATED, a `sub`, and either the session's human id or a non-blank `preferred_username`;
  2. a verified NHI from the session's `nhiId`;
  3. an unverified HUMAN built from `sub`;
  4. `unknownRoot`, with id `unknown`, type HUMAN and verified=false.

  If the inbound `act.sub` is set, an unverified agent is added next.
- **Actor** (:112-122):
  - with an MCP session, id = the registry agent UUID and verified = (clientId present);
  - session-less (A2A or stateless), id = `client_id` and verified = (tokenType present).
- **OboInvariants** (sts/model/OboInvariants.java:29-118):
  - hard checks: prefixPreserved, appendOnly (+0/+1), subConstant, rootPresent, monotonicRoles (the root is HUMAN or NHI, everything after it is AGENT);
  - report-only: allVerified, scopePresent.
- Live data (gap-fill): `rootVerified` and `actorVerified` are true in 494 of 497 PDP requests. The 3 false rows are unauthenticated calls from 2026-08-06. Every root in the data is a human, and **no NHI root has ever occurred live**.

### 5.10 Revocation
- Two kinds: by `jti` (default window 15 min) and by session (default window 6 h). The defaults apply only when the admin omits `expiresAt`.
- They are backed by the DB plus per-instance in-memory sets, warmed at `@PostConstruct`.
- The hourly purge only removes expired entries. Other instances therefore learn about a new revocation **only on restart** (sts/service/StsRevocationService.java:33-163).
- Where revocation is honored:
  - `/mcp`: both session and `jti` (HttpMcpAuditFilter.java:171-185);
  - stateless: only `jti` is effective (StatelessIdentityService.java:89);
  - `/a2a`: **no jti check**; only the session check at mint time.
- Endpoints:
  - admin `POST /api/admin/sts/revocations` for a `jti`;
  - admin `POST /api/admin/sts/revocations/session`, which also disconnects the session;
  - public `GET /.well-known/sts/revocations/{jti}` (sts/web/StsAdminController.java:99-170; sts/web/StsJwksController.java:42-45).

### 5.11 Keys, rotation, JWKS
- One RSA-2048 key set per tenant, created lazily. The private JWK is encrypted with AES-GCM via `SecretCryptoService`, and the signing key is cached per instance.
- Keys move ACTIVE → RETIRING → RETIRED. A key becomes RETIRED once the grace window (`ws.sts.key.grace-window`, PT1H) has passed; its private key is then scrubbed, and at most 5 RETIRED keys are kept (sts/service/StsKeyService.java:44-221).
- Rotation:
  - manual: `POST /api/admin/sts/keys/rotate`;
  - automatic: opt-in per tenant (default off, 90 days), checked by a daily sweep (sts/service/StsRotationService.java:30-66).
  - Rotating refreshes only the local instance's cache. Other instances keep signing with the demoted key (inferred).
- The public `GET /.well-known/sts/jwks.json` reads `TenantContext`, which is **never set on that path**, so it most likely returns an empty key set (inferred) (sts/web/StsJwksController.java:17-35).

### 5.12 Other identity surfaces
- `OAuth2ProtectedResourceConfig` serves (security/OAuth2ProtectedResourceConfig.java:35-165):
  - RFC 9728 metadata, with the resource hard-coded to `http://localhost:<port>`;
  - a proxy for the IdP's authorization-server metadata;
  - `/authorize`, a 302 to the IdP that rewrites `scope` to `openid email profile`;
  - `/token`, which proxies the IdP token endpoint.
- The IdP config is managed with CRUD, validate, discover and refresh-jwks under `/api/admin/auth-config` (authConfig/controller/AuthConfigController.java:27-133). The admin identity comes from `jwt.*` attributes, but those are never set on `/api` paths, so it falls back to `admin@<ip>`.

---

## 6. Policy decision point

### 6.1 What the engine is
- `CedarPolicyEngine` is a pure-Java evaluator that parses a subset of Cedar-like syntax with `java.util.regex`. There is **no Cedar library** in `pom.xml`; it contains only a comment (repo:pom.xml:324-326; pdp/service/CedarPolicyEngine.java:3-16, :41-122).
- It has no schema, no entity store and no type checking. The principal type is hard-coded as `Agent` (:647).
- Policies are parsed on save, on reload, and on the lazy load of an unseen tenant. They become immutable per-tenant lists. Each request is a **single linear scan** (:124-134, :158-221).

### 6.2 Grammar the engine actually honors
| Construct | Where it appears | Honored? |
|---|---|---|
| `principal == Agent::"x"` | head | Yes. The id comparison is case-sensitive. |
| `principal is T` | head | Matches only when T is `Agent` (case-insensitive). |
| `principal in AgentGroup::"g"` | head | **No.** It is ignored, so the policy applies to *any* principal (:411-420, :734-739). |
| `principal in AgentGroup::"g"` | when/unless | Yes. It checks the JWT **groups**, not the approval status (:665-670, :929-932). |
| `action == Action::"x"`, `action in [...]` | head | Yes, case-insensitive. |
| `resource == Tool\|Prompt\|Resource\|Skill::"x"` | head | Yes. The type comparison is case-insensitive (:749). |
| `resource in [T::"a", ...]` | head | Yes, but the type is taken **from the first element only** (:441-450). |
| `resource is T` | head | Yes. |
| `resource in Server::"s"` | head | **No.** It is ignored, so the policy applies to *any* resource (:437-456). |
| `resource in Server::"s"` | when/unless | Yes (:504-511). |
| `resource == Tool::"x"` | when/unless | The type is compared **case-sensitively** against runtime `TOOL`, so it **never matches** in production (:944). |
| `attr == / != value` (string, bool, long) | condition | Yes. A missing attribute makes the condition false, **including `!=`** (:950-952). |
| `< <= > >=` | condition | Integers only. |
| `attr like "*pat*"` | condition | Yes. The wildcard is `*`, matching is case-sensitive, and a new regex is compiled on every evaluation (:999-1018). |
| `attr.contains("x")` | condition | Yes, as a plain substring match on space-joined strings (:974). |
| `&&` | condition | This is the only way to combine conditions (:475). |
| `\|\|`, `!`, parentheses, `has`, decimals, `in [...]` inside a condition, a bare boolean | condition | **Not supported.** An unrecognized fragment is dropped with only a DEBUG log, which **widens a permit**. Fragments are matched with `Matcher.find()`, so `!(x == true)` is evaluated as `x == true` (:473-490). |
| Several `when{}` / `unless{}` blocks | | Only the first of each is read, and a block ends at the first `}` (:71-73, :460-468). |
| `//` comments | | Stripped before parsing. This also strips text inside a quoted string, for example a URL (:380). |
| Effect | | Decided by whichever of "permit" or "forbid" appears first **anywhere** in the text, including inside `@id(...)` (:390-400). |

### 6.3 Evaluation semantics
- If the tenant has no policies, the result is DENY with basis `NO_POLICIES` (:273-278).
- The scan returns DENY with the forbid's id at the **first matching forbid**. If any permits matched, it returns ALLOW with all of their ids. Otherwise it returns DENY with basis `DEFAULT_DENY`. Any exception returns DENY with basis `EVAL_ERROR` and `hasErrors=true` (:289-374).
- Priority order (ascending) changes only which forbid id is reported and the order of permit ids. It never changes the decision.
- The policy reference reported is the `@id` if there is one, otherwise the policy **name**. It is never `cedar_policy_id` (:183, :300, :385-388). `decidedBy()` is never null: when no policy matched it returns the markers DEFAULT_DENY, NO_POLICIES or EVAL_ERROR (pdp/dto/PolicyEvaluationResult.java:49-57).
- The result carries: `decision`, `matchedPolicies`, `reason`, `evaluationDurationMs` (1 ms resolution), `hasErrors`, `diagnostics`, `decisionBasis`. There are **no obligations and no advice**. A deny reaches the caller as `"Policy violation: <reason>"` (orchestration/HopOrchestrator.java:349-355).
- Failure handling is fail-closed in two layers: the engine returns EVAL_ERROR, and HopOrchestrator denies on any exception while building or evaluating the request (:361-367, :650-656). The one exception: the act_chain is built *before* that try block, so an `OboIntegrityException` escapes without being turned into a denial result.
- The guardrails `context.rootVerified == false` and `context.actorVerified == false` **do not fire when act_chain is absent**, because a missing attribute makes the condition false. A test pins this behaviour (repo:src/test/java/com/ws/wsAgenticSecurityGateway/pdp/service/CedarPolicyEngineTest.java:90-102). On real hops the chain is always non-empty.

### 6.4 Building the request (`PolicyContextBuilder`)
- Action / resourceType pairs: `toolCall`/TOOL, `skillInvocation`/SKILL, `promptGet`/PROMPT, `resourceRead`/RESOURCE. **Prompt and resource requests pass `arguments=null`** (pdp/service/PolicyContextBuilder.java:51-99).
- **agentName** resolves in this order (:113-161):
  1. the verified `AGENT_CLIENT_ID`;
  2. `rawJwtClaims.actor.clientId` / `actor.id`. This path is dead in practice, because the STS never mints an `actor` claim;
  3. the self-asserted `clientInfo.name`;
  4. `"unknown"`.
- **approvalStatus** comes from `findAgentsByName(name)`, which filters by `TenantContext`, and defaults to `"UNKNOWN"` (:163-171; agentRegistry/service/AgentRegistryService.java:708-710). **On MCP the result is always UNKNOWN** because `TenantContext` is null there. Live data shows 210/210 toolCall rows as UNKNOWN, even for APPROVED agents. On A2A it resolves: 220 APPROVED, 56 UNKNOWN (older rows), 8 PENDING.
- **sourceIp** is `CLIENT_IP`. On MCP that value is `getRemoteAddr()`, not `X-Forwarded-For` (:173-181).
- **Arguments** are sanitized as follows: a top-level String longer than 2000 chars is cut and gets `...[truncated]` appended; a `byte[]` becomes `"[binary data]"`; nested maps and lists are passed through untouched. The result is put into a new `HashMap`, so key order is not deterministic (:261, :318-331).
- **Custom attributes** come from two places: the ones registered in the DB (`CustomAttributeService.resolveAttributes`) and any `CustomAttributeProvider` beans (**there are none**; the interface exists at :24-29, :296-316). Provider values win on a collision, and errors are swallowed (:199-201, :268-316).
- HopOrchestrator then calls `setActChain(actChain.toClaim())` (orchestration/HopOrchestrator.java:324, :613, :902, :1164).
- **Never passed to the PDP**: the capability descriptor's description and inputSchema (they are in scope at orchestration/HopOrchestrator.java:306-309 but not used), traceId, requestId, MCP `_meta`, the raw claims, inbound `scope` and `corr_id`, the response, and any history.

### 6.5 Every attribute a policy can reference (pdp/service/CedarPolicyEngine.java:644-720)
| Attribute | Source |
|---|---|
| `principal` id / `principal.name` | `agentName` (see 6.4) |
| `principal.version` | `clientInfo.version`. Null on A2A. |
| `principal.approvalStatus` | Registry lookup by name and `TenantContext`. Always UNKNOWN on MCP. |
| `principal.sessionId` | MCP session id, or A2A `contextId` (null for current callers), or `stateless-<uuid>` |
| `principal.roles`, `realmRoles`, `clientRoles`, `groups` (and the AgentGroup set) | Roles and groups from the JWT, joined with spaces. A verified X-Agent-Assertion replaces roles, realmRoles and groups, but not clientRoles. |
| `resource` id / `resource.name`, `serverName`, `originalName`, `type` | The publicName, the registry server, the original name, and `TOOL`/`SKILL`/`PROMPT`/`RESOURCE` |
| action | `toolCall` / `skillInvocation` / `promptGet` / `resourceRead` |
| `context.businessHours`, `hour`, `minute`, `dayOfWeek`, `month`, `year` | `LocalDateTime.now()` in the JVM default zone. businessHours is Mon–Fri 08:00–18:00 (:677-683, :1020-1027). |
| `context.sourceIp`, `serverName`, `resourceName`, `correlationId` | Client IP; the gateway-generated 16-hex correlation id (not the header) |
| `context.argumentsFlat` | `k=v k=v` built from the sanitized arguments with `toString()`. Empty string when there are no arguments. On A2A it is `input=<message text> ...`. |
| `context.actChainDepth`, `rootType`, `rootId`, `rootVerified`, `actorType`, `actorId`, `actorVerified` | Taken from the first and last act_chain elements only, and set only when the chain is non-empty (:693-704) |
| `context.<custom>` | Custom attributes. They are merged **last** with `putAll`, so they can **overwrite built-ins** such as `rootVerified` or `argumentsFlat` (:706-708). |

### 6.6 On the request but never read by the engine
`agentClientId`, `jwtSubject`, `userIdentity` (the human's username), `tokenType`, and `jwtCustomClaims`. The comment at :660-661 says custom claims are merged; that is false. The structured arguments map, the intermediate act_chain nodes, and each node's `idp`/`username`/`workload_id`/`identity_source` are not read either (pdp/dto/PolicyEvaluationRequest.java:17-46). All of these are persisted in audit.

### 6.7 Custom attributes
- They are stored in `gateway_custom_attribute` with these fields: `attribute_name`, validated only against `^[a-zA-Z][a-zA-Z0-9_]*$` with **no reserved names**; `data_type` (STRING/INTEGER/BOOLEAN); `value_source` (STATIC/HEADER/AGENT_FIELD); `source_key`; `default_value`; `enabled` (pdp/entity/GatewayCustomAttributeEntity.java:14-68; pdp/service/CustomAttributeService.java:21-27, :298-316).
- **HEADER** attributes look up the header by exact name, then case-insensitively, in `_httpHeaders`, which exists on MCP only. If not found they try `transportContext`, which holds `clientIp`, `agentName` and `correlationId`; the last two come from the caller headers `X-Agent-Name` and `X-Correlation-Id`. Failing that, the default is used. **On A2A only `clientIp` can resolve** (:196-227).
- **AGENT_FIELD** attributes read `totalRequests`, `totalSessions`, `status`, `approvalStatus`, `protocolVersion`, `firstSeenAt`, `lastSeenAt` or `agentVersion`, with **one DB lookup per attribute per request** (:232-259).
- The runtime cache loads enabled attributes for **all tenants**, refreshes every 30 s under `synchronized`, and `getById`, `update`, `delete` and `toggle` run with no tenant check (:20, :48-54, :278-291).
- Live DB: 0 rows. The latent risk (inferred): a HEADER attribute named `rootVerified` would let a caller-supplied header override the lineage guardrails.

### 6.8 Storage, tenancy, reload, default guardrails
- **`gateway_policy`** columns (pdp/entity/GatewayPolicyEntity.java:14-96):
  - `policy_name`, unique per tenant;
  - `cedar_policy_id`, taken from `@id` or a slug;
  - `policy_text`, the only column the evaluator reads;
  - `effect`, display only;
  - `enabled`, `priority` (default 100), `tags`;
  - `source`: MANUAL, DEFAULT or LLM_GENERATED;
  - `original_prompt`;
  - `principal_kind` / `principal_id`, a derived read model;
  - `created_by`, `created_at`, `updated_at`. There is no `updated_by`.
- **Slots**: one per tenant, plus a global fallback that is the union of every tenant's enabled policies. An unseen tenant is loaded lazily on first evaluation, and its guardrails are seeded at that point.
  - If the loader throws, an empty list is cached and every request for that tenant is denied until the next reload.
  - If the loader is not wired yet (before app ready), the global set is used (pdp/service/CedarPolicyEngine.java:198-221).
- **Reload**: every CRUD operation re-parses the tenant's slot **and** the whole global set. Reloads are per-JVM only (pdp/service/PolicyService.java:411-427).
- **Guardrails**: `deny-unverified-root` (`forbid ... when { context.rootVerified == false }`) and `deny-unverified-actor` are FORBID policies with source DEFAULT and priority 10.
  - They are seeded for each tenant at startup and on lazy load, controlled by `ws.gateway.policy.seed-default-lineage` (default true).
  - The API refuses to update, delete or toggle them.
  - Seeding skips a guardrail by name even when it has been disabled (PolicyService.java:29-37, :97-126, :216-219).
- **Validation**:
  - create and update validate the text and check `@id` uniqueness; create also rejects a duplicate name;
  - delete and toggle only check the protection flag;
  - `validatePolicy` only checks that an effect and a head exist, and never reports ignored fragments (CedarPolicyEngine.java:223-239).
- **Read model vs evaluator**: `extractPrincipal` reports a head `principal in AgentGroup` as AGENT_GROUP, but the evaluator applies that policy to everyone. The "policies for agent X" view excludes AGENT_GROUP rows (CedarPolicyEngine.java:850-868; PolicyService.java:142-166).
- Three separate effect detectors can disagree: `CedarPolicyEngine` takes the first keyword, `PolicyService` returns FORBID if `forbid` appears anywhere, and `PolicyLlmService` uses `startsWith("forbid")` (PolicyService.java:447-454; pdp/service/PolicyLlmService.java:758-761).

### 6.9 Live policy state (local `ws_local`, gap-fill)
- `gateway_policy` holds 21 rows across 2 tenants, of which **4 are enabled**. The last startup reload, on 2026-09-25 at 20:12:41, reported `policyCount=4`, so every enabled row parsed.
- **`amitdev.local`**: 2 enabled rows.
  - `agent-console-github-get-me` (LLM_GENERATED): `principal == Agent::"agent-console"`, `toolCall`, on 3 GitHub tools, with no conditions.
  - `financial-desk-grant` (MANUAL): `permit(principal in AgentGroup::"financial-agents", action, resource) when { context.rootVerified == true }`. Because the head is ignored, **what actually runs is "permit any agent, any action, any resource whenever the root is verified."**
  - The other 17 are disabled, including **both DEFAULT guardrails**. The API refuses that change, and 13 rows share one `updated_at`, so this was probably a direct bulk DB update (inferred). **`amitdev.local` has no enabled forbid.**
- **`default`**: the 2 DEFAULT forbids and no permit, so every request is denied.
- **Ledger proof of the widened grant**: `agent-console`, whose `agentGroups` was `[]`, was ALLOWed 44 times through `financial-desk-grant` (`advisor.analyze`, `github_get_me`). `fundamentals → alphavantage_BALANCE_SHEET` was allowed 4 times even though no scoped permit lists it.
- **No policy, enabled or disabled, references `context.argumentsFlat`.** None has a condition fragment that gets dropped. There are no custom attributes.
- The customer-facing reference teaches exactly the ignored head forms (repo:docs/features/policy-engine-reference.md:36-38, :52, :58, :76, :97, :122). Applying the code to its examples, "Example 1" permits any agent to make any toolCall, and "Example 4" forbids every call whose root is an NHI (inferred). The doc's claim that rules are "never accidentally broader" (:140) is false.

### 6.10 Evaluation cost (measured, gap-fill; 2 enabled policies, 0 custom attributes)
- `evaluationDurationMs` over 497 decisions: p50 0, p99 1, max 6 ms.
- Time from the request audit to the decision audit: SKILL p50 0.22 / p95 0.94 ms; TOOL p50 0.13 / p95 0.26 ms.
- Pre-PDP time (from `TOOL_EXTRACTED` to `PDP_EVALUATION_REQUESTED`): SKILL p50 1.75 / p95 6.2 / max 6.6 ms; TOOL p50 1.80 / p95 8.1 / max 85.3 ms. Almost all of it is building the act_chain and the context, which include DB reads: `findAgentsByName` always, `getAgent` on MCP, and one query per AGENT_FIELD attribute.
- For each evaluation the engine logs one INFO line per policy (:283-298).

### 6.11 Test and check endpoints
- **`POST /api/admin/policies/test`** is a dry run. It defaults `rootVerified` and `actorVerified` to true and `resourceType` to `"Tool"`, and sends no arguments, custom attributes or sessionId. So it **cannot exercise `argumentsFlat`**, and it hides the case-sensitivity bug in 6.2 (pdp/controller/PolicyController.java:167-221).
- **`/check`** checks syntax, the warning, the references against the registries, and duplicate `@id`, then evaluates the decision with and without the draft (:236-315).
- **`/validate`** checks syntax only.
- Dry runs are not audited.

### 6.12 Policy AI assistant (`PolicyLlmService`)
- **Endpoints**: `/api/admin/policies/chat` generates a policy; `/chat/save` saves it with `enabled=true` as soon as it validates, with **no approval step** (pdp/controller/PolicyController.java:358-412).
- **Model and call**: Anthropic `claude-haiku-4-5`, `max_tokens` 2048, synchronous, 10 s connect / 30 s request timeout, no retry (pdp/service/PolicyLlmService.java:38-40, :83-85, :139-177).
- **System prompt**: a static grammar section plus live metadata. The metadata is cached for 60 s **under a single global key** (a cross-tenant leak, inferred) and contains:
  - agents;
  - human users' usernames, full names, **emails**, roles and custom claims;
  - NHIs;
  - tool names;
  - policy names;
  - custom-attribute definitions.

  (:233-720)
- **The grammar it teaches is out of date**: it has no `skillInvocation`, no Skill type, and no roles/groups or act_chain attributes. It describes `AgentGroup::"APPROVED"` as an approval group, and it has a pattern that emits multiple statements (:261-392).
- The prompt is logged at INFO, and requests and replies are audited truncated to 500 characters (:113; audit/service/GatewayAuditService.java:1254-1289).

### 6.13 Decision ledger and attribution
- Each decision produces two async rows in `pdp_audit_log`: `PDP_EVALUATION_REQUESTED`, whose `pdp_context` holds the full request JSON, and `PDP_DECISION_RENDERED`, which holds the result JSON plus `pdp_policy_id` (≤512 chars) and `pdp_reason` (≤1024 chars). Both are mirrored into `gateway_audit_log` (audit/service/GatewayAuditService.java:1027-1128).
- `PolicyActivityService` joins on `cedar_policy_id` while the engine reports policy names, so a policy without an `@id` can show as "dead". The coverage metric counts `DEFAULT_DENY` markers as "attributed" (inferred) (audit/repository/PdpAuditLogRepository.java:76-106).

---

## 7. Capability registry and profiles

### 7.1 Agents
- **Table `gateway_agent`** (agentRegistry/entity/GatewayAgentEntity.java:16-120)
  - Unique on (tenant, `agent_name`) and on (tenant, `a2a_base_url`).
  - Columns: `agent_version`, `protocol_version`, `capabilities` JSONB (the MCP client's feature flags, which say nothing about purpose), `status` (ACTIVE or DEPROVISIONED), `approval_status` (PENDING, APPROVED or BLOCKED), counters, `auth_client_id`, `token_type`, `identity_source`, `workload_id`, `speaks_mcp`, `speaks_a2a`, `a2a_base_url`.
  - Version holds only the latest value seen.
  - There is **no purpose, owner or description column**. repo:docs/features/ciso-dashboard-data-contracts.md:29, :44 notes this as a gap.
- **Four ways an agent row gets created**:
  1. MCP `initialize`, after the SDK has run. Uses the verified name.
  2. `HttpMcpServerInitializer.ensureAgentRegistered` fallback. Uses the **unverified** `clientInfo.name` and no tenant.
  3. Every stateless request (`client_id`, version `stateless`).
  4. A2A `registerA2aEndpoint`.

  New rows are ACTIVE with approval PENDING (agentRegistry/service/AgentRegistryService.java:136-288; protocol/mcp/inbound/HttpMcpServerInitializer.java:189-223).
- **Admin actions** (agentRegistry/service/AgentRegistryService.java:787-912)
  - `approve` sets APPROVED. It also acts as "unblock".
  - `block` sets approval BLOCKED. This is reversible.
  - `deprovision` sets status DEPROVISIONED. This is terminal, and no un-deprovision exists.
  - `block` and `deprovision` disconnect sessions and publish a `BlockedSessionEvent`. The only listener lives on `HttpMcpAuditFilter`, which is created with `new` and so is probably never registered (inferred) (protocol/mcp/transport/HttpTransportConfig.java:145-148; HttpMcpAuditFilter.java:881-886).
  - The admin mutators load rows with `findById` and **do not check the tenant**.

### 7.2 Humans and NHIs
- **Tables** `gateway_human_users` and `gateway_nhi_registry` (agentRegistry/entity/GatewayHumanUserEntity.java:16-104; GatewayNhiEntity.java:16-95)
  - Unique on (`idp_subject`, tenant), but lookups use `findByIdpSubject`, which is not tenant-scoped.
  - Store roles, `custom_claims`, `last_jwt_claims`, block metadata and counters.
- **Status** is PENDING, ACTIVE or BLOCKED. New rows start PENDING (the builder default is ACTIVE).
- **Profile refresh**:
  - The human profile updates only from non-delegated tokens. A JWT carrying `act` or `act_chain` refreshes only `lastSeen` and IP (AgentRegistryService.java:935-951).
  - The NHI profile is refreshed on every discovery.
- **Status enforcement**:
  - Happens **only on `/mcp`** (door filter).
  - The status caches store hits only. Every request for a subject with no human row (and the reverse for NHIs) runs a DB query (AgentRegistryService.java:491-513).
- **Admin-only analytics** (agentRegistry/service/HumanUserService.java:287-498; NhiService is a near-duplicate):
  - A heuristic risk score made of 6 signals: session frequency, unique tools, error rate, off-hours (gateway clock), PDP denial rate, new tools. It is capped at 100 and bucketed into LOW/MEDIUM/HIGH/CRITICAL.
  - Lineage from audit rows, keyed by session id. This means stateless and A2A traffic is not covered.
- `GatewayNhiEntity.description` is the only free-text field on an identity that looks like a purpose, and nothing ever writes it.

### 7.3 Sessions
- `gateway_agent_session` holds: agent FK, `session_id`, `auth_method`, `auth_identity` (JWT sub), connected/disconnected timestamps, `request_count`, `last_request_at`, status, `token_type`, `human_user_id`, `nhi_id`, `ip_address`. It has **no task, conversation, goal or intent column** (agentRegistry/entity/GatewayAgentSessionEntity.java:14-74).
- In-memory maps `sessionToAgentId`, `sessionToHumanUserId`, `sessionToNhiId` and `sessionToAuthIdentity` are not tenant-keyed (AgentRegistryService.java:570-595).
- Stateless requests only link in memory. A2A creates no session.

### 7.4 Capability registry and stored metadata
| Capability | Public name | Stored / indexed | Dropped |
|---|---|---|---|
| MCP tool | `<serverConfigName>_<toolName>` | name, `description` (TEXT), `inputSchema`. The schema is the Jackson serialization of the SDK `JsonSchema` record: type, properties, required, additionalProperties, `$defs`, definitions. | `title`, `outputSchema`, **annotations** (readOnly/destructive/idempotent/openWorld hints), `_meta`, and top-level schema title/description |
| MCP prompt | `<server>_<prompt>` | name, description, `arguments` JSON (names and descriptions) | |
| MCP resource | `<server>_<name>` | uri, name, description, mimeType | resource *templates* are never ingested |
| A2A skill | `<agent>.<skillId>` | id, description (falls back to name) | tags, examples, input/output modes, card description; **no input schema** |

- Cites: protocol/mcp/capability/service/McpCapabilityRegistrar.java:168-264, :322-324; capabilityRegistry/model/CapabilityDescriptor.java:12-57; protocol/a2a/capability/A2aCapabilityRegistrar.java:38-48.
- **Storage**
  - The index is a **global** in-memory `ConcurrentHashMap` with no tenant dimension (capabilityRegistry/service/CapabilityRegistryService.java:30-72).
  - MCP metadata is persisted in `mcp_server`, `mcp_tool`, `mcp_resource` and `mcp_prompt`, and reloaded at `@PostConstruct` (McpCapabilityRegistrar.java:69-100).
  - A2A skills live in memory only.
- **Refresh on change**
  - Downstream `tools/list_changed` causes delete-then-reinsert. The inbound side diffs signatures and sends `list_changed`, but there is **no versioning, content diff record or approval step** for changed descriptions or schemas.
  - Only the tools change feed is subscribed. Prompts and resources are not (protocol/mcp/outbound/config/McpSessionManager.java:98-106, :332-377).
- Downstream descriptions are republished **verbatim** to agents in `tools/list`.

### 7.5 Capability profiles (allow-lists)
- **Tables** `agent_capability_profile`, `agent_capability_profile_rule` and `agent_capability_profile_assignment` (agentRegistry/entity/AgentCapabilityProfile*.java).
  - A rule names a server or A2A agent, a type (ALL, TOOL, PROMPT, RESOURCE or SKILL), a mode (INCLUDE_ALL, INCLUDE_ONLY or EXCLUDE), and a comma-separated list of **original** names.
  - The computed allow-sets hold **public** names.
- **Recomputation** (agentRegistry/service/AgentCapabilityFilterService.java:45-199)
  - In memory, at startup (`@Order(100)`, after the A2A reconcile at `@Order(10)`).
  - On `CapabilityRegistryChangedEvent`, which **only `McpSessionManager` publishes**, so A2A skill changes leave SKILL allow-sets stale.
  - On profile update, delete, assign or unassign. Creating a profile does not trigger it.
- **Enforcement** happens in two places. First, list filtering, on `/mcp` only and only when the agent resolves. Second, `HopOrchestrator` before the PDP.
  - A resolved agent with no profile is denied everything.
  - An **unresolved agent skips the gate** (fail-open) (orchestration/HopOrchestrator.java:258-273, :542-566).
- **Weaknesses**
  - An unknown rule type string throws inside the recompute (inferred).
  - `getAllProfiles()` is `findAll()` across all tenants.
  - `normalizeRuleType` rewrites the declared type of an INCLUDE_ONLY rule to the registry's actual kind (agentRegistry/service/CapabilityProfileService.java:493-522).
- The allow-list is purely name-based. It knows nothing about purpose or arguments.
- **Profile AI** (`CapabilityProfileChatService`): `claude-haiku-4-5`, admin-only, returns drafts that are not saved. The system prompt contains the global capability names, **every tenant's** profile names and descriptions, and human PII (agentRegistry/service/CapabilityProfileChatService.java:28-354).

### 7.6 MCP outbound (the gateway as MCP client)
- **Server configs** live in `gateway_server_config`, unique on (`server_name`, tenant) (protocol/mcp/outbound/service/ServerConfigService.java:29-33, :353-383, :453-719).
  - Values whose key contains auth, token, secret, key, password, bearer, credential, api-key, apikey, cookie or session are encrypted with AES-GCM (`ENCv1:`). This covers headers, URL query parameters and top-level `server_config` strings.
  - These values are masked when read and decrypted when connecting. `${env:VAR}` placeholders are resolved from the environment.
- **Sessions**: `McpSessionManager` keys sessions by server name, with no tenant. Connecting lists tools, resources and prompts, registers them, and publishes the change event. The auto-connect query at startup is not filtered by tenant (protocol/mcp/outbound/config/McpSessionManager.java:31, :58-181; protocol/mcp/outbound/config/McpClientInitializer.java:28-65).
- **Transport**: `HttpMcpTransport` is hand-rolled (protocol/mcp/outbound/config/HttpMcpTransport.java:64-196).
  - Connect timeout is the config timeout (default 30 s). Read timeout is 0.
  - The SDK `McpSyncClient` default of 20 s is the only overall bound.
  - Override headers **replace** the config headers rather than merging with them.
  - An HTTP 4xx/5xx does not mark the connection as disconnected.
- **Health check** is passive: it only checks `client.isInitialized()`. `ws.gateway.southbound.auto-reconnect` is bound but never read (protocol/mcp/outbound/health/WsClientHealthCheckService.java:62-101).
- **Bypass**: `POST /api/mcp/servers/{server}/tools/{tool}` calls a downstream tool **directly**, skipping the spine, the PDP and the profiles. The dashboard's Playground uses this endpoint (protocol/mcp/outbound/controller/McpClientController.java:76-94; /Users/amitprakash/Desktop/WS Apps/ws-gateway-dashboard/js/playground.js:4-5).
- The STS OBO is **never** attached to MCP downstream calls. MCP servers never see the act_chain.

---

## 8. Egress post-processor

### 8.1 Pipeline
- **Trigger**: `HopOrchestrator.fireEgress` runs after every successful TOOL, SKILL, PROMPT or RESOURCE hop, and skips error results (orchestration/HopOrchestrator.java:144-182, :466, :759, :1023, :1281).
  - Because MCP `isError` is dropped earlier (§3.4 step 18), **downstream tool errors are classified as normal responses** (inferred).
  - Before enqueueing, it resolves the tenant and, for SKILL hops, runs a DB lookup, both synchronously on the request thread.
- **`EgressContext`** (postprocessor/model/EgressContext.java:20-40)
  - Carries: tenant, correlationId, traceId, protocol, capability type and name, producer, consumer, consumerAgentId, producer kind and ids, root principal kind/id/name/verified.
  - Does **not** carry: request arguments, prompt text, sessionId, requestId, tool description, or act_chain entries beyond the root.
- **`classifyAsync`** runs `@Async("auditExecutor")` (postprocessor/service/EgressClassificationService.java:49-106; audit/config/AuditAsyncConfig.java:17-28).
  - It is idempotent, via an application-level check-then-insert on (tenant, correlationId).
  - It is fail-open: errors are logged at WARN and no row is written.
  - When the queue is full the task is dropped, with only an ERROR log.
- **`EgressClassifier.classify(text, RulePolicy)`** is pure and synchronous (postprocessor/classifier/EgressClassifier.java:32, :40-132; postprocessor/classifier/ContextScorer.java:18-47). It runs:
  1. built-in and extra recognizers;
  2. skipping of disabled matchers;
  3. `prompt_injection`, which sets a flag and continues;
  4. OVERRIDE rules, which remap the sensitivity floor and categories;
  5. `ContextScorer`, which adds +0.35 (capped at 1.0) when a keyword for the matcher's context key appears within ±32 chars;
  6. a **0.60 gate**;
  7. output: sorted categories, max-floor sensitivity, and detector evidence (count, matcher type, confidence; never the matched value).

  It has **no size cap and no regex timeout**.
- **Content scanned** (orchestration/adapter/McpAdapter.java:107-126, :156-170, :206-220; orchestration/adapter/A2aAdapter.java:191-211)
  - MCP: `TextContent` blocks only, so images, audio and embedded resources are skipped.
  - Prompts: the description plus text messages.
  - Resources: `TextResourceContents` only, so blobs are skipped.
  - A2A: message and status text parts only. Data parts, file parts and Task artifacts are skipped.

### 8.2 Built-in recognizers (postprocessor/classifier/BuiltInRecognizers.java:26-202)
Sensitivity ladder: PUBLIC < INTERNAL < CONFIDENTIAL < RESTRICTED (postprocessor/classifier/Sensitivity.java).

| Matcher | Notes (behaviour pinned by EgressClassifierTest) |
|---|---|
| email | PII / CONFIDENTIAL, base 0.60. Example and placeholder domains are suppressed. |
| ssn | PII / RESTRICTED. Structural check only (area 000/666/9xx rejected); no checksum. |
| credit_card | Luhn check. Emits both FINANCIAL and PII / RESTRICTED. 23 processor test PANs are suppressed. |
| iban | mod-97 check. FINANCIAL / RESTRICTED. |
| ipv4 | NETWORK / INTERNAL, base 0.60. RFC 5737 doc IPs are suppressed. Private and public addresses are treated the same (`DenyLists.isPrivateIp` is dead code). |
| jwt | SECRET, base 0.60, even if the token does not decode. |
| secrets group | aws_access_key, private_key, certificate, bearer_token, labelled_secret |
| vendor tokens | google_api_key, github_token, slack_token, stripe_key, prefixed_key |
| high_entropy | Base 0.50, so it passes the gate only when a `secret` keyword is nearby. |
| **prompt_injection** | One case-insensitive **English** regex covering 5 phrase families: "ignore (all/the) previous/prior/above instructions", "disregard ... previous/prior/above/system", "you are now", "system prompt", "reveal (your) (system) prompt". It records at most one match, with confidence 1.0. It **sets `injectionDetected` only**: no category, no sensitivity change. Generic phrases such as "you are now connected" trigger it (inferred). DISABLE can turn it off; OVERRIDE cannot change it. |

### 8.3 Rules and templates
- **`data_tag_rule`** rows have these types (postprocessor/entity/DataTagRuleEntity.java:30-113; postprocessor/service/ClassifierRuleService.java:34-177):
  - CUSTOM: REGEX, or KEYWORDS compiled to a whole-word, case-insensitive, `Pattern.quote` alternation. Detector id is `rule:<name>`. Default confidence is 0.90. A rule with no categories gets the category `CUSTOM`.
  - DISABLE: turns a built-in off.
  - OVERRIDE: remaps a built-in's floor and categories.

  The compiled tenant policy is cached **per node** for 30 s. Writes invalidate only the local node.
- **Templates**: a catalog maintained in code with 14 templates in 5 packs (Healthcare 4, Finance 3, General/GDPR 3, Government 2, Legal 2), 11 REGEX and 3 KEYWORDS. They install as CUSTOM rules with `source_template_id` set (postprocessor/model/RuleTemplateCatalog.java:24-96).
- **Rule AI** (`RuleAssistantService`): `claude-haiku-4-5`, max_tokens 1500, synchronous, 10 s / 30 s timeouts. It only drafts rules; it never saves them. Its prompt includes tenant category and producer aggregates. Starter suggestions are cached for 5 min per tenant (postprocessor/service/RuleAssistantService.java:43-412).

### 8.4 Persistence (`gateway_response_classification`, postprocessor/entity/GatewayResponseClassificationEntity.java:29-220)
- **Columns written**: tenant, correlation_id, trace_id, protocol, capability type/name, producer/consumer and their ids, root principal kind/id/name/verified, `data_categories` jsonb, sensitivity, `volume_bytes`, `injection_detected`, detectors jsonb, `classifier_version` v1, `duration_ms`, `classified_at`.
- **Hard-coded on every row**: `direction=RESPONSE`, `redacted=false`, `status=CLASSIFIED`, `is_terminal_egress=false`, **`enforcement_mode=OBSERVE`**, **`action_taken=OBSERVED`**.
- **Reserved but never written**: `egress_policy_id`, `enforcement_reason`, `provenance_categories` (upstream taint), `record_count`, `attributes`.
- There is **no `session_id` column**.
- The unique constraint is on `source_event_id`, which the live path always leaves null.

### 8.5 Observe-only status versus the plan
- The plan puts V2 enforcement (SHADOW/LIVE at the terminal egress) and Phase-3 request-side argument inspection in the future. **Neither exists** (repo:docs/features/post-processor-egress-governance-plan.md:18-20, :56-70).
- The plan's rule is that anything able to block must be inline, deterministic and fail-closed, while LLM signals are async and never block (:26-31). That is the plan's stated position, not code.
- CISO coverage queries count rows where `egress_policy_id IS NOT NULL`, so today they always report 0 covered (inferred) (postprocessor/repository/GatewayResponseClassificationRepository.java:59-97).
- The entity Javadoc says "raw payload redacted before audit". That is stale: raw responses are kept in audit, and reprocessing depends on them.

### 8.6 Reprocess and insights
- **Reprocess** re-classifies `payload.toString()` from retained audit rows: `CLIENT_TOOL_INVOCATION`, `CLIENT_RESOURCE_READ`, and `ORCHESTRATION_RESPONSE_RETURNED` for SKILL/PROMPT (postprocessor/service/PostProcessorReprocessService.java:51-227).
  - The text differs from the live path: it is escaped JSON, and it includes non-text parts.
  - It rewrites `classified_at`, which can create or hide drift.
  - It is capped at 500 rows per run.
- **Insights** are computed from SQL at read time: per-capability fingerprints, producer→consumer sharing edges, 24 h drift (recent peak above baseline peak), and per-entity peak sensitivity. **None of them is consulted at decision time** (postprocessor/service/PostProcessorInsightsService.java:40-174).

---

## 9. Audit, traces and governance surfaces

### 9.1 The two ledgers
| Table | Content | Key columns |
|---|---|---|
| `ws_agentic_security.gateway_audit_log` | Timeline of every event type. `AuditEventType` has **88** values, of which 9 are never emitted. | tenant, protocol (default `MCP`), event_type/module/status/severity, `correlation_id`, `trace_id`, `session_id`, `request_id`, `event_sequence` (per leg), agent/identity columns, `pdp_decision`, capability columns, `request_payload`/`response_payload`/`error_data` JSONB, `duration_ms`, `timestamp` (the caller's firedAt). **Single-column indexes only**: no tenant, composite or GIN index. |
| `ws_agentic_security.pdp_audit_log` | The authoritative decision ledger | tenant, event_type, `correlation_id`, `pdp_subject`, `pdp_resource`, `pdp_action`, **`pdp_context` JSONB** (the full request on REQUESTED rows, the result on RENDERED rows), `pdp_decision`, `pdp_policy_id`, `pdp_reason`, `timestamp` (@CreationTimestamp, i.e. **async write time**). **No `trace_id` and no `session_id` column.** |

- Cites: audit/entity/GatewayAuditLog.java:20-193; audit/entity/PdpAuditLog.java:18-104; audit/constants/AuditEventType.java:3-108.

### 9.2 How writes happen
- Almost every writer is `@Async("auditExecutor")`: a pool of core 4 / max 16 threads with a queue of 2000. **When the queue is full, rows are dropped** with only an ERROR log (audit/config/AuditAsyncConfig.java:17-28).
- `persist()` stamps: timestamp if null, traceId from MDC, identity from `sessionIdentityCache`, and tenant (cache → `TenantContext` → MDC `wsTenant` → `"system"`). It stamps protocol from MDC. It swallows all exceptions (audit/service/GatewayAuditService.java:1949-2029).
- Synchronous exceptions: `auditAgentApproved`, `auditAgentBlocked`, `auditAgentDeprovisioned` and `auditAgentConnectionRejected` (the last runs on the door filter's thread), plus the `*DisconnectedSync` variants (:144, :559, :1410-1520).
- One successful MCP tool call writes these rows:
  - `OAUTH2_AUTH_SUCCESS` (first call per session only);
  - `CAPABILITY_ACCESS_GRANTED`;
  - `ORCHESTRATION_TOOL_EXTRACTED`;
  - `ORCHESTRATION_REGISTRY_LOOKUP`;
  - `PDP_EVALUATION_REQUESTED` ×2 tables;
  - `PDP_DECISION_RENDERED` ×2 tables;
  - `STS_TOKEN_MINTED`;
  - `CLIENT_TOOL_INVOCATION`;
  - `ORCHESTRATION_RESPONSE_RETURNED`;
  - plus one classification row and async counter updates.

### 9.3 What is stored that matters for intent
- The PDP request JSON: sanitized arguments, including the A2A `input` text (≤2000 chars per top-level string), actChain, JWT custom claims and custom attributes (GatewayAuditService.java:1027-1066).
- `CLIENT_TOOL_INVOCATION`: **full, untruncated MCP arguments and the full response content** (:306-336).
- `ORCHESTRATION_RESPONSE_RETURNED`: full response text for SKILL/PROMPT only, and only when non-blank. `protocol_method` is hard-coded to `tools/call` for every type (:835-869).
- STS receipt: act_chain, actor, trace_id, corr_id, jti, scope (:899-949).
- `OAUTH2_AUTH_SUCCESS`: the **raw JWT claims**. `SERVER_NOTIFICATION_RECEIVED`: params. `SERVER_SESSION_INITIALIZED`: clientInfo and capabilities. `SERVER_REQUEST_REJECTED`: the whole JSON-RPC request.
- LLM chat requests and replies, truncated to 500 characters.
- Nothing prunes either table. No retention or purge job exists (audit/repository/*).

### 9.4 Correlation
- **traceId** is shared across hops through the OBO `trace_id` claim.
- **correlationId** is per leg, and **eventSequence** restarts at 1 on each leg. `getTraceChain` sorts by eventSequence first, so rows from different legs interleave (audit/service/AuditQueryService.java:167-199).
- **requestId** is the A2A messageId (null on MCP HTTP). **sessionId** is the MCP session or the A2A contextId.
- The **parent→child link is not stored anywhere**. The child's OBO `corr_id` equals the parent's correlationId, but nobody reads it. `TraceGraph` infers edges by name, treating "a target that is also a caller" as an edge (AuditQueryService.java:498-572).
- `CLIENT_*` rows carry the gateway's *outbound* McpSession id, so they do not group with the agent's session (protocol/mcp/outbound/config/McpSession.java:31).
- MDC `protocol` is set only after the PDP runs, so pre-PDP rows on A2A legs are stamped `MCP` (orchestration/HopOrchestrator.java:369-370).

### 9.5 Query views and graphs
- **`/api/admin/audit/*`** (12 GET endpoints): logs (filtered, paged, free-text over 10 columns but never over payloads), correlation chain, OBO receipt, trace chain, trace graph, agent activities and summary, identity graph, stats, timeline, filters (audit/controller/AuditController.java:36-190).
- **Tenant scoping gap**: correlation, OBO receipt, trace, trace graph and activities queries have **no tenant predicate** (AuditQueryService.java:102-282).
- **`/api/admin/access-graph`** (accessgraph/service/AccessGraphService.java:55-292) is read-only and plays no part in any decision. It combines:
  - observed actor→agent→tool edges from audit;
  - policy entitlements from `BlastRadiusService` (N+1 queries, one per agent);
  - DLP sensitivity;
  - gap detection (OVER_PRIVILEGE, UNGOVERNED_USE);
  - risk bands propagated upward.

### 9.6 CISO and compliance (read-only reporting)
- **CISO** (`/api/admin/ciso/*`, ciso/controller/CisoController.java:46-292) covers:
  - dashboard widgets: overview, priority actions (with a template-generated NL `assistantSeed`), traffic, hotspots, chains, top tools;
  - a posture scorecard, weighted 40/25/20/15;
  - accountability: actChain parsed from up to 20k ledger rows, labelled DIRECT (depth ≤2), DELEGATED (≥3) or UNROOTED;
  - blast radius;
  - a point-in-time replay, which is "observed, not declared" because there is no policy versioning (docs/policy-versioning-design.md:1-4);
  - activity trails with CSV export.
- **Compliance** (`/api/admin/compliance/*`) is a standalone module with duplicated queries. It produces SOC 2 and SOX packs, with 14 `dataclass.*` metrics fed by the post-processor, via `/report/{framework}` (compliance/controller/ComplianceController.java:20-99; repo:src/main/resources/compliance/*.json).
  - The legacy `ciso` compliance copy cannot render SOX.
  - "Attributed" metrics count DEFAULT_DENY markers as policy-governed.
- **Ops dashboard** (admin/DashboardController.java:26-269): `/api/admin/dashboard/{summary,in-flight,health,pdp}`. The in-flight view exposes the tool arguments of every tenant.

### 9.7 SIEM
- **None.** A repo-wide search for siem, cef, syslog, splunk, kafka, otlp or webhook found no export code. The only outputs are CSV (`?format=csv`) on the CISO and compliance endpoints, and a single STDERR console log appender whose pattern includes `traceId` and `correlationId` (repo:src/main/resources/logback.xml:1-12).

---

## 10. Platform

| Aspect | Fact |
|---|---|
| Build | Maven (wrapper 3.3.2 / Maven 3.9.9). Parent is `spring-boot-starter-parent` 3.3.4, Java 17. `spring-boot-maven-plugin` builds a fat jar with main class `com.ws.AzureAdWsIntegrationApplication` (repo:pom.xml:8, :30, :423-439). The pom description still reads "Demo project for azure ad integration". |
| Key dependencies | MCP SDK `mcp-bom` 0.12.1. a2a-java 1.0.0.Final with Gson 2.11.0. nimbus-jose-jwt 9.37.3. PostgreSQL driver 42.7.4. spring-boot-starter-data-jpa. springdoc 2.5.0. jackson-dataformat-yaml. Legacy-only: Azure resourcemanager, msgraph, k8s client-java 22, BouncyCastle 1.82 (repo:pom.xml:145-191, :253-418). **No Cedar library, no AI or ML SDK, no Micrometer.** |
| Runtime libraries | tomcat-embed-core 10.1.30, reactor-core 3.6.10, HikariCP 5.1.0 (from `BOOT-INF/lib` in repo:target/AzureAdWsIntegration-0.0.1-SNAPSHOT.jar) |
| Database | PostgreSQL `ws_local`, `currentSchema=ws_agentic_security`, `stringtype=unspecified` (lets JSONB bind from strings). `ddl-auto: update`, so there is no Flyway or Liquibase, only hand-run SQL in `repo:docs/migrations`. Hibernate `default_schema` is `azure_test`, but the gateway entities pin their schema explicitly (repo:src/main/resources/application.yml:58-72). |
| Tenancy | Row-level only; see §5.7. |
| Co-hosted modules | `@SpringBootApplication` in package `com.ws` scans every legacy module into the same JVM: 96 entities in total, 24 of them gateway entities. Legacy controllers (`/api/azure*`, `/api/k8-resources/`, `/api/pdp/`, and others) sit behind the same `permitAll` chain. The legacy OPA PDP calls `localhost:8181` and is not wired to the gateway. `spring.main.allow-bean-definition-overriding=true` can hide bean collisions (application.yml:85). |
| Deployment | The repo has no Dockerfile, compose, k8s or helm files. The artifact is a jar run with `mvnw`. The sample-agents repo does have a `compose.yaml`. |
| Threads | Tomcat defaults: `threads.max=200`, `max-connections=8192`, `accept-count=100`. Reactor `boundedElastic` = 10 × cores (120 on the 12-core dev Mac), queue 100000. Hikari pool 10, connection timeout 30 s. `auditExecutor` 4/16/2000. One default Spring scheduler thread runs **7 `@Scheduled` jobs**, including the legacy K8 job every 20 s. There is also a single-thread stateless-eviction scheduler and daemon SSE-reader threads. **No tuning properties are set.** `spring.jpa.open-in-view` is left at its default of true, which may hold a DB connection across blocked A2A calls (unverified). |
| Crypto | `SecretCryptoService` uses AES/GCM/NoPadding with a 12-byte IV and a 128-bit tag, output prefixed `ENCv1:`. The key is an unsalted SHA-256 of `ws.gateway.server-config-encryption.key`, which **falls back to the compiled-in `Constant.ENCRYPTION_KEY`** when unset. The yml default is empty (common/crypto/SecretCryptoService.java:22-45, :109-117; repo:src/main/resources/application.yml:147-148). |
| Logging | STDERR console only (logback.xml). The audit package logs at DEBUG and `io.modelcontextprotocol` at DEBUG (application.yml:115-121). `logging.level` nested under `spring:` has no effect (application.yml:74-78). |
| CORS | `/api/**` allows origins `*` with all headers and methods (security/CorsConfig.java:19-24). |
| Committed secrets | `application.yml` contains literal values for: `spring.cloud.azure.active-directory.client-secret` (:47), `spring.datasource.username/password` (:60-61), `github.token` (:113), and the **default of `ws.gateway.pdp.anthropic-api-key`** (:150). Because of that default, the LLM assistants are active out of the box. The sample-agents launcher also embeds a key-like literal (/Users/amitprakash/Desktop/WS Apps/a2a-sample-agents/start-agents.sh:17). **These values should be rotated.** They are not reproduced in this document. |

**Gateway config keys (names only)**
| Key | Meaning / default |
|---|---|
| `ws.gateway.transport` | `http` (default) or `stdio` |
| `ws.gateway.auth.mode` | env `AUTH_MODE`, default `none`. Overridden by an enabled DB `gateway_auth_config` row. |
| `ws.gateway.auth.config-poll-interval-ms` | 60000 |
| `ws.gateway.auth.token-classification.{mode, cache-ttl, introspection-uri, introspection-client-id, introspection-client-secret, introspection-timeout-ms}` | mode defaults to `jwt-signals`. `cache-ttl` and `introspection-timeout-ms` are never read. |
| `spring.security.oauth2.resourceserver.jwt.{issuer-uri, jwk-set-uri}` | Env fallback for the IdP |
| `ws.gateway.session.{idle-timeout-minutes, reaper-interval-seconds, reaper-enabled}` | yml 10 / 30 / true. Java defaults 30 / 60. |
| `ws.gateway.southbound.{health-check-interval-seconds, health-check-enabled, auto-reconnect, max-reconnect-attempts}` | `auto-reconnect` is never read |
| `ws.gateway.stateless.identity-evict-grace-seconds` | 60 |
| `ws.gateway.sts.issuer-base` | `https://gateway.local` (code default only) |
| `ws.sts.key.grace-window`, `ws.sts.key.purge-interval-ms`, `ws.sts.revocation.purge-interval-ms`, `ws.sts.auto-rotate.check-interval-ms` | PT1H, 1 h, 1 h, 24 h (code defaults) |
| `ws.gateway.policy.seed-default-lineage` | true |
| `ws.a2a.card.{name, description, version, url}`, `ws.a2a.outbound.timeout-seconds` | Code defaults. `timeout-seconds` is 120. |
| `ws.gateway.pdp.anthropic-api-key` (env `WS_GATEWAY_ANTHROPIC_API_KEY`) | Shared by all three LLM assistants |
| `ws.gateway.server-config-encryption.key` | AES key material; see Crypto above |

---

## 11. LLM usage today

The gateway's request path makes **no LLM, ML or model-based classifier call** in the spine, adapters, PDP, STS or egress classifier. The three LLM call sites in the gateway are all admin-time authoring assistants. Each one calls Anthropic directly using raw `java.net.http`.

| Call site | Model and parameters | Purpose and trigger | Sync/async, timeouts | Data sent to Anthropic |
|---|---|---|---|---|
| `pdp/service/PolicyLlmService.java:139-177` | `https://api.anthropic.com/v1/messages`, `claude-haiku-4-5` (hard-coded), anthropic-version 2023-06-01, max_tokens 2048 | Turns natural language into a Cedar-subset policy. Called from `POST /api/admin/policies/chat` and `/chat/save`; `/chat/save` saves the result **enabled**. | Synchronous on the servlet thread. 10 s connect, 30 s request, no retry. | Admin prompt and history. Live metadata: agents; human users with **name, email, roles, custom claims** and block reasons; NHIs; server, tool, prompt and resource names; policy names; custom-attribute definitions. Cached 60 s under **one global key**. Prompt is logged at INFO. Audited truncated to 500 chars. |
| `agentRegistry/service/CapabilityProfileChatService.java:64-121` | same endpoint and model, max_tokens 2048 | Turns natural language into a capability-profile JSON draft. Called from `POST /api/admin/capability-profiles/chat`. Nothing is saved. | Synchronous. 10 s / 30 s. Not audited. | Global capability names; **every tenant's** profile names and descriptions; tenant agents; humans (PII); NHIs |
| `postprocessor/service/RuleAssistantService.java:166-190` | same endpoint and model, max_tokens 1500 | Turns natural language into an egress rule draft, and produces 3 starter suggestions (cached 5 min per tenant). Called from `/api/admin/post-processor/rules/chat*`. Nothing is saved. | Synchronous. 10 s / 30 s. Not audited. | Built-in detector list; the tenant's rule names; category and producer aggregates; up to 15 sensitive capability rows |

- All three read the key `ws.gateway.pdp.anthropic-api-key`. Because `application.yml` commits a non-empty default for it, `isLlmAvailable()` is true out of the box. The endpoints sit under the unauthenticated `/api/admin/**` path (pdp/service/PolicyLlmService.java:54, :88-90; repo:src/main/resources/application.yml:150).
- Measured `PDP_LLM_CHAT_COMPLETED` latency: n=7, p50 2642 ms, max 4072 ms (gap-fill).
- **LLMs outside the gateway** (agent side, not governed by the gateway's config):
  - Sample agents: each agent runs an Anthropic tool loop via `anthropic.AsyncAnthropic`. Model comes from `AGENT_MODEL`: code default `claude-sonnet-5`, `start-agents.sh` default `claude-haiku-4-5`. max_tokens 2048, at most 4 iterations. No temperature is set, although the README says temperature 0 (/Users/amitprakash/Desktop/WS Apps/a2a-sample-agents/agent_brain.py:29-30, :96-106, :185-243).
  - Agent console: its own Claude model writes the A2A `text` (/Users/amitprakash/Desktop/WS Apps/ws-agentic-console/src/llm.js:100-153). That model's id was not recorded.

---

## 12. What callers actually send

### 12.1 The real front door: agent console (`ws-agentic-console`, commit 6bf60b9, clean tree)
- Body sent to `/a2a` (/Users/amitprakash/Desktop/WS Apps/ws-agentic-console/src/a2aClient.js:121-134):
  `{jsonrpc, id, method: "message/send", params: {message: {kind: "message", role: "user", messageId, parts: [{kind: "text", text}], metadata: {skillId}}}}`
- **Not sent**: `contextId`, `taskId`, `referenceTaskIds`, `extensions`, `params.metadata`, `params.configuration`. The SDK `Message` type has no history field anyway.
- Headers: `Authorization` carries the signed-in user's Keycloak JWT. `X-Trace-Id` is minted **once per chat turn** and shared by every A2A call in that turn (a2aClient.js:140-144; llm.js:106, :147).
- **The text is written by the console's own Claude model** as `tu.input.text` (llm.js:147). The tool schema asks the model for "the natural-language request" (a2aClient.js:84-94). The human's chat history stays in the console (public/app.js:544-554 → server.js:244-251 → llm.js:100) and **never reaches the gateway**.
  - Example observed hop-1 text: "Analyze Apple stock (AAPL) - provide current price, fundamentals, recent news, and investment analysis". This is a paraphrase written by the model.
- The console ignores the contextId the gateway returns (a2aClient.js:99-112).
- The console's MCP calls send no trace header and no `_meta` (mcpClient.js:130-134).
- Runtime corroboration: all 63 A2A skill decisions in the local DB have `session_id` NULL, i.e. no contextId was ever sent.

### 12.2 Sample agents (`/Users/amitprakash/Desktop/WS Apps/a2a-sample-agents`)
- Four agents share `agent_server.py` and `agent_brain.py`:
  - advisor: port 11001, skill `analyze`, orchestrator;
  - market-data: port 11002, `quote`;
  - fundamentals: port 11003, `earnings`;
  - news: port 11004, `sentiment`.
- The legacy billing/refund pair is broken: it imports the removed `_call_downstream` (billing_agent.py:25).
- **Inbound handling**: the executor reads the inbound text, `metadata.skillId` and the inbound `Authorization` header, then runs the LLM loop (agent_server.py:27-42).
- **LLM tool menu**: declared A2A skills become `call_<skill>` with one required `text` string argument. MCP tools come from gateway `tools/list`, cached once per process and filtered by profile (agent_brain.py:41-71, :128-151).
- **A2A calls**: the envelope has one text part (**written by the LLM**) plus `metadata.skillId`, and no contextId (a2a_wire.py:12-26).
  - Headers: `Authorization` is the inbound OBO, or in `AGENT_AUTONOMOUS=1` mode the agent's own token. `X-Agent-Assertion` is the agent's own Keycloak client-credentials token, cached until 30 s before expiry. Both headers are conditional (agent_identity.py:40-89; agent_brain.py:109-125).
  - The LLM sees only `"HTTP <code>: "` plus the first 600 characters of the raw JSON-RPC response (agent_brain.py:125).
- **MCP calls**: each call opens a new streamable-HTTP session (`initialize` with `clientInfo.name` = agent name, or `SPOOF_CLIENTINFO_NAME`), then `call_tool(name, args)` with LLM-chosen structured arguments (e.g. `symbol`, `tickers`) and **no `_meta`**. A client-side guard skips the call if a required argument or `symbol` is blank (mcp_tools.py:36-139; agent_brain.py:79-94, :216-230).
- **Timeouts**: agent→gateway A2A 120 s, MCP 30 s, Keycloak 10 s. `run_autonomous.py` uses 180 s and `call_gateway.py` 30 s.
- **Autonomous trigger**: `run_autonomous.py` sends a natural-language brief under a client-credentials token, with no human involved (run_autonomous.py:37-78).

### 12.3 Admin dashboard (`/Users/amitprakash/Desktop/WS Apps/ws-gateway-dashboard`)
- A vanilla-JS single-page app with 14 pages. It calls roughly 141 `/api/admin/*` and `/api/mcp/*` path templates **with only `X-WS-Tenant` (hard-coded `amitdev.local`) and no Authorization header**. BASE is `http://localhost:9492` (js/api.js:8-26).
- It renders request and response payloads in Audit Log rows and `requestArgs` in In-Flight, so request text is visible to admins (js/audit.js:470-478; js/inflight.js:264-270).
- It hosts three chat assistants that call the gateway LLM endpoints (§11).
- The Playground calls the direct-tool bypass endpoint.
- CISO priority actions and access-graph fixes pre-fill the policy assistant with a natural-language "seed" (js/cisoDashboard.js:219-241; js/accessGraph.js:266-274).

### 12.4 Does natural-language task text reach the gateway?
| Hop | Text the gateway sees | Whose words |
|---|---|---|
| Hop 1 A2A (console → advisor) | `arguments.input` | The **console LLM's** paraphrase. The human's chat is not forwarded. |
| Hop 1 A2A (`run_autonomous.py`) | `arguments.input` | The trigger prompt; no human involved |
| Hop ≥2 A2A (agent → agent) | `arguments.input` | The **delegating agent's LLM**. The root question is not carried. |
| MCP `tools/call` | none. Only structured arguments (e.g. `symbol`) | n/a |
| Responses (after the decision) | `fullText` | The downstream agent or tool |

---

## 13. Grounding facts for intent-aware authorization

Facts only. Every item is cited in this section or earlier.

### 13(a) Data related to purpose at each point on the request path
| Point on path | What is available there | Passed onward? |
|---|---|---|
| `HttpMcpAuditFilter`, before the SDK (`/mcp` only) | The **full raw JSON-RPC body** (params, arguments, `_meta`, clientInfo) plus all headers and `jwt.*` attributes (protocol/mcp/transport/HttpMcpAuditFilter.java:148-153) | Only `id` and `method` are read. `_meta` is never parsed. |
| `McpGatewayContextExtractor` (both HTTP MCP transports) | Headers (including any custom purpose header), claims, raw claims, trace id (McpGatewayContextExtractor.java:22-138) | Yes, as the request attribute bag |
| MCP SDK handler | `CallToolRequest.name/arguments/meta`, clientInfo | Only name and arguments (HttpMcpServerInitializer.java:225-229) |
| `A2aInboundController` between parse and `handle` | The **full `MessageSendParams`**: all parts, message and params metadata, contextId, taskId, messageId, raw body (A2aInboundController.java:82-145) | Only text→`input`, `metadata.arguments`, skillId, contextId, messageId |
| Spine, before the PDP (after registry lookup) | Hop (publicName, full untruncated args, traceId), RequestContext, **descriptor (description, inputSchema)**, act_chain, sessionId, correlationId (orchestration/HopOrchestrator.java:232-318, :581-610) | Descriptor, traceId and raw claims are **not** passed to the PDP |
| PDP request | Identity, action, names, sanitized args, act_chain, custom attributes (§6.5) | Evaluated |
| After dispatch | Response `fullText` | Only to async audit and classification |
| Registry, at registration time | MCP tool description and inputSchema; A2A skill description (§7.4) | Republished to agents; never evaluated |
| Initialize / agent row | clientInfo, client capabilities (feature flags) | Stored; no purpose field |

### 13(b) Exactly what the PDP can consume today
- Only the attributes in §6.5, and only through `==`, `!=`, integer comparisons, `like` (wildcard, case-sensitive), `.contains` (substring), and `&&`. There is no OR, NOT, regex or structured argument access (pdp/service/CedarPolicyEngine.java:96-122, :927-977).
- **Natural language reaches the PDP only as `context.argumentsFlat`.** This is one space-joined `k=v` string, top-level strings truncated at 2000 chars, key order non-deterministic. Only A2A hops carry natural language in it (`input=...`). Prompt and resource hops carry an empty string.
- The engine outputs only ALLOW or DENY, plus a reason string and matched ids. There are no obligations or advice.
- A new signal would have to arrive as a string, boolean or long in `context.*` (via custom attributes or a provider) or `principal.*`/`resource.*`.
- Today no policy references `argumentsFlat` (§6.9).

### 13(c) Seams where a signal could be computed or attached before the decision
- **`PolicyContextBuilder.CustomAttributeProvider`** (pdp/service/PolicyContextBuilder.java:24-29, :296-316)
  - A Spring SPI that receives (agentName, action, resourceName, serverName, **raw untruncated arguments**).
  - Its output is merged **last** into the Cedar context.
  - Runs synchronously. Exceptions are swallowed (fail-open for the attribute).
  - It has **zero implementations**, and it does **not** receive the `RequestContext`, traceId, claims or the descriptor.
- **DB-registered HEADER custom attributes** can turn a caller-supplied header into `context.<name>` without code changes. This works on MCP only, not A2A, and the header is self-asserted (pdp/service/CustomAttributeService.java:196-218).
- **`HopOrchestrator` between the registry lookup and `buildFor*`** has the descriptor, arguments, context and act_chain in scope. It is duplicated across 4 near-identical leg methods, each about 250 lines (orchestration/HopOrchestrator.java:306-324, :598-613, :896-902, :1158-1164).
- **`A2aInboundController`** is the last point where the full A2A message is in hand (protocol/a2a/inbound/A2aInboundController.java:124-145).
- **`HttpMcpAuditFilter`** is the only point where MCP `_meta` exists, and only on `/mcp`.
- **`ToolCallOrchestrator`** is the common point for all MCP transports (name, arguments, context) (protocol/mcp/inbound/ToolCallOrchestrator.java:36-41).

### 13(d) Latency on the hot path
Measured from 128 hops in 26 demo journeys, 2026-08-11 to 08-18, single user, from existing audit timestamps. Source: gap-fill read-only `SELECT`s on `ws_local.ws_agentic_security.gateway_audit_log` and `pdp_audit_log`. The stage gaps are real on-thread intervals, because each audit call passes a `firedAt` value captured on the request thread (audit/service/GatewayAuditService.java:1946-1949). Pool sizes come from library defaults: Tomcat 200 and Hikari 10 (spring-boot-autoconfigure 3.3.4, HikariCP 5.1.0), boundedElastic 10×cores (reactor-core 3.6.10).

| Stage | p50 | p95 | max |
|---|---|---|---|
| Pre-PDP build (TOOL_EXTRACTED → PDP request) | ~1.8 ms | 6–8 ms | 85 ms |
| PDP engine (`evaluationDurationMs`, n=497) | 0 ms | — (p99 1 ms) | 6 ms |
| Decision → OBO minted (connectivity + in-flight + RSA sign) | SKILL 11.3 / TOOL 10.1 ms | 18.7 / 14.5 ms | 541 / 95 ms |
| Downstream A2A skill | 6.9 s (per skill 3.9–10.7 s) | 20.0 s | 27.8 s |
| Downstream MCP tool | 1.4 s | 2.5 s | 5.1 s |
| Egress classifier, async (n=12) | 5 ms | 42 ms | 74 ms |
| Admin LLM policy generation (n=7) | 2.6 s | — | 4.1 s |

- Governance overhead is about 12–13 ms p50 per hop. The downstream call dominates.
- Everything is **synchronous and blocking**:
  - An A2A hop holds one Tomcat worker for its whole subtree.
  - An MCP hop holds one Tomcat worker plus one boundedElastic worker.
  - A parent blocks while its children run. Peak per demo journey is about 6 Tomcat plus 3 boundedElastic threads. About 33 concurrent journeys would exhaust 200 Tomcat workers (inferred).
  - No deadline propagates. Each A2A level has its own 120 s timeout and there is no child cancellation (orchestration/adapter/A2aAdapter.java:48-58).
- Synchronous DB reads before the PDP:
  - status lookups in the door filter;
  - `governanceDenial` fallback;
  - `resolveAgentIdByName` (A2A);
  - `ActChainBuilder.getAgent` (MCP);
  - `findAgentsByName` (always);
  - one query per AGENT_FIELD attribute;
  - the JWKS query for every inbound STS token.

  After the PDP: the MCP connectivity DB query, then the mint.
- The only async pool is `auditExecutor` (4 threads until 2000 queued, then drops). It is shared by audit writes, counters and the classifier. On MCP handler threads `TenantContext` is null.

### 13(e) Existing classification machinery
- **`EgressClassifier`** is deterministic: regex, checksum, entropy, plus a prompt-injection phrase regex. It exposes `classify(String, RulePolicy)` and a `Recognizer` plug-in interface documented as the seam for future ML recognizers (postprocessor/classifier/Recognizer.java:5-17).
  - It is used **only on responses, asynchronously, observe-only**.
  - It has no size cap or regex timeout, and has never been measured inline.
- **`TokenClassificationService`** is a rules-based classifier of *who* (human vs automated), not what or why (§5.4).
- **Admin-only heuristic risk score** for humans and NHIs (§7.2).
- **CISO accountability** classifies chains as DIRECT, DELEGATED or UNROOTED, again admin-only (§9.6).
- **No request-side content, intent or injection classifier exists.**

### 13(f) History available at decision time
- **In the request itself**: the inbound OBO's `act_chain` (all prior principals, identities only), `trace_id`, `corr_id` (the parent correlationId, never read), and `scope` (the parent's capability id, never read) (sts/service/StsService.java:78-105).
- **In memory**:
  - `InFlightRequestRegistry` keeps active entries keyed by correlationId, each with args truncated to 2000 chars, plus the last 50 completed entries gateway-wide. It has no traceId field and no get-by-id. The current call registers only *after* the PDP (orchestration/InFlightRequestRegistry.java:18-149).
  - While a child hop is being decided on the same JVM, the parent's entry (holding the parent's text) is present, keyed by the child's inbound `corr_id`. **Nothing looks it up.**
  - Per-session identity caches exist; there is no call history.
- **In the DB**:
  - `pdp_audit_log.pdp_context` holds the full sanitized request per correlationId, with no trace or session column.
  - `gateway_audit_log` holds rows by `trace_id`, `session_id` and `correlation_id`.
  - `gateway_response_classification` rows are keyed by trace and correlation id.
  - All of these are written async. Measured persist lag is p50 0.45 ms / p99 17 ms. In the demo, a parent's PDP row always existed before its child was decided (minimum gap 1029 ms, 97 inferred pairs), but that margin comes from LLM pacing and has not been tested under load.
- **Per-agent counters** (`totalRequests`, `firstSeenAt` and similar) are reachable via AGENT_FIELD custom attributes.
- The PDP itself **consults no history**.

### 13(g) What is NOT available today
- No purpose or intent field in any protocol envelope the gateway reads.
- No purpose claim in the OBO.
- No purpose column on agents, sessions or policies.
- MCP `_meta` is dropped. Tool/skill descriptions, input schemas and MCP tool annotations never reach the PDP. Annotations are not even stored.
- The root human question is never available to the gateway on the console path, and is not propagated on later hops.
- There is no parent→child link that anything reads, no per-session or per-chain history at decision time, and no conversation or task id: contextId is unused by current callers and not forwarded.
- There is no request-side classifier and no LLM on the request path. The engine has no string-similarity or semantic operators, and no obligations or advice.
- There is no verified approval status on MCP (always UNKNOWN), and no verified per-tenant isolation on the header-selected tenant path.

---

## 14. Gotchas, bugs and inconsistencies

**Security and fail-open behaviour**
1. The policy-head forms `principal in AgentGroup` / `resource in Server` are ignored, so the live `financial-desk-grant` permits every agent and resource when the root is verified (§6.9). Unrecognised condition fragments are dropped. `!` and `||` are silently mis-evaluated (§6.2).
2. `/api/admin/**` and `/api/mcp/**` are `permitAll`, and the tenant comes from a caller-chosen header. The admin actor comes from a spoofable `X-Admin-User` header on the human/NHI controllers (agentRegistry/controller/HumanUserController.java:146-152).
3. `X-WS-Tenant` overrides the verified `ws_tenant` claim and the issuer mapping on the data plane (security/TenantResolver.java:37-46).
4. `/stateless/mcp` has no sender constraint and no human/NHI gate. `/a2a` has no human/NHI gate, no `jti` revocation check, and agent status is effectively unenforced (§3.5, §4.8).
5. Custom attributes can overwrite built-in context keys. Their cache and admin CRUD are not tenant-scoped (§6.7).
6. The IdP decoder validates neither issuer nor audience (inferred) and trusts all TLS certificates. The STS decoder's issuer check can never fail, and it checks no audience (§5.2).
7. An X-Agent-Assertion is accepted if it is any valid IdP token with an azp, including a human token (§5.5).
8. The direct tool call `/api/mcp/servers/{s}/tools/{t}` bypasses the PDP and capability profiles (§7.6).
9. Secret literals are committed in `application.yml`. The encryption key falls back to a compiled constant. The raw `Authorization` header is stored in the MCP context bag, and `X-Agent-Assertion` is stored in `_httpHeaders` (§10; protocol/mcp/transport/McpGatewayContextExtractor.java:17-42).
10. The LLM assistants send tenant PII to Anthropic. The policy assistant's metadata cache is global, so one tenant's data could leak to another (inferred). Profile chat includes other tenants' profiles (§11).
11. `/chat/save` enables an LLM-generated policy immediately, with no approval step (pdp/controller/PolicyController.java:389-397).
12. In amitdev.local both DEFAULT lineage guardrails are disabled, probably by a direct DB write, even though the API forbids it (§6.9).

**Correctness**
13. On MCP, `approvalStatus` is always `UNKNOWN` and `identity_source` is always `KEYCLOAK`, because of the null `TenantContext` on boundedElastic (§6.4, §5.6).
14. A `resource == Tool::"x"` condition inside `when`/`unless` never matches (case-sensitivity bug), and the `/test` endpoint hides this (§6.2, §6.11).
15. Downstream MCP `isError` and `structuredContent` are dropped, so tool errors return success and are then classified (§3.4).
16. The spine never checks that the descriptor type matches the hop type. A `tools/call` on a SKILL name passes the PDP and the mint, then fails with ORCHESTRATION_FAILURE (orchestration/HopOrchestrator.java:292-309).
17. `OboIntegrityException` and `resolveAdapter` failures escape unshaped, probably as HTTP 500, with no audit row (§4.5).
18. `initialize` succeeds before a blocked or deprovisioned agent is detected; the block takes effect from the next request (§3.2).
19. After reaping, a session keeps working without its agent link, so filtering and profile checks are skipped (§3.8).
20. The reconnect purge is keyed by agent name, so it can expire another user's session on the same agent (inferred) (HttpMcpAuditFilter.java:639-653).
21. `metadata.arguments.input` overwrites the A2A text. `A2aRoleWire` rewrites any field named `role` inside arguments. Only the first text part of an A2A reply reaches the caller. Task artifacts are ignored (§4.2, §4.4).
22. Registry and directory are keyed by name with no tenant, and MCP and A2A names can collide (§4.7, §7.4).
23. SKILL allow-sets go stale after A2A ingestion because no change event is published (§7.5).
24. Revocations, key rotation and the policy and rule caches are per-JVM only. Other instances learn of revocations and rotations only on restart (§5.10, §5.11).
25. Public `/.well-known/sts/jwks.json` probably returns empty (§5.11).
26. The stateless session-revocation check is dead code. The stateless tenant ignores the `ws_tenant` claim (§3.5).
27. `updateAuthConfig` never applies a change to the JWKS URI alone. The 60 s poll includes disabled rows (§5.1, §5.2).

**Audit and observability**
28. Audit rows are dropped when the queue is full. Errors are swallowed (§9.2).
29. Pre-PDP audit rows on A2A legs are stamped `MCP`. `protocol_method` is always `tools/call`. The tools/list count is the unfiltered total (§9.3, §9.4, §3.3).
30. `eventSequence` restarts per leg, so trace chains interleave. `pdp_audit_log.timestamp` is the write time, not the decision time (§9.1, §9.4).
31. Several query views have no tenant predicate (§9.5).
32. The -32001 code collides with `REQUEST_TIMEOUT`. `SERVER_UNAVAILABLE` says "MCP server" even for A2A (§3.9).
33. "Attributed" metrics count `DEFAULT_DENY`. CISO egress coverage is always 0 (§6.13, §8.5).

**Docs versus code**
34. `repo:docs/features/policy-engine-reference.md` teaches ignored head forms and omits `skillInvocation`. The LLM prompt teaches that `AgentGroup::"APPROVED"` is an approval group. Neither matches the engine (§6.9, §6.12).
35. Other stale statements:
    - `repo:docs/others/mcp-concurrency-sample.md:5, :11, :13` says handlers run on the Tomcat thread, that there are "only two locks", and that there are "no DB waits".
    - `repo:docs/others/empty-hop-no-token.md:29-34` claims there are exactly two causes; there are more.
    - `repo:docs/others/stage-2-plan.md:33-35` says there is a "single global policy list"; policies are now per-tenant.
    - The post-processor entity Javadoc says payloads are "redacted".
    - The pom comment says the "a2a client" is used for outbound.
    - The A2A card Javadoc gives the wrong path.
    - Both `_meta` Javadocs describe it as read.
36. `docs/policy-versioning-design.md` is a proposal only. There is no versioned declared-policy state. A partial trail exists in create/update audit payloads, but seeded DEFAULT policies are never audited.

**Hygiene**
37. There are 203 plus 52 duplicate `* 2.java` files, which likely break a clean build (inferred). There is also a stray `.DS_Store` under `protocol/a2a/`.
38. `HumanUserService` and `NhiService` are near-duplicates. In both, the per-agent tool count in usage analytics sums across all agents (agentRegistry/service/HumanUserService.java:555-560).
39. Dead config and code: `ws.gateway.auth.mode` is injected into `HttpTransportConfig` but unused; `auto-reconnect` is never read; the `cache-ttl` and `introspection-timeout-ms` settings are never read; `DenyLists.isPrivateIp` is never called; `PolicyContextBuilder.sessionManager` is never used; `DelegatingJwtDecoder.setOnGracePeriodEndCallback` is never called.

---

## 15. Open questions still unresolved

1. Is `/api/admin/**` protected by anything outside this repo, such as an ingress or proxy? Is the header-only admin plane meant for production?
2. Is the gateway ever deployed as more than one instance? This decides whether the per-JVM caches (revocation, keys, policies, in-flight parent entries) are a real problem.
3. Does a clean build succeed with the `* 2.java` duplicates present, and are they present in CI?
4. Deployment CPU count, which sets boundedElastic = 10 × cores. Is `open-in-view` actually holding a Hikari connection across blocked A2A calls?
5. Behaviour under load: audit queue saturation, tail latency, and whether a fast non-LLM child can outrun its parent's ledger write.
6. JVM and host timezone for the PDP time attributes. Team memory says UTC; this is unverified in config.
7. The model id used by the console's LLM, and the envelopes sent by other front doors such as "Kore.ai Agent Console V1" and claude-desktop.
8. Whether the 20 s `McpSyncClient` timeout really bounds a hung read, given `readTimeout(0)`.
9. How the SDK handles JSON-RPC batch arrays on `/mcp`. The filter's method-based gates would see `""`.
10. The exact HTTP status and body returned when exceptions escape the A2A controller.
11. How the amitdev.local DEFAULT guardrails came to be disabled, and whether that is intended.
12. Whether any real caller sends MCP `_meta`, custom purpose headers, or A2A contextId, metadata or DataParts. None were seen in the console or sample agents.
13. Whether the 45 `"system"` pdp rows were evaluated against the amitdev.local slot or the null-tenant global set. The evidence points to the amitdev.local slot (inferred).
14. Whether `TokenClassificationService`, `TenantResolver`, `GatewayOAuth2Filter`, `AgentAssertionVerifier` and `MultiIssuerJwtDecoder` behave as read. They have no tests.

---

## Appendix A: What the verifiers corrected (brief)

| Original claim | Corrected fact |
|---|---|
| MCP handlers run on the Tomcat request thread | They run on Reactor `boundedElastic`; the servlet thread is parked in `block()`. |
| Introspection runs at initialize (3 s timeout) | It runs only when mode is `introspect` (default `jwt-signals`). Its RestTemplate has no timeout, and its cache is write-only. |
| Custom-attribute providers receive tool arguments | The SPI exists, but it has zero implementations. |
| `rawJwtClaims` carries an OBO `actor` claim | The STS mints no `actor`, `azp` or `client_id`. The `actor.*` fallback is dead. |
| `rawJwtClaims`, `act_chain` and `traceId` reach the PDP | Only `actChain` (set by the spine) reaches it. `jwtCustomClaims` is set but never read by the engine. |
| `X-Correlation-Id` is the spine correlation id | The spine mints its own per leg. The header only feeds custom attributes. |
| `GatewayOAuth2Filter` always sets `jwt.*` | It does so only in oauth2 mode. |
| BLOCKED is refused for every method | Only when a subject or session is present. A blocked agent still gets a successful `initialize`. |
| A2A failures always map to ORCHESTRATION_FAILURE | `IllegalArgumentException` raised before the adapter's try block maps to SERVER_UNAVAILABLE. |
| Governance denials on A2A always come back as FAILED Tasks | -33016, -32601 and -32602 are JSON-RPC errors. Integrity and adapter exceptions escape. |
| "Cedar" PDP | A regex subset. Operators: `==`, `!=`, `<`, `>`, `<=`, `>=`, `like`, `contains`, `in` (group/server) and entity equality. |
| The STS mint fails closed | It skips when the tenant is null or unknown, and it mints empty chains. |
| `updateLastActivity` is a synchronous DB write | It is `@Async`. |
| The RESOURCE leg rewrites the URI | The public name is looked up by exact URI match, so there is no practical rewrite. |
| The single-adapter fallback applies | Two adapters are registered, so it never fires. An unknown protocol throws outside the try. |
| The reaper disconnect is clean | The session stays live at the transport, without its agent link (inferred). |
| X-Agent-Assertion replaces roles and principal | Only `all_roles`, `realm_roles` and `groups` are replaced. `client_roles` and the principal are unchanged. |
| The trace is shared across legs | Only when propagated. MCP mints a new trace per HTTP request unless a header or claim supplies one. MCP checks header then claim; A2A checks claim then header. |
| The principal session id is null for A2A | It is the A2A `contextId` when the caller sends one. |
| 90 audit event types | 88, of which 9 are never emitted. |
| SDK timeout unknown / infinite read | The `McpSyncClient` default request timeout is 20 s (javap). |
| `docs/policy-versioning-design.md` is missing | It exists inside the gateway source root. |
| The template catalog's Government pack is KEYWORDS | Legal's `legal.privilege` is also KEYWORDS: 3 KEYWORDS templates, 11 REGEX. |
| Classifications are per-session | There is no `session_id` column. Only trace and correlation ids. |

## Appendix B: Test coverage map

All gateway tests are plain JUnit 5 + Mockito + AssertJ unit tests. **No test loads a Spring context, a database, HTTP, Testcontainers or MockMvc**, so no native SQL, controller or servlet filter is exercised. The only `@SpringBootTest` is an empty `contextLoads` for the whole app (repo:src/test/java/com/ws/AzureAdWsIntegrationAzureApplicationTests.java:6-11).

| Area | Tests (under repo:src/test/java/com/ws/wsAgenticSecurityGateway) | What they pin | Not covered |
|---|---|---|---|
| Spine | `orchestration/GovernedFlowCharacterizationTest`, `ToolCallOrchestratorCharacterizationTest` | Real ToolCallOrchestrator → HopOrchestrator → McpAdapter over a mocked MCP client. Tool happy path and deny paths. Fail-closed mint. Prompt/resource throw semantics. **PDP, context builder and act_chain are mocked.** | SKILL leg, PDP-exception catch, fireEgress, InFlightRequestRegistry, credential brokering |
| A2A | `orchestration/adapter/A2aAdapterTest` (real in-process HTTP server), `protocol/a2a/{capability,source,wire}/*Test` | Outbound wire shape and OBO bearer. Card→SKILL registration. URL-collision guard. Reconcile isolation. Role rewrite (String overloads only). | `A2aInboundController`, `A2aMessageMapper`, `A2aRequestContextFactory`, card service, admin controller, directory |
| MCP inbound | `protocol/mcp/inbound/McpRequestContextFactoryTest`, `wsServer/StatelessIdentityServiceTest`, `wsServer/transport/HttpTransportConfigTest` (the directory names do not match their packages) | Context mapping. Stateless bootstrap and eviction. Lenient parsing of initialize capabilities. | `HttpMcpAuditFilter` (all gates, list filter), `McpGatewayContextExtractor`, initializers, reaper, stdio |
| PDP | `pdp/service/CedarPolicyEngineTest`, `CedarPolicyEngineTenantPartitionTest`, `PolicyContextBuilderTest`, `PolicyServiceTest`, `PolicyActivityServiceTest` | Lineage forbids. Role ABAC. The absent-chain gap. Principal/resource extraction. `decidedBy` markers. Tenant isolation and lazy load. Verified client_id beats clientInfo.name. Guardrail seeding. | Argument sanitisation, custom attributes, head-clause AgentGroup/Server evaluation, dropped fragments, the `when`-clause entity case bug, effect-from-`@id`, `PolicyLlmService`, controllers |
| STS / identity | `sts/service/*Test` (StsService, HopTokenMinter(+Revocation), ActChainBuilder(+MultiHop), StsKey, StsRotation, StsRevocation, ScopeDeriver), `sts/model/{ActChain,OboInvariants}Test`, `security/{StsJwtDecoder,TokenClassificationService,TokenClassificationIdpCases,ProtocolRouteRegistry}Test`, `security/workload/JwtWorkloadIdentitySourceTest` | Exact claim set with real RSA (cnf not asserted). Fail-closed mint. Revoked session refused before tenant skip. Lineage variants. Invariants. Keys, rotation, retire. Revocation caches. 19 real-world IdP token shapes. | `GatewayOAuth2Filter`, `MultiIssuerJwtDecoder`, `DelegatingJwtDecoder`, `AgentAssertionVerifier`, `TenantResolver`, `TenantInterceptor`, `AuthConfigService`, STS controllers |
| Registry | `agentRegistry/service/{AgentRegistryService,CapabilityProfileService}Test`, `capabilityRegistry/service/CapabilityRegistryServiceTest`, `protocol/mcp/capability/service/McpCapabilityRegistrarCharacterizationTest`, `protocol/mcp/outbound/service/ServerConfigServiceUrlSecretTest` | Deprovision. `normalizeRuleType`. Index replace/evict/reload. URL secret encrypt, mask and decrypt. | `AgentCapabilityFilterService`, Human/NHI services, `CapabilityProfileChatService`, `McpSessionManager`, `HttpMcpTransport`, health check |
| Post-processor | `postprocessor/classifier/{EgressClassifier,RulePolicy}Test`, `model/RuleTemplateCatalogTest`, `service/{ClassifierRuleService,PostProcessorInsightsService,PostProcessorReprocessService}Test` | Every built-in recognizer and its suppressions. Prompt-injection flag. Rule layering. Keyword matching. Templates. Insights math. Reprocess source selection. | `EgressClassificationService` (async, idempotency, fail-open), `RuleAssistantService`, `PostProcessorRuleService`, controllers |
| Audit / governance | `audit/service/AuditQueryServiceTest`, `accessgraph/service/AccessGraphServiceTest`, `ciso/service/*Test` (7), `compliance/service/ComplianceServiceTest`, `common/listener/TenantEntityListenerTest` | Trace-chain ordering and OBO flag. Access-graph gaps and risk. CISO and compliance math over mocked `Object[]` rows. SOC2/SOX packs. Tenant stamping. | Native SQL, `GatewayAuditService.persist` enrichment, `AuditAsyncConfig` propagation, all controllers |
| LLM | none | — | All three Anthropic assistants |

