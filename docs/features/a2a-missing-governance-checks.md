# A2A: governance checks missing compared with MCP

**Status:** gap list. Nothing here is built. Recorded 2026-09-26.
**Code facts:** `docs/others/gateway-grounding.md` (§3.4, §4.3, §4.8). All cites below were re-checked in code on 2026-09-26.

> ## ⚠️ IMPORTANT: read this before starting on any item
>
> **Before getting started on these missing features, first check whether each one is relevant to A2A at all.**
> MCP and A2A differ in many ways: sessions, how identity is presented, discovery, request shape and lifecycle. A check that is right for `/mcp` may not apply to `/a2a`, or may need a different design there.
> Treat every item as **"confirm relevance first, then design"**, never as "copy the MCP check over". Record the outcome in the **Decision** column before any code is written.

---

## Why these gaps exist

- On `/mcp`, a servlet door filter (`HttpMcpAuditFilter`) runs identity, status and revocation gates before the MCP SDK sees the request. It is registered **only on `/mcp/*`** (`protocol/mcp/transport/HttpTransportConfig.java:149`).
- `/a2a` is handled by `A2aInboundController`. Its only gate of its own is the sender-constraint (`cnf`) check (`protocol/a2a/inbound/A2aInboundController.java:95-101`). It also takes roles and groups from a verified `X-Agent-Assertion` (`:105-111`).
- The spine has its own agent-status gate, `governanceDenial`, but it looks the agent up **by session id** (`orchestration/HopOrchestrator.java:1415-1427`, called for SKILL hops at `:532`). On A2A the session id is the caller-chosen `contextId` (`protocol/a2a/inbound/A2aRequestContextFactory.java:40`). That normally matches no registered session, so the gate finds nothing and the call proceeds.

## What `/a2a` already has (do not rebuild)

- JWT authentication on the route (in oauth2 mode).
- Sender constraint: an OBO carrying `cnf.workload_id` must be presented with that agent's own `X-Agent-Assertion`, otherwise -33016.
- Roles and groups from the verified assertion.
- In the spine:
  - the capability-profile check. The agent is resolved by name (`HopOrchestrator.java:546`); if it does not resolve, the check is skipped, which is the same fail-open as MCP;
  - the policy decision. `principal.approvalStatus` does resolve on A2A;
  - the per-hop OBO mint;
  - audit;
  - egress classification.

---

## The gaps

| # | Gap | What `/mcp` does | What `/a2a` does today | Relevance questions to answer first | Decision |
|---|---|---|---|---|---|
| 1 | **Token (`jti`) revocation at the door** | Rejects a revoked session id or a revoked inbound `jti` with -33015 (`HttpMcpAuditFilter.java:175-176`) | No `jti` check anywhere. A revoked OBO is still accepted for the rest of its 120 s life | Token revocation is token-level, not protocol-level, so it is likely relevant. Where should it hook in (controller vs spine)? | — |
| 2 | **Session revocation can be dodged** | Checked at the door on every request | Checked only at mint time, keyed on the caller's own `contextId` (`sts/service/HopTokenMinter.java:61`; `A2aRequestContextFactory.java:40`). Sending a new `contextId` sidesteps it | A2A has no gateway session. What should "revoke a session" mean on A2A: the `contextId`, the trace, the human+agent pair, or the agent? Decide that before copying anything | — |
| 3 | **Human status gate** | BLOCKED → -33009 (every method); PENDING → -33013 (execution methods) (`HttpMcpAuditFilter.java:230`, `:244`) | None | Human status is identity-level, so it is likely relevant. On hops 2+ the bearer is a gateway OBO, so which human is checked: the act_chain root or something else? | — |
| 4 | **NHI status gate** | BLOCKED → -33010; PENDING → -33014 (`:260`, `:274`) | None | Only matters if NHIs can call A2A at all, which depends on #7 and on autonomous mode (`docs/features/autonomous-multiagent-nhi.md`) | — |
| 5 | **Agent status gate** | DEPROVISIONED → -33012, BLOCKED → -33007, PENDING → -33011 (`:291`, `:304`, `:316`); also refuses blocked sessions (`:156`) | The spine gate is keyed by session and finds nothing (see above). **Live data: 8 A2A calls from an agent in PENDING status were ALLOWed** (grounding §4.3) | Agent status is identity-level, so it is likely relevant. Should the calling agent be resolved by its verified `client_id` instead of the session? | — |
| 6 | **Identity pinning** | A different JWT `sub` on the same session → -32001 (`:326-343`) | None | Pinning protects a long-lived MCP session from takeover. A2A has no gateway session, so this may not apply as-is. Is `contextId` reuse across users a real risk? | — |
| 7 | **NHI discovery** | At `initialize`, registers the NHI for AUTOMATED_AGENT tokens (`:592`), and the human for delegated tokens (`:578`) | Never. Service-account callers are never registered, so an NHI-rooted (autonomous) chain cannot form on A2A | Relevant only if autonomous A2A is in scope. See `docs/features/autonomous-multiagent-nhi.md` (Options A/B) | — |
| 8 | **Session registration (kill switch)** | `initialize` writes a `gateway_agent_session` row (`:615`), which the admin "Kill session" action can stop | A2A creates no session, so "Kill session" cannot stop an A2A chain | A2A is session-less by design. What should the admin kill switch target on A2A: a trace/chain, an agent, or a human? | — |
| 9 | **Caller-filtered discovery** | `tools/list` is filtered to the calling agent's capability profile (`HttpMcpAuditFilter.java:397-411`, per grounding §3.3) | The public `GET /.well-known/agent-card.json` lists **every SKILL across all tenants**, unfiltered (`protocol/a2a/inbound/A2aAgentCardService.java:41-43`) | A2A agent cards are meant to be public discovery documents. Should filtering happen on the public card at all, or on an authenticated/extended card? This may not be relevant in the MCP sense | — |

---

## Out of scope for this doc

- **`/stateless/mcp`** also skips the door filter and has a similar gap list: no `cnf` check and no human/NHI gate (grounding §3.5). It is not covered here.
- **Other A2A limits** not related to door parity: `message/send` only, only the first text part returned, artifacts dropped, no deadline propagation, skills held in memory only. See grounding §4.2-§4.7.
