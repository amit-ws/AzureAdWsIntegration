# Compliance report templates (config-driven)

The compliance report's **structure is data, not code**. The backend ships a default template and computes a
fixed **menu of metrics** from the tenant's real audit / PDP / identity / policy data. A template chooses the
layout and *which metric goes where*; the backend fills it. A template may only reference metrics that exist in
the menu — that's validated on upload.

> This produces **evidence for the referenced controls, not a certification.** The report carries a disclaimer.

## Template schema

JSON or YAML, same shape (auto-detected on upload):

```json
{
  "framework": "SOC 2 (Trust Services Criteria)",
  "disclaimer": "optional; a default is used if omitted",
  "controls": [
    {
      "ref": "CC6.1",
      "title": "short control title",
      "requirement": "what the control requires (prose)",
      "howSatisfied": "how the gateway satisfies it (prose)",
      "evidence": [
        { "label": "Human-readable label", "metric": "decisions.total" }
      ]
    }
  ]
}
```

- `label` — free text shown in the report.
- `metric` — a **key from the menu below**. The backend replaces it with the live value. Unknown keys are rejected.
- Extra fields are ignored, so you can annotate templates freely.

## Metric menu (what a template may reference)

Get the live values any time: `GET /api/admin/ciso/compliance/metrics`.

| Key | Meaning |
|---|---|
| `decisions.total` | PDP decisions rendered |
| `decisions.allowed` / `decisions.denied` | allow / deny counts |
| `decisions.attributed` / `decisions.unattributed` | decisions tied to a named policy vs default-deny |
| `decisions.attributedOfTotal` | pre-formatted "X of Y" |
| `audit.events` | total audit events |
| `audit.eventTypes` | distinct event types |
| `audit.humanAttributed` | events tied to a verified human |
| `audit.humanAttributedOfTotal` | pre-formatted "X of Y" |
| `audit.distinctHumans` / `audit.distinctAgents` | distinct humans / agents observed |
| `audit.periodStart` / `audit.periodEnd` / `audit.period` | coverage dates (start, end, "start to end") |
| `agents.total` / `agents.approved` / `agents.pending` | registry counts by approval |
| `policies.total` / `policies.enabled` | policy counts |
| `policies.manual` / `policies.generated` / `policies.defaults` | policies by authorship source |
| `policies.lastChange` | most recent policy change date |

Need a metric that isn't here? It's a small one-time backend add (a new entry in the menu); after that any
template can use it.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/admin/ciso/compliance/metrics` | the live metric menu (key → value) |
| `GET` | `/api/admin/ciso/compliance/soc2?format=json\|csv` | the shipped default SOC 2 pack, filled |
| `POST` | `/api/admin/ciso/compliance/render?format=json\|csv` | render an **uploaded** template (JSON or YAML body) |

- `format=json` (default) returns the structured report; `format=csv` returns a downloadable spreadsheet.
- On `render`, if the template references an unknown metric it returns **400** with `unknownMetrics: [...]` (it
  does not silently fill a wrong value).
- All endpoints are tenant-scoped (`X-WS-Tenant` header) and read-only.

## Testing

```bash
# 1) see the live menu
curl -s -H "X-WS-Tenant: amitdev.local" "http://localhost:9492/api/admin/ciso/compliance/metrics" | python3 -m json.tool

# 2) default SOC 2 pack (JSON)
curl -s -H "X-WS-Tenant: amitdev.local" "http://localhost:9492/api/admin/ciso/compliance/soc2" | python3 -m json.tool

# 3) default SOC 2 pack as CSV
curl -s -H "X-WS-Tenant: amitdev.local" "http://localhost:9492/api/admin/ciso/compliance/soc2?format=csv"

# 4) upload a custom template (JSON) and render it
curl -s -X POST -H "X-WS-Tenant: amitdev.local" -H "Content-Type: text/plain" \
  --data-binary @docs/sample-compliance-template.json \
  "http://localhost:9492/api/admin/ciso/compliance/render" | python3 -m json.tool

# 5) same upload, but export CSV
curl -s -X POST -H "X-WS-Tenant: amitdev.local" -H "Content-Type: text/plain" \
  --data-binary @docs/sample-compliance-template.json \
  "http://localhost:9492/api/admin/ciso/compliance/render?format=csv"
```

A ready-to-upload sample template lives at [`docs/sample-compliance-template.json`](sample-compliance-template.json);
a YAML version of the *default* is not shipped, but any YAML matching the schema above uploads fine.

## Not yet (deliberate scope)

- **Persistence** — `render` is stateless (upload → fill → return). Storing an uploaded template per tenant so it
  sticks is a small next step.
- **PDF export** — JSON + CSV today; PDF needs a rendering library.
- **OSCAL** — for a machine-validatable standard format (NIST), we'd emit an OSCAL assessment-results doc. Heavier;
  a later option if compliance becomes a headline product.
