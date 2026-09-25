# Stage 1 — Auth Spine (STS + act_chain) on single-hop MCP — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:executing-plans` (inline, with checkpoints) to implement this plan milestone-by-milestone. Steps use checkbox (`- [ ]`) syntax. MCP behavior stays green throughout — the Stage-0 characterization test is the guard.

**Goal:** Stand up the gateway's own **STS** (Security Token Service) that mints **short-lived, per-hop, scoped OBO tokens carrying an `act_chain`** (human/NHI root → agent actor), wired into the single-hop MCP path — proving the agentic-auth engine (short-lived, OBO, JIT least-privilege, `act_chain`, fail-closed) end-to-end.

**Architecture:** The gateway already validates inbound JWTs with Nimbus and already extracts + carries identity (`jwtSubject`, `agentClientId`, `rawJwtClaims`) onto the MCP exchange. Stage 1 adds the *encoder* half: a per-tenant RSA signer, a JWKS endpoint, an `act_chain` model built from the already-extracted identity, and a mint service invoked at the existing per-hop credential seam (`McpAdapter.applyCredentials`). On the **external-MCP leg the minted token is an internal enforcement/audit artifact** (drives Cedar + audit; the downstream cred stays brokered); it becomes the on-the-wire token for trusting targets in the A2A phase.

**Tech Stack:** Java 17, Spring Boot 3.3.4, **Nimbus JOSE (`nimbus-jose-jwt` + `spring-security-oauth2-jose` `NimbusJwtEncoder` — already on classpath, encoder half unused)**, PostgreSQL + `ServerConfigCryptoService` (AES/GCM) for key-at-rest, `TenantContext` for tenant scoping, JUnit 5 + Mockito.

---

## LOCKED DECISIONS (confirmed with the user)

