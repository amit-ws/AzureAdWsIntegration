# Stage 0 — Refactor to the Hop Spine — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the monolithic `ToolCallOrchestrator` into a protocol-agnostic lifecycle spine (`HopOrchestrator`) plus an MCP-specific dispatch (`McpAdapter`) behind a `ProtocolAdapter` seam — **without changing MCP behavior** — so the A2A adapter can plug in later.

**Architecture:** The three near-identical methods in `ToolCallOrchestrator` (`orchestrate`, `orchestrateGetPrompt`, `orchestrateReadResource`) share one 9-step lifecycle (capability check → registry lookup → PDP → connection check → in-flight → token resolution → **dispatch** → audit). We extract steps 1–7 + audit into `HopOrchestrator` (the spine) and the protocol-specific dispatch (steps 8–9: the `mcpClientService.*` call + result shaping + enrichment + token-header injection) into `McpAdapter`. `ToolCallOrchestrator` becomes a thin **facade** that builds a `Hop` and delegates — so its two callers (`HttpMcpServerInitializer`, `McpServerApplication`) never change.

**Tech Stack:** Java 17, Spring Boot 3.3.4, MCP SDK 0.12.1 (`io.modelcontextprotocol`), JUnit 5 + Mockito (via `spring-boot-starter-test`), Maven (`./mvnw`).

---

## KEY SCOPE DECISION (read first)

**Stage 0 establishes the structural seam; it does NOT fully neutralize MCP types out of the spine yet.**

Why: `PolicyContextBuilder.buildForToolCall(...)` (and the prompt/resource variants) take the MCP `McpSyncServerExchange` directly and read ~15 attributes off `exchange.transportContext()` + `exchange.getClientInfo()`. The three MCP result types (`CallToolResult`, `GetPromptResult`, `ReadResourceResult`) are structurally heterogeneous. Designing a protocol-neutral `RequestContext` and a neutral `HopResult` **now — against only MCP, with no second protocol in hand — is guesswork that gets reworked.** That is precisely the "major refactor later" we want to avoid.

**Therefore, for Stage 0:**
- The `Hop` carries the MCP `exchange` plus the resolved routing info as its request context.
- `HopOrchestrator` (spine) runs the shared lifecycle generically but still references `McpSyncServerExchange` where PDP/session resolution need it — a **documented, temporary** coupling.
- `ProtocolAdapter` exposes MCP-typed dispatch methods (below). The **neutral** `RequestContext` + `HopResult` contract is finalized in the **A2A phase**, when the second protocol's real shape informs the abstraction.

