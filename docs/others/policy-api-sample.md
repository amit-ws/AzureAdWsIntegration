# The Policy API

## What it is

The policy API is where an administrator manages the rules that decide what an agent is allowed to do. Authorization is the core of the gateway, so this is the API we'd hold up as representative of how we build them. Everything lives under `/api/admin/policies` and is scoped to the caller's tenant.

## What's on it

| Verb | Path | Purpose |
|---|---|---|
| `GET` | `/policies` | list (optional `?agentId=` returns only the policies that govern that agent) |
| `GET` | `/policies/{id}` | fetch one |
| `POST` | `/policies` | create |
| `PUT` | `/policies/{id}` | update |
| `DELETE` | `/policies/{id}` | delete |
| `POST` | `/policies/{id}/toggle` | enable or disable |
| `POST` | `/policies/reload` | reload the decision engine |
| `GET` | `/policies/activity` | per-policy allow/deny/last-fired |
| `POST` | `/policies/validate` | check syntax |
| `POST` | `/policies/test` | dry-run a request against saved (and draft) policies, nothing saved |
| `POST` | `/policies/check` | full pre-save check: syntax, semantics, references, and effect |
| `POST` | `/policies/chat` | draft a policy from a plain-English description |

The verbs are the obvious ones. Turning a policy on or off is a state change, so that's a POST to `/{id}/toggle` rather than a field on update. The things that aren't plain create-read-update-delete get their own endpoints instead of being bolted onto something else: validate, test, check, reload, chat.

## The shape of a request

Creating a policy is a POST with the policy in the body. The controller stays thin: it hands the request to the policy service and turns the result into a response. If the service accepts it, we return the new policy with a 200. If it rejects it, we return a 400 with the reason. A read that misses returns a 404. It's the same pattern on every endpoint.

```java
@PostMapping
public ResponseEntity<Map<String, Object>> createPolicy(@RequestBody PolicyDto dto) {
    PolicyCreationResult result = policyService.createPolicy(dto);
    if (result.success()) {
        return ResponseEntity.ok(toMap(result.policy()));                    // 200 + the new policy
    }
    return ResponseEntity.badRequest().body(Map.of("error", result.error())); // 400 + the reason
}

@GetMapping("/{id}")
public ResponseEntity<Map<String, Object>> getPolicy(@PathVariable UUID id) {
    return policyService.getById(id)
            .map(p -> ResponseEntity.ok(toMap(p)))
            .orElse(ResponseEntity.notFound().build());                      // 404 when it isn't there
}
```

Listing takes an optional `agentId`. Leave it off and you get all of the tenant's policies. Pass it and you get only the ones that govern that agent, looked up from an index rather than by scanning policy text.

## The part I'd actually demo

The part worth showing is the author-time checks, because a mistake in an authorization policy is quiet and expensive. A typo in a tool name doesn't throw an error. The policy just never matches, and under default-deny that means it silently denies. So before anything is saved, an author can run three checks that persist nothing.

`validate` checks the syntax and warns on a couple of known traps. `test` dry-runs a real request against the saved policies plus the draft, and tells you allow or deny and which policy decided. `check` is the full pass: syntax, semantics, and the one that matters, it looks up every agent, tool and server the policy names in the real registry, so a typo that would quietly kill the policy is caught before it's saved.

```java
// POST /policies/check — full pre-save analysis, nothing saved:
//   1. syntax   2. semantic warnings
//   3. every agent/tool/server the policy names must actually exist in the registry
//   4. whether the policy takes effect or is overridden by a forbid
String error = cedarEngine.validatePolicy(text);
if (error != null) { resp.put("valid", false); resp.put("error", error); return ResponseEntity.ok(resp); }
resp.put("warning", cedarEngine.getSemanticWarnings(text));
Map<String, String> refs = cedarEngine.extractPolicyReferences(text);
boolean agentKnown = !agentRegistryService.findAgentsByName(refs.get("agentName")).isEmpty();
```

## Drafting a policy in plain English

There's also a chat endpoint that drafts a policy from a plain-English description. It gives back a structured response rather than free text: either a drafted policy with a suggested name, effect and explanation, or a follow-up question if it needs more to work with.

```java
@PostMapping("/chat")
public ResponseEntity<PolicyChatResponse> chatGeneratePolicy(@RequestBody PolicyChatRequest request) {
    return ResponseEntity.ok(llmService.generatePolicy(request));
}
```

The model only drafts. The administrator reviews it and saves it through the ordinary create endpoint, and the model never sits in the path that actually decides a request.

## In short

Resource paths and the right verbs, thin controllers with the real work in services, honest status codes, and a set of pre-save checks that stop a broken policy before it ever goes live.
