# Agentic Auth Gateway — Build Plan

A refactor of the existing MCP gateway into the **Agentic Auth Gateway**.
PRD: `../../Agentic-Gateway-PRD.md`

---

## Mental model (locked)

**ONE centralised service (the auth spine) = the product + all the value; two thin adapters = protocol plug-ins.**
- **MCP adapter** (tool calls) — now.
- **A2A adapter** (agent-to-agent calls) — next phase.

## Spine vs adapter boundary

The test: **"Does it change when the protocol changes (MCP ↔ A2A)?"**

| | Belongs to |
|---|---|
| Inbound identity dispatch, STS, `act_chain`, PDP/Cedar, audit, Agent Registry, Router classification | **Spine (shared)** |
| Transport / session-init, capability discovery, tool/skill invocation, result shaping, namespacing, downstream credential injection | **Adapter (protocol-specific)** |

**Plug-and-play linchpin:** a `ProtocolAdapter` interface —
```
discoverCapabilities()
dispatch(Hop, MintedToken) -> Response
```
The spine speaks only `Hop` / `MintedToken` / `Response` — **never** MCP objects. Any such leak = a future refactor.

## A2A call flow decision — INLINE PROXY

The gateway mints the per-hop token and calls the target itself (`agent1 → gateway → agent2`); the agent never holds a standalone token.

Chosen for max security/governance: enforcement of every hop, no stealable token in the agent's hands, delivery-level audit, mid-flight revocation. (Not the token-return model.)

## MCP adapter — Delta 1: internal & external MCP servers (already in place)

This **extends the OG gateway's existing** per-server credential brokering — **already built, fully owned.** Per-server **"auth profile"**: admins configure each server's expected credential; the gateway **brokers** it (AES-256-GCM encrypted). Serves **both**:
- **Internal** MCP server → mTLS/SPIFFE (same trust domain) or an internal API key.
- **External / SaaS** MCP server → its own OAuth token / API key.

Note: SPIFFE/SVID is caller-side (inbound) only. For the MCP leg the STS token is an internal artifact (Cedar + audit) — never seen by the MCP server.

## MCP adapter — Delta 2: stateless dual-support (new MCP spec, 2026-07-28)

The MCP spec is deprecating session-based operation (12+ month runway; removal window opens **July 2027**). The MCP adapter must support **both** models and **bridge** between them:
- **Legacy (with `initialize`)** — the `initialize` handshake + `Mcp-Session-Id` sessions.
- **New (stateless)** — no handshake; per-request **`_meta`** object (identity / capabilities / version); **`server/discover`** for capabilities.

The gateway bridges old ↔ new (e.g. a stateless client northbound ↔ a legacy server southbound), so customers don't have to migrate their servers — the adapter absorbs it.

**Adapter-only concern** — the spine is already stateless (state rides in the token: `act_chain`, `trace_id`), so the core never changes.

Already on the non-deprecated side (carried over from the OG gateway): **Streamable HTTP** transport (not the deprecated HTTP+SSE), our **own audit + correlation** (not MCP protocol-level logging), **enterprise OAuth2/Keycloak** (not Dynamic Client Registration).

---

## Staging

### Stage 0 — Refactor: carve the spine out
Split `ToolCallOrchestrator` → `HopOrchestrator` (spine) + MCP adapter; add Router + the `ProtocolAdapter` interface. MCP path green, behavior unchanged.
**Achievement:** MCP gateway running on the protocol-agnostic spine.

### Stage 1 — Auth spine on single-hop MCP
Inbound identity dispatch (SPIFFE+mesh / mTLS / JWT → agent id via registry). STS: short-lived scoped tokens, OBO/token-exchange, `act_chain`, JWKS, JIT scoping. Credential brokering on the outbound.
**Achievement:** a single-hop MCP call fully governed by the engine (short-lived token, OBO, JIT least-priv, `act_chain=[Sarah,Agent]`, brokered downstream cred).

### Stage 2 — Policy + governance → V1
Cedar ABAC + invariants (initiator-gate, monotonic down-scoping, default-deny) + approval gates. Full audit (`act_chain`/`trace_id`/scope/decision) + revocation (CAEP / registry-block).
**Achievement: Full functional Agentic Auth Gateway V1 — single-hop MCP proving everything.**

### Next phase — A2A adapter (plug & play)
Implement `ProtocolAdapter` for A2A + Router agent-vs-tool classification. Spine untouched. Multi-hop `act_chain` growth lights up.
**Achievement:** A2A slots in; multi-hop tree works with minimal change.

**Key insight:** even single-hop MCP (`Sarah → Agent → tool`, `act_chain=[Sarah,Agent]`) exercises **all** agentic-auth features. The spine is built multi-hop-capable but proven single-hop; A2A just lengthens the chain.

---

## V1 definition (goals)

1. Fully functional **centralised service**, easily scalable to drop in the A2A adapter later.
2. Fully functional **MCP adapter**, two deltas over the reused MCP core: (a) admins configure **internal & external** MCP servers, gateway serves both *(already in place)*; (b) **stateless dual-support** — works **with and without `initialize`** (via `_meta` + `server/discover`), bridging legacy and new.
3. Fully functional **gateway with the MCP single-hop workflow**.