1. **STS token role (external-MCP leg):** internal delegation/enforcement artifact — carries `act_chain` + scope → Cedar + audit. **Downstream MCP-server credential stays brokered** (static config header). Becomes the wire token for JWKS-trusting targets (internal servers / A2A) later.
2. **Per-tenant signing keys.** One RSA keypair per `wsTenantName`; a global key would risk cross-tenant leakage.
3. **`act_chain[0]` integrity:** machine-rooted chains modeled explicitly (root `type=nhi`); a weak/inferred human root is marked **`verified=false`**, never fabricated.
4. **Fail-closed on mint.** Mint failure → the hop is **denied** (NOT the codebase's fail-open PDP posture).
5. **Identity source = per-exchange `transportContext`**, not `SessionManager.getCurrentSession()` (a shared-mutable single pointer — concurrency hazard). This refactor is part of Stage 1.
6. **Nimbus pin:** remove the explicit `nimbus-jose-jwt:9.23` in `pom.xml` so Boot's managed (newer) version resolves — avoids `NoSuchMethod` against `NimbusJwtEncoder`.

## SCOPE / NON-GOALS

- **In scope:** the STS (keys, JWKS, mint), the `act_chain` model + construction, per-hop scope derivation, wiring into the single-hop MCP path, `act_chain` → Cedar + audit, fail-closed mint, the identity-source refactor.
- **Out of scope (later phases):** replacing the *downstream wire credential* for external servers (stays brokered); A2A hops; SPIFFE workload identity; the neutral `RequestContext`/result contract (still Stage-0 MCP-typed); CAEP revocation; multi-hop `trace_id`/`span_id` (Stage 1 uses the existing `correlationId` as the trace root).

---

## DESIGN CONTRACTS (locked — the rest of the plan builds to these)

### act_chain claim shape (the token's delegation lineage)
```json
"act_chain": [
  { "id": "<idp-sub>", "type": "human", "verified": true,  "idp": "<issuer>", "username": "<preferred_username>" },
  { "id": "<agent-uuid>", "type": "agent", "verified": true, "clientId": "<azp/client_id>" }
]
```
- Index 0 = **root** (`type` = `human` or `nhi`). Last element = the **actor** (current agent).
- `verified`: true only when the principal is strongly authenticated (human: `sub` present + `tokenType=HUMAN_DELEGATED` + a `GatewayHumanUserEntity`; agent: resolved to a registry `GatewayAgentEntity.id` + a JWT `client_id`). Otherwise `false`.
- Machine-rooted: single root `{ id:<nhi-uuid>, type:"nhi", verified:<bool> }` then the agent actor.
- Seeded from the inbound RFC 8693 `act` claim when present (`TokenClassificationService` already detects it at `TokenClassificationService.java:57`).

### STS mint contract
```
MintRequest  { tenant, actChain (List<Principal>), rootSub, actorAgentId (UUID), actorClientId,
               targetServer, targetOriginalName, capabilityType, scope (derived), correlationId, ttlSeconds }
   → StsService.mint(MintRequest) → MintedToken { jwt (compact), jti, kid, expiresAt }
```
Minted JWT claims (RS256, `kid` in header):
```
iss  = https://<gateway-issuer>/sts/<tenant>          nbf/iat = now
sub  = rootSub (the human/NHI root — stays the root)   exp = now + ttlSeconds (default 120s)
aud  = targetServer                                     jti = random
actor      = { id: actorAgentId, type:"agent", clientId }
act_chain  = [...]                                      scope = "<derived per-hop scope>"
trace_id   = correlationId                              ws_tenant = tenant
```
**Fail-closed:** any exception in `mint()` propagates; the hop path treats a null/failed mint as a hard deny.

### Per-tenant key management
- One active RSA-2048 keypair per `wsTenantName`, `kid` = key UUID. Generated on first mint for a tenant if absent (or admin-provisioned).
- Private key stored **AES/GCM-encrypted** (reuse `ServerConfigCryptoService.encrypt`) in a new table `ws_agentic_security.gateway_sts_key` (columns: `id`, `ws_tenant_name`, `kid`, `public_jwk` (json), `private_key_enc`, `status` ACTIVE/RETIRING, `created_at`).
- Rotation: mark old ACTIVE→RETIRING (still served in JWKS during a grace window), create a new ACTIVE — mirrors the existing `DelegatingJwtDecoder` grace pattern.

### JWKS endpoint
- `GET /.well-known/sts/jwks.json` (tenant resolved from `X-WS-Tenant` / `TenantContext`) → serves the tenant's ACTIVE + RETIRING **public** JWKs. Added alongside the existing well-known controller (`OAuth2ProtectedResourceConfig`).

### Integration point (the seam)
- Mint happens in the hop path **after PDP-allow, before dispatch**, at the existing `McpAdapter.applyCredentials(hop, correlationId)` seam (`HopOrchestrator` 269/492/711, try/finally already present). Identity is read from `hop.exchange().transportContext()` (per-exchange, concurrency-safe) — NOT `getCurrentSession()`.
- The minted token is recorded (new audit event `STS_TOKEN_MINTED` + `inFlightRegistry` token-mode `STS-OBO`). For external MCP servers the outbound call keeps the brokered config header; the STS token is internal.

---

## FILE STRUCTURE

**New package `sts/`** under `src/main/java/com/ws/wsAgenticSecurityGateway/`:

| File | Responsibility |
|---|---|
| `sts/model/Principal.java` | one `act_chain` element: `id`, `type` (HUMAN/NHI/AGENT), `verified`, + optional `idp`/`username`/`clientId` |
| `sts/model/ActChain.java` | ordered `List<Principal>` + helpers (`root()`, `actor()`, `append()`), JSON-serializable to the claim shape |
| `sts/model/MintRequest.java`, `sts/model/MintedToken.java` | the mint contract records |
| `sts/entity/GatewayStsKeyEntity.java` | JPA entity for `gateway_sts_key` (tenant-scoped, encrypted private key) |
| `sts/repository/GatewayStsKeyRepository.java` | find ACTIVE/RETIRING by tenant |
| `sts/service/StsKeyService.java` | per-tenant keypair generate/load/rotate; builds a Nimbus `JWKSource` + `RSAKey` per tenant |
| `sts/service/StsService.java` | `mint(MintRequest)` — builds claims, signs via `NimbusJwtEncoder`/Nimbus `RSASSASigner`, embeds `act_chain` |
| `sts/service/ActChainBuilder.java` | builds an `ActChain` from a transportContext identity map (+ registry lookups + `act`-claim seed) |
| `sts/service/ScopeDeriver.java` | derives the per-hop scope string from `serverName` + capability `publicName` |
| `sts/web/StsJwksController.java` | `GET /.well-known/sts/jwks.json` |

**Modified:** `pom.xml` (drop nimbus pin) · `McpAdapter.java` (mint at the seam, read identity from transportContext) · `PolicyContextBuilder.java` + `CedarPolicyEngine.java` (carry + consume `act_chain`) · `McpAuditService` (`STS_TOKEN_MINTED` event) · a DB migration for `gateway_sts_key`.

**Unchanged / reused:** `GatewayOAuth2Filter`, `McpGatewayContextExtractor` (identity already extracted) · `HttpMcpTransport` override-header wire path · `ServerConfigCryptoService` (key encryption) · the Stage-0 spine/adapter seam.

---

## TESTING STRATEGY

- **Guard:** `ToolCallOrchestratorCharacterizationTest` (7/7) must stay green — MCP behavior is preserved. Run it after every integration task.
- **New unit tests** (pure, no DB/Spring where possible): key round-trip (mint → verify against JWKS), `ActChainBuilder` for human/NHI/weak cases, `ScopeDeriver`, `StsService.mint` claim correctness + fail-closed.
- **Integration test:** the JWKS endpoint serves a key that verifies a minted token.
- **Live smoke (M5):** a real MCP tool call now writes an `STS_TOKEN_MINTED` audit row with a populated `act_chain` + scope, verifiable in `mcp_audit_log`.

---

## MILESTONE ROADMAP

Foundation milestones (M0–M3) build the STS engine bottom-up and are independently unit-testable. Integration milestones (M4–M5) wire it into the hop path. **M0–M1 are task-detailed below; M2–M5 are milestone specs — each is expanded into TDD tasks at execution time, grounded in the then-current code (per the executing-plans checkpoint rhythm). This progressive elaboration is deliberate: the integration tasks' exact code depends on the engine existing first.**

### M0 — Prep: unpin Nimbus

**Files:** Modify `pom.xml`

- [ ] **Step 1: Remove the explicit `nimbus-jose-jwt` version** so Boot's managed version resolves.

Find in `pom.xml` the dependency `com.nimbusds:nimbus-jose-jwt` with `<version>9.23</version>` (~line 185) and delete the `<version>` line (keep the dependency; it stays managed by `spring-boot-dependencies`).

- [ ] **Step 2: Verify the managed version + clean compile.**

Run: `./mvnw -q dependency:tree -Dincludes=com.nimbusds:nimbus-jose-jwt && ./mvnw -q clean compile`
Expected: a single Nimbus version ≥ Boot's managed (e.g. 9.37+), `BUILD SUCCESS`.

- [ ] **Step 3: Run the full suite to confirm nothing regressed.**

Run: `./mvnw -q test`
Expected: all existing tests still green (13/13).

### M1 — STS signing keys + JWKS endpoint

**Files:** Create `sts/entity/GatewayStsKeyEntity.java`, `sts/repository/GatewayStsKeyRepository.java`, `sts/service/StsKeyService.java`, `sts/web/StsJwksController.java`; a DB migration; Test `StsKeyServiceTest.java`.

- [ ] **Step 1: Write the failing test** for per-tenant key generation + JWKS round-trip.

```java
// StsKeyServiceTest — mock the repository; real Nimbus + ServerConfigCryptoService
@Test
void generatesActiveKeyPerTenant_andSignedTokenVerifiesAgainstItsJwk() throws Exception {
    when(repo.findFirstByWsTenantNameAndStatus("acme", "ACTIVE")).thenReturn(Optional.empty());
    // service generates + saves a new ACTIVE key on demand:
    RSAKey signingKey = keyService.activeSigningKey("acme");        // returns Nimbus RSAKey (private)
    assertThat(signingKey.getKeyID()).isNotBlank();
    // sign a trivial JWT with it, then verify with the PUBLIC jwk from the JWKS view:
    SignedJWT jwt = TestJws.sign(signingKey, Map.of("sub", "x"));
    JWKSet publicSet = keyService.jwks("acme");                     // public-only
    RSAKey pub = (RSAKey) publicSet.getKeyByKeyId(signingKey.getKeyID());
    assertThat(jwt.verify(new RSASSAVerifier(pub))).isTrue();
    verify(repo).save(any());                                       // persisted (encrypted)
}
```

- [ ] **Step 2: Run it — expect FAIL** (`StsKeyService` not defined).

Run: `./mvnw -q test -Dtest=StsKeyServiceTest`
Expected: compile failure / red.

- [ ] **Step 3: Implement `GatewayStsKeyEntity` + repository + `StsKeyService`.**

`StsKeyService.activeSigningKey(tenant)`: load ACTIVE row for tenant → decrypt private key (`ServerConfigCryptoService.decryptIfEncrypted`) → rebuild `RSAKey` (private+public, `keyID=kid`). If none: `new RSAKeyGenerator(2048).keyID(UUID).generate()`, persist row with `public_jwk = key.toPublicJWK().toJSONString()` and `private_key_enc = crypto.encrypt(key.toJSONString())`, status ACTIVE. `jwks(tenant)`: ACTIVE+RETIRING rows → `new JWKSet(publicJwks)`. (Entity is tenant-scoped; the Stage-0 `TenantEntityListener` will stamp `ws_tenant_name` — but set it explicitly here too since keys are security-critical.)

- [ ] **Step 4: Run the test — expect PASS.** `./mvnw -q test -Dtest=StsKeyServiceTest`

- [ ] **Step 5: Add `StsJwksController` (`GET /.well-known/sts/jwks.json`)** returning `keyService.jwks(TenantContext.get()).toJSONObject()`; permit it in the security config (public endpoint, like the other well-known paths). Compile + a slice/integration test that the endpoint returns a JWK with a `kid`.

- [ ] **Step 6: DB migration** — create table `ws_agentic_security.gateway_sts_key` (see FILE STRUCTURE columns). Follow the project's existing migration mechanism (check how `gateway_auth_config` is created).

- [ ] **Step 7: Full build green.** `./mvnw -q clean test` → all green (existing 13 + new).

### M2 — `act_chain` model + `ActChainBuilder` (milestone spec)

**Deliverable:** `Principal`, `ActChain` (JSON-serializable to the locked claim shape), and `ActChainBuilder.fromTransportContext(Map<String,Object> ctx, sessionId)` that produces the root (human via `jwtSubject`+`HumanUserEntity`, or NHI, or unverified) + agent actor (registry `GatewayAgentEntity.id` + `agentClientId`), seeding from the `act` claim in `rawJwtClaims`.
**Reuse:** identity keys from `McpGatewayContextExtractor` output; registry resolution `AgentRegistryService.getAgentIdForSession` / `getHumanUserIdForSession` / `getNhiIdForSession`.
**Tests:** human-delegated → verified human root + agent actor; automated → NHI root; missing/weak human → `verified=false` root, never fabricated; `act`-claim seed appended correctly.

### M3 — `StsService.mint` + `ScopeDeriver` (milestone spec)

**Deliverable:** `ScopeDeriver.derive(serverName, publicName, capabilityType)` → a least-privilege scope string; `StsService.mint(MintRequest)` builds the locked claim set, signs with `keyService.activeSigningKey(tenant)` via `NimbusJwtEncoder` (or Nimbus `RSASSASigner`), returns `MintedToken`. Fail-closed (exceptions propagate).
**Tests:** minted token contains correct `iss/sub/aud/exp/jti/actor/act_chain/scope/ws_tenant` + `kid` header; verifies against `keyService.jwks(tenant)`; `exp` ≈ now+ttl; mint with a key-service failure throws (fail-closed contract).

### M4 — Wire the STS into the single-hop MCP path (milestone spec)

**Deliverable:** at `McpAdapter.applyCredentials(hop, correlationId)` (or a new `HopOrchestrator` step just before dispatch): read identity from `hop.exchange().transportContext()`, build the `ActChain`, derive scope, call `StsService.mint`; on success record `STS_TOKEN_MINTED` (new `McpAuditService` event) + `inFlightRegistry` token-mode `STS-OBO`; **fail-closed** — a failed/null mint denies the hop (tool → error result; prompt/resource → throw, matching existing error semantics). External MCP server: keep the brokered config header on the wire (STS token internal). Carry `act_chain` into `PolicyEvaluationRequest` (`PolicyContextBuilder`) and consume it in `CedarPolicyEngine.buildEvalContext` so policy can gate on lineage. **Remove the `getCurrentSession()` identity dependency** (Locked Decision 5) — and, per the Stage-0 carry-over, drop the `ToolCallOrchestrator`→`McpAdapter` `setSessionManager` forwarding if identity no longer needs it.
**Guard:** `ToolCallOrchestratorCharacterizationTest` stays green (mint is additive; on the mocked path with no identity/tenant, mint must degrade safely per the mode=none rule — decide: skip-mint in non-oauth2/open mode vs deny; align with existing open-mode behavior).
**Tests:** new integration-style tests that a hop mints + records the token; fail-closed when mint throws; characterization suite still 7/7.

### M5 — Verify end-to-end (milestone spec)

Full `./mvnw clean test` green; live smoke test: a real MCP tool call writes an `STS_TOKEN_MINTED` audit row with a populated `act_chain` + scope + `jti`; confirm via `mcp_audit_log` SQL (as in Stage-0 validation). Confirm the minted token verifies against the live `/.well-known/sts/jwks.json`.

---

## SELF-REVIEW

- **Spec coverage:** STS keys+JWKS (M1) ✓, act_chain (M2) ✓, mint+scope (M3) ✓, hop integration + Cedar + audit + fail-closed + identity-source refactor (M4) ✓, verify (M5) ✓, Nimbus prep (M0) ✓, per-tenant keys ✓, fail-closed ✓, act_chain[0] integrity ✓.
- **Progressive elaboration (not placeholders):** M2–M5 are milestone specs with concrete deliverables + reuse points + test focus; each is task-detailed at execution time grounded in the current code — the honest way to plan integration that depends on the engine existing first.
- **Type consistency:** `Principal`/`ActChain`/`MintRequest`/`MintedToken`/`StsService.mint`/`StsKeyService.activeSigningKey`/`jwks` used consistently across M1–M4.
- **Open question surfaced for M4:** open/`mode=none` behavior — skip-mint vs deny when there is no authenticated identity/tenant. Decide at M4 start, aligned with the gateway's existing open-mode posture.