Net: Stage 0 delivers the real win (shared lifecycle extracted + a dispatch seam + a thin facade so callers don't change), keeps MCP byte-identical, and defers the neutral contract to when it can be designed correctly. If the reviewer prefers full neutralization now, that is a larger, higher-risk variant — call it out before execution.

---

## File Structure

**New files (all under `src/main/java/com/ws/wsAgenticSecurityGateway/`):**

| File | Responsibility |
|---|---|
| `orchestration/model/CapabilityType.java` | Enum: `TOOL`, `PROMPT`, `RESOURCE`. |
| `orchestration/model/Hop.java` | Immutable request the spine operates on: `capabilityType`, `publicName`, `arguments`, `resourceUri`, `exchange`. Resolved fields (`serverName`, `originalName`, `descriptor`) are set by the spine after registry lookup. |
| `orchestration/adapter/ProtocolAdapter.java` | Seam interface. Stage-0 MCP-typed dispatch methods (see code). |
| `orchestration/adapter/McpAdapter.java` | `implements ProtocolAdapter`. Owns steps 8–9: `mcpClientService.*` calls, response enrichment, agent-token header injection (`HttpMcpTransport` ThreadLocal), and shaping to `CallToolResult`/`GetPromptResult`/`ReadResourceResult`. |
| `orchestration/HopOrchestrator.java` | The spine. Runs steps 1–7 + audit for all three capability types; delegates dispatch to the injected `ProtocolAdapter`. |

**Modified files:**

| File | Change |
|---|---|
| `orchestration/ToolCallOrchestrator.java` | Becomes a **thin facade**: its 3 public methods build a `Hop` and call `HopOrchestrator`. All lifecycle/dispatch code is removed (moved out). Public method signatures unchanged. |

**Unchanged (verified — must stay unchanged):**
- `wsServer/HttpMcpServerInitializer.java` and `wsServer/McpServerApplication.java` — call `orchestrator.orchestrate/GetPrompt/ReadResource(...)`; identical after refactor.
- `wsClient/service/McpClientService.java` — southbound client, used by `McpAdapter`.
- `pdp/service/PolicyContextBuilder.java`, `pdp/service/CedarPolicyEngine.java` — spine dependencies, unchanged in Stage 0.

**Test files (new):**
- `src/test/java/com/ws/wsAgenticSecurityGateway/orchestration/ToolCallOrchestratorCharacterizationTest.java` — pins current behavior BEFORE the refactor; must stay green AFTER.

---

## Testing Strategy

There is **no existing test coverage of the orchestrator** (only 2 unrelated test files exist). So the safety net is a **characterization test** written against the *current* `ToolCallOrchestrator`, mocking its 10 dependencies with Mockito, asserting the observable behavior of each path. We run it green on the current code, then keep it green through every refactor step. It is the definition of "MCP stays green."

Mocked dependencies (all constructor args of `ToolCallOrchestrator`): `CapabilityRegistryService`, `McpClientService`, `McpAuditService`, `InFlightRequestRegistry`, `ObjectMapper` (real instance, not mocked), `McpSessionManager`, `AgentRegistryService`, `AgentCapabilityFilterService`, `CedarPolicyEngine`, `PolicyContextBuilder`. Plus `McpSyncServerExchange` (mocked) and `SessionManager` (mocked, via `setSessionManager`).

Behaviors to pin (tool path unless noted):
1. **Happy path** — capability allowed, descriptor found, PDP allows, server connected → `mcpClientService.callTool(...)` invoked with resolved `originalName` + args; returns `CallToolResult(content, isError=false)`.
2. **Capability denied** — `capabilityFilterService.isCapabilityAllowed(...) == false` → returns error `CallToolResult(isError=true)`; `callTool` never invoked; `auditCapabilityAccessDenied` called.
3. **Not found** — `registryService.lookupByPublicName(...)` empty → error result; `callTool` never invoked.
4. **PDP denied** — `cedarPolicyEngine.evaluate(...).isDenied() == true` → error result; `callTool` never invoked.
5. **Server not connected** — `mcpSessionManager.isConnected(server) == false` → error result; `callTool` never invoked.
6. **Prompt happy path** — `orchestrateGetPrompt` → `mcpClientService.getPrompt(...)` invoked; returns the `GetPromptResult`.
7. **Resource happy path** — `orchestrateReadResource` → `mcpClientService.readResource(...)` invoked; returns `ReadResourceResult`.

---

## Tasks

### Task 1: Characterization test — pin current behavior

**Files:**
- Create: `src/test/java/com/ws/wsAgenticSecurityGateway/orchestration/ToolCallOrchestratorCharacterizationTest.java`

- [ ] **Step 1: Write the characterization test (7 behaviors above).**

Structure (fill each behavior; happy-path shown, replicate the pattern for the rest):

```java
package com.ws.wsAgenticSecurityGateway.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.*;
import com.ws.wsAgenticSecurityGateway.audit.service.McpAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import com.ws.wsAgenticSecurityGateway.pdp.dto.*;
import com.ws.wsAgenticSecurityGateway.pdp.service.*;
import com.ws.wsAgenticSecurityGateway.wsClient.config.McpSessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolCallOrchestratorCharacterizationTest {

    CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
    com.ws.wsAgenticSecurityGateway.wsClient.service.McpClientService mcpClientService =
            mock(com.ws.wsAgenticSecurityGateway.wsClient.service.McpClientService.class);
    McpAuditService auditService = mock(McpAuditService.class);
    InFlightRequestRegistry inFlight = mock(InFlightRequestRegistry.class);
    ObjectMapper objectMapper = new ObjectMapper();
    McpSessionManager mcpSessionManager = mock(McpSessionManager.class);
    AgentRegistryService agentRegistryService = mock(AgentRegistryService.class);
    AgentCapabilityFilterService capabilityFilterService = mock(AgentCapabilityFilterService.class);
    CedarPolicyEngine cedarPolicyEngine = mock(CedarPolicyEngine.class);
    PolicyContextBuilder policyContextBuilder = mock(PolicyContextBuilder.class);

    McpSyncServerExchange exchange = mock(McpSyncServerExchange.class, RETURNS_DEEP_STUBS);

    ToolCallOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ToolCallOrchestrator(registryService, mcpClientService, auditService,
                inFlight, objectMapper, mcpSessionManager, agentRegistryService,
                capabilityFilterService, cedarPolicyEngine, policyContextBuilder);
        // Neutral defaults so the happy path flows.
        when(exchange.transportContext().get(anyString())).thenReturn(null);
        when(exchange.getClientInfo()).thenReturn(new McpSchema.Implementation("test-agent", "1.0"));
        when(agentRegistryService.getAgentIdForSession(any())).thenReturn(null); // skip capability filter
        PolicyEvaluationResult allow = mock(PolicyEvaluationResult.class);
        when(allow.isDenied()).thenReturn(false);
        when(cedarPolicyEngine.evaluate(any())).thenReturn(allow);
        when(policyContextBuilder.buildForToolCall(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mock(PolicyEvaluationRequest.class));
    }

    private CapabilityDescriptor toolDescriptor() {
        CapabilityDescriptor d = mock(CapabilityDescriptor.class);
        when(d.getServerConfigName()).thenReturn("github");
        when(d.getOriginalName()).thenReturn("create_issue");
        return d;
    }

    @Test
    void toolCall_happyPath_forwardsToClientAndReturnsResult() {
        when(registryService.lookupByPublicName("github_create_issue"))
                .thenReturn(Optional.of(toolDescriptor()));
        when(mcpSessionManager.isConnected("github")).thenReturn(true);
        List<McpSchema.Content> content = List.of(new McpSchema.TextContent("ok"));
        when(mcpClientService.callTool(any(), eq("github"), eq("create_issue"), any(), any(), any()))
                .thenReturn(content);

        McpSchema.CallToolResult result =
                orchestrator.orchestrate(exchange, "github_create_issue", Map.of("title", "x"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo(content);
        verify(mcpClientService).callTool(any(), eq("github"), eq("create_issue"), any(), any(), any());
    }

    // TODO in execution: capability-denied, not-found, PDP-denied, server-not-connected,
    // prompt happy path (orchestrateGetPrompt), resource happy path (orchestrateReadResource).
    // Each asserts the observable result + that callTool/getPrompt/readResource is or isn't invoked.
}
```

> Note: during execution, replace the `TODO` with the remaining six concrete tests following the same mock-arrange / act / assert pattern — do not leave it as a comment.

- [ ] **Step 2: Run it against current code — expect PASS.**

Run: `./mvnw test -Dtest=ToolCallOrchestratorCharacterizationTest`
Expected: BUILD SUCCESS, all tests pass (pins current behavior).

- [ ] **Step 3: Commit.**

```bash
git add src/test/java/com/ws/wsAgenticSecurityGateway/orchestration/ToolCallOrchestratorCharacterizationTest.java
git commit -m "test: characterization tests pinning ToolCallOrchestrator behavior"
```

---

### Task 2: Introduce `CapabilityType` + `Hop` model

**Files:**
- Create: `src/main/java/com/ws/wsAgenticSecurityGateway/orchestration/model/CapabilityType.java`
- Create: `src/main/java/com/ws/wsAgenticSecurityGateway/orchestration/model/Hop.java`

- [ ] **Step 1: Create `CapabilityType`.**

```java
package com.ws.wsAgenticSecurityGateway.orchestration.model;

public enum CapabilityType { TOOL, PROMPT, RESOURCE }
```

- [ ] **Step 2: Create `Hop`.**

```java
package com.ws.wsAgenticSecurityGateway.orchestration.model;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import java.util.Map;

/** Protocol-agnostic request the spine operates on. Stage 0 carries the MCP exchange
 *  as request context (see Stage 0 scope decision). Resolved fields are populated by
 *  the spine after registry lookup. */
public final class Hop {
    private final CapabilityType capabilityType;
    private final String publicName;
    private final Map<String, Object> arguments; // tool/prompt
    private final String resourceUri;            // resource only
    private final McpSyncServerExchange exchange;

    private String serverName;   // resolved
    private String originalName; // resolved (tool/prompt); resource uri for RESOURCE

    public Hop(CapabilityType capabilityType, String publicName,
               Map<String, Object> arguments, String resourceUri,
               McpSyncServerExchange exchange) {
        this.capabilityType = capabilityType;
        this.publicName = publicName;
        this.arguments = arguments;
        this.resourceUri = resourceUri;
        this.exchange = exchange;
    }

    public CapabilityType capabilityType() { return capabilityType; }
    public String publicName() { return publicName; }
    public Map<String, Object> arguments() { return arguments; }
    public String resourceUri() { return resourceUri; }
    public McpSyncServerExchange exchange() { return exchange; }
    public String serverName() { return serverName; }
    public String originalName() { return originalName; }
    public void resolve(String serverName, String originalName) {
        this.serverName = serverName; this.originalName = originalName;
    }
}
```

- [ ] **Step 3: Compile.**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/com/ws/wsAgenticSecurityGateway/orchestration/model/
git commit -m "feat: add CapabilityType + Hop model for the orchestration spine"
```

---

### Task 3: Introduce `ProtocolAdapter` + `McpAdapter` (extract dispatch)

**Files:**
- Create: `src/main/java/com/ws/wsAgenticSecurityGateway/orchestration/adapter/ProtocolAdapter.java`
- Create: `src/main/java/com/ws/wsAgenticSecurityGateway/orchestration/adapter/McpAdapter.java`

- [ ] **Step 1: Create `ProtocolAdapter` (Stage-0 MCP-typed seam).**

```java
package com.ws.wsAgenticSecurityGateway.orchestration.adapter;

import com.ws.wsAgenticSecurityGateway.orchestration.model.Hop;
import io.modelcontextprotocol.spec.McpSchema;

/** The dispatch seam. Stage 0 is MCP-typed; the neutral cross-protocol contract
 *  is finalized in the A2A phase (see Stage 0 scope decision). */
public interface ProtocolAdapter {
    McpSchema.CallToolResult dispatchTool(Hop hop, String correlationId, int seqStart);
    McpSchema.GetPromptResult dispatchPrompt(Hop hop, String correlationId, int seqStart);
    McpSchema.ReadResourceResult dispatchResource(Hop hop, String correlationId, int seqStart);
}
```

- [ ] **Step 2: Create `McpAdapter` by MOVING dispatch logic out of `ToolCallOrchestrator`.**

Move these existing members from `ToolCallOrchestrator` into `McpAdapter` verbatim (they are already MCP-specific): the southbound-call blocks (the `try { ... mcpClientService.callTool/getPrompt/readResource ... }` sections of each `orchestrate*` method — `ToolCallOrchestrator.java:239-305`, `646-709`, `860-923`), plus the helpers `enrichResponseData` (`352-374`), `enrichPromptResponseData` (`376-388`), `enrichResourceResponseData` (`390-408`), `resolveAndApplyAgentToken` (`421-443`), `buildOverrideHeaders` (`445-475`), and the `AUTH_TOKEN_KEYS`/`API_KEY_KEYS` constants (`410-419`). `McpAdapter` gets constructor deps: `McpClientService`, `McpAuditService`, `InFlightRequestRegistry`, `ObjectMapper`, and `SessionManager` (set via a setter, mirroring the current `setSessionManager`). Each `dispatch*` method returns the same MCP result type the corresponding `orchestrate*` currently returns.

> During execution: copy the exact code from the cited line ranges — do not paraphrase. `McpAdapter` is a `@Service`. At the end of this step `McpAdapter` is complete but **not yet wired in**; `ToolCallOrchestrator` still has its own copies, so it still compiles and the characterization test stays green.

- [ ] **Step 3: Compile + characterization test still green.**

Run: `./mvnw -q compile && ./mvnw test -Dtest=ToolCallOrchestratorCharacterizationTest`
Expected: BUILD SUCCESS; tests pass (orchestrator behavior unchanged — adapter is unused).

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/com/ws/wsAgenticSecurityGateway/orchestration/adapter/
git commit -m "feat: add ProtocolAdapter seam + McpAdapter (dispatch extracted, not yet wired)"
```

---

### Task 4: Create `HopOrchestrator` (the spine)

**Files:**
- Create: `src/main/java/com/ws/wsAgenticSecurityGateway/orchestration/HopOrchestrator.java`

- [ ] **Step 1: Create `HopOrchestrator`** — a `@Service` holding the shared lifecycle (steps 1–7 + audit) generalized over `CapabilityType`, delegating dispatch to the injected `ProtocolAdapter`.

Constructor deps (the spine subset of `ToolCallOrchestrator`'s): `CapabilityRegistryService`, `McpAuditService`, `InFlightRequestRegistry`, `McpSessionManager`, `AgentRegistryService`, `AgentCapabilityFilterService`, `CedarPolicyEngine`, `PolicyContextBuilder`, `ProtocolAdapter`, plus `SessionManager` via setter. Public entry: `Object handle(Hop hop)` that runs the lifecycle and returns the MCP result from the adapter (`dispatchTool`/`dispatchPrompt`/`dispatchResource`) chosen by `hop.capabilityType()`.

The lifecycle body is the union of the three `orchestrate*` methods' steps 1–7 (correlation/session/client resolution `:76-114`; capability check `:88-107`; registry lookup `:121-146`; PDP `:147-181` selecting the builder by `capabilityType`; connection check `:182-192`; in-flight register `:194-234`; token resolution is delegated to the adapter's dispatch). Move `resolveSessionId` (`:308-334`), `resolveClientName` (`:336-350`), `buildErrorResult` overloads (`:477-492`) into `HopOrchestrator`. Keep the three capability types' small differences (PDP builder method, descriptor field, error style — tool returns error result, prompt/resource throw) via a `switch (hop.capabilityType())`.

> During execution: assemble from the exact cited line ranges. At the end of this step `HopOrchestrator` compiles but is **not yet wired**; the characterization test stays green (still exercising the old `ToolCallOrchestrator`).

- [ ] **Step 2: Compile + characterization test still green.**

Run: `./mvnw -q compile && ./mvnw test -Dtest=ToolCallOrchestratorCharacterizationTest`
Expected: BUILD SUCCESS; tests pass.

- [ ] **Step 3: Commit.**

```bash
git add src/main/java/com/ws/wsAgenticSecurityGateway/orchestration/HopOrchestrator.java
git commit -m "feat: add HopOrchestrator spine (lifecycle extracted, not yet wired)"
```

---

### Task 5: Rewire `ToolCallOrchestrator` → thin facade (THE green-keeping step)

**Files:**
- Modify: `src/main/java/com/ws/wsAgenticSecurityGateway/orchestration/ToolCallOrchestrator.java`

- [ ] **Step 1: Replace the body of the 3 public methods with facade delegation; delete all moved code.**

`ToolCallOrchestrator` now depends only on `HopOrchestrator` (+ keep `setSessionManager` forwarding to it if the wiring needs it). Its methods become:

```java
public McpSchema.CallToolResult orchestrate(McpSyncServerExchange exchange,
                                             String publicName, Map<String, Object> arguments) {
    Hop hop = new Hop(CapabilityType.TOOL, publicName, arguments, null, exchange);
    return (McpSchema.CallToolResult) hopOrchestrator.handle(hop);
}

public McpSchema.GetPromptResult orchestrateGetPrompt(McpSyncServerExchange exchange,
                                                       String publicName, Map<String, Object> arguments) {
    Hop hop = new Hop(CapabilityType.PROMPT, publicName, arguments, null, exchange);
    return (McpSchema.GetPromptResult) hopOrchestrator.handle(hop);
}

public McpSchema.ReadResourceResult orchestrateReadResource(McpSyncServerExchange exchange,
                                                            String publicName, String resourceUri) {
    Hop hop = new Hop(CapabilityType.RESOURCE, publicName, null, resourceUri, exchange);
    return (McpSchema.ReadResourceResult) hopOrchestrator.handle(hop);
}
```

Delete every private helper and field that moved to `HopOrchestrator`/`McpAdapter`. `setSessionManager` should forward to both the spine and adapter as wired.

- [ ] **Step 2: Run the FULL characterization test — must stay green (now exercising spine + adapter).**

Run: `./mvnw test -Dtest=ToolCallOrchestratorCharacterizationTest`
Expected: BUILD SUCCESS; **all 7 behaviors still pass** — this proves behavior is preserved end-to-end through the new structure.

- [ ] **Step 3: Full compile of the module.**

Run: `./mvnw -q clean compile`
Expected: BUILD SUCCESS (confirms `HttpMcpServerInitializer` + `McpServerApplication` still bind to the unchanged facade signatures).

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/com/ws/wsAgenticSecurityGateway/orchestration/ToolCallOrchestrator.java
git commit -m "refactor: ToolCallOrchestrator delegates to HopOrchestrator (facade); MCP behavior preserved"
```

---

### Task 6: Verify end-to-end (MCP stays green in the real app)

- [ ] **Step 1: Full build + all tests.**

Run: `./mvnw clean test`
Expected: BUILD SUCCESS; all tests pass (characterization + the 2 pre-existing tests).

- [ ] **Step 2: Manual MCP smoke test.**

Start the gateway (HTTP mode). Connect an MCP client to `/mcp`, run `tools/list`, then call one real tool on a connected downstream server. Confirm: (a) the tool result comes back correctly, (b) the audit log shows the same lifecycle events (registry lookup → PDP → call forwarded), (c) `correlationId` is present. This confirms the extract-and-delegate preserved runtime behavior, not just unit behavior.

- [ ] **Step 3: Commit any smoke-test fixes (if needed) and tag the milestone.**

```bash
git commit --allow-empty -m "chore: Stage 0 refactor complete — Hop spine + MCP adapter, MCP green"
```

---

## Self-Review Checklist

- **Spec coverage:** spine extracted (`HopOrchestrator`) ✓, dispatch extracted (`McpAdapter`) ✓, seam (`ProtocolAdapter`) ✓, facade keeps callers unchanged ✓, behavior pinned by characterization test ✓. Router is intentionally deferred (thin routing is a one-liner in the facade for Stage 0; the classifying Router lands with A2A) — note this to the reviewer.
- **Type consistency:** `Hop.resolve(serverName, originalName)`, `ProtocolAdapter.dispatchTool/Prompt/Resource`, `HopOrchestrator.handle(Hop)` — names used consistently across Tasks 2–5.
- **Placeholder scan:** the only deferred detail is the six extra characterization tests (Task 1) and the verbatim code moves (Tasks 3–4), both with exact source line ranges — execution must fill real code, not comments.

## Execution Handoff

After review, two execution options:
1. **Subagent-Driven (recommended)** — a fresh subagent per task, review between tasks.
2. **Inline Execution** — execute in this session with checkpoints after each task.
