# How the gateway handles concurrent MCP requests

## The approach, in plain terms

The gateway is a normal Spring MVC service, so each incoming MCP request runs on its own thread from the Tomcat worker pool. Plenty of agents call it at the same time, each on a separate thread. The request path keeps no per-request state on the server, so when we need to handle more load we run more instances behind a load balancer.

The only thing those threads share is a bit of per-session bookkeeping: the sessions we've seen, which agent is on a session, which tenant it belongs to, which ones are blocked. All of that sits in concurrent maps. Looking something up doesn't take a lock, and neither does writing to it, so one request never waits on another just to read shared state.

The one case that actually needs care is work that should happen only once per session. When an agent first connects it sends an initialize call, and under load a few requests for the same session can arrive together. We don't want to register the same agent several times over. So instead of locking, we use an atomic put-if-absent on the session id: the first thread to claim the session does the registration, and the others see it's already taken and skip it. One registration per session, and no lock.

There are only two places we take a real lock, and both are small. One is the output stream on the stdio transport, where a reader thread and the response senders share a single pipe, so we serialize writes to it. The other is the capability refresh. Everything else stays lock-free.

Anything slow, we keep off the request thread. Audit records are written on a separate executor, so a governed call returns without waiting on the database. Stale per-request identity entries are cleaned up by a small background thread on a timer rather than on the way out of the request, and because the keys are unique per request and expire on their own, nothing piles up.

So the shape of it is straightforward: a worker pool at the front, concurrent collections for the little shared state there is, one atomic guard for the once-per-session work, locks only where a stream genuinely has to be serial, and the slow work moved off to the side.

---

## Walking it in the code

For the live demo, here's the short path through the source and the one thing to point out at each stop.

### The shared session state — `HttpMcpAuditFilter`

All the cross-request state is held in concurrent collections. No global lock on the request path.

```java
// sessions whose agent has already been registered — the put-if-absent guard, so registration runs once per session
private final ConcurrentHashMap<String, Boolean> registeredSessions   = new ConcurrentHashMap<>();

// every session id we've initialized — used to spot a re-used/unknown session and to drive cleanup
private final Set<String>                         knownSessionIds      = ConcurrentHashMap.newKeySet();

// sessions belonging to a blocked agent/human — every request on them is rejected fast, no DB re-check
private final Set<String>                         blockedSessionIds    = ConcurrentHashMap.newKeySet();

// session id -> the resolved agent name for that session
private final ConcurrentHashMap<String, String>   sessionAgentNames    = new ConcurrentHashMap<>();

// session id -> the founding human subject, cached so identity isn't re-derived on every request
private final ConcurrentHashMap<String, String>   sessionIdentityCache = new ConcurrentHashMap<>();

// session id -> the tenant that session belongs to
private final ConcurrentHashMap<String, String>   sessionToTenant      = new ConcurrentHashMap<>();
```

*Say: every piece of shared state is a concurrent collection, so requests read and write it without blocking each other.*

### The once-per-session guard — `HttpMcpServerInitializer.ensureAgentRegistered`

```java
String sessionId = exchange.sessionId();
if (sessionId == null || checkedSessions.containsKey(sessionId)) {
    return;                                     // already handled
}
if (checkedSessions.putIfAbsent(sessionId, Boolean.TRUE) != null) {
    return;                                     // another thread already claimed this session
}
// exactly one thread gets past this point per session
```

*Say: even if several initialize calls for the same session land at once, only one thread registers it. Same guard is in the audit filter.*

### The only real lock — `StdioServerTransport.sendMessage`

```java
private volatile boolean closed = false;
private final ConcurrentHashMap<Object, RequestContext>          inflightRequests   = new ConcurrentHashMap<>();
private final ConcurrentHashMap<McpSchema.JSONRPCMessage, Object> messageToRequestId = new ConcurrentHashMap<>();

public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
    return Mono.fromRunnable(() -> {
        String json = mapper.writeValueAsString(message);
        synchronized (out) {                    // serialize the shared output stream, nothing else
            out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        auditResponseIfTracked(message, json);
    });
}
```

*Say: the shared stream is the one thing that has to be written by a single thread at a time. The flag is volatile for visibility, the inflight maps are concurrent.*

### Slow work off the request thread — `StatelessIdentityService`

```java
private final ScheduledExecutorService evictionScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stateless-identity-evict");
            t.setDaemon(true);
            return t;
        });
```

*Say: cleanup runs on this background thread, and audit writes go through an async executor, so neither one slows down a live request.*
