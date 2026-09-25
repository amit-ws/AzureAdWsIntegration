-- ============================================================================
-- Hardening #2 — Unified Agent Model (Stage 1): make gateway_agent the single
-- canonical agent identity keyed by (ws_tenant_name, agent_name).
--
-- The project uses Hibernate ddl-auto:update (no Flyway). Hibernate ADDS the new
-- columns from the entity on boot, but it will NOT dedup existing rows or swap a
-- unique constraint — so this one-time migration does that for the existing dev
-- DB. A fresh DB is handled entirely by the updated entity mapping.
--
-- Idempotent + transactional. Re-runnable: ADD COLUMN IF NOT EXISTS + DROP
-- CONSTRAINT IF EXISTS; the dedup is a no-op once there is one row per (tenant,name).
-- ============================================================================
BEGIN;
SET search_path TO ws_agentic_security;

-- 1. New facet columns (A2A endpoint + explicit protocol flags).
ALTER TABLE gateway_agent
  ADD COLUMN IF NOT EXISTS a2a_base_url VARCHAR(1024),
  ADD COLUMN IF NOT EXISTS speaks_mcp   BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS speaks_a2a   BOOLEAN NOT NULL DEFAULT false;

-- 2. Choose the surviving (canonical) row per (tenant, name): prefer a row that
--    already carries a verified workload_id, then the most recently seen, then id.
CREATE TEMP TABLE _canon ON COMMIT DROP AS
SELECT ws_tenant_name, agent_name,
       (array_agg(id ORDER BY (workload_id IS NULL), last_seen_at DESC NULLS LAST, id))[1] AS canonical_id
FROM gateway_agent
GROUP BY ws_tenant_name, agent_name;

CREATE TEMP TABLE _remap ON COMMIT DROP AS
SELECT g.id AS old_id, c.canonical_id
FROM gateway_agent g
JOIN _canon c ON c.ws_tenant_name = g.ws_tenant_name AND c.agent_name = g.agent_name;

-- 3. Pre-delete group aggregates (read from ALL rows incl. losers) for the merge.
CREATE TEMP TABLE _agg ON COMMIT DROP AS
SELECT r.canonical_id,
       SUM(x.total_sessions)::int   AS sum_sessions,
       SUM(x.total_requests)::bigint AS sum_requests,
       MIN(x.first_seen_at)          AS min_first,
       MAX(x.last_seen_at)           AS max_last,
       (array_agg(x.workload_id)     FILTER (WHERE x.workload_id     IS NOT NULL))[1] AS workload_id,
       (array_agg(x.identity_source) FILTER (WHERE x.identity_source IS NOT NULL))[1] AS identity_source,
       (array_agg(x.auth_client_id)  FILTER (WHERE x.auth_client_id  IS NOT NULL))[1] AS auth_client_id,
       bool_or(x.approval_status = 'BLOCKED')  AS any_blocked,
       bool_or(x.approval_status = 'APPROVED') AS any_approved,
       bool_or(x.status = 'DEPROVISIONED')     AS any_deprov
FROM _remap r JOIN gateway_agent x ON x.id = r.old_id
GROUP BY r.canonical_id;

-- 4. Re-point the only two id-references: sessions + capability-profile assignments.
UPDATE gateway_agent_session s SET agent_id = r.canonical_id
FROM _remap r WHERE s.agent_id = r.old_id AND s.agent_id <> r.canonical_id;

--    assignments: drop rows that would collide on unique(agent_id,profile_id), then move the rest.
DELETE FROM agent_capability_profile_assignment a USING _remap r
WHERE a.agent_id = r.old_id AND a.agent_id <> r.canonical_id
  AND EXISTS (SELECT 1 FROM agent_capability_profile_assignment b
              WHERE b.agent_id = r.canonical_id AND b.profile_id = a.profile_id);
UPDATE agent_capability_profile_assignment a SET agent_id = r.canonical_id
FROM _remap r WHERE a.agent_id = r.old_id AND a.agent_id <> r.canonical_id;

-- 5. Merge counters + identity + strongest-approval + worst-status onto the canonical row.
UPDATE gateway_agent g SET
    total_sessions  = a.sum_sessions,
    total_requests  = a.sum_requests,
    first_seen_at   = a.min_first,
    last_seen_at    = a.max_last,
    workload_id     = COALESCE(g.workload_id,     a.workload_id),
    identity_source = COALESCE(g.identity_source, a.identity_source),
    auth_client_id  = COALESCE(g.auth_client_id,  a.auth_client_id),
    approval_status = CASE WHEN a.any_blocked  THEN 'BLOCKED'
                          WHEN a.any_approved THEN 'APPROVED'
                          ELSE g.approval_status END,
    status          = CASE WHEN a.any_deprov THEN 'DEPROVISIONED' ELSE g.status END
FROM _agg a WHERE g.id = a.canonical_id;

-- 6. Delete the loser rows (identity/counters already merged onto canonical).
DELETE FROM gateway_agent g USING _remap r WHERE g.id = r.old_id AND g.id <> r.canonical_id;

-- 7. Fold the A2A endpoint directory into the canonical agent.
UPDATE gateway_agent g SET a2a_base_url = z.base_url, speaks_a2a = true
FROM gateway_a2a_agent z WHERE z.name = g.agent_name AND z.ws_tenant_name = g.ws_tenant_name;

-- 8. speaks_mcp = MCP client (not a pure A2A endpoint, or has ever held an MCP session).
UPDATE gateway_agent SET speaks_mcp = (a2a_base_url IS NULL) OR (total_sessions > 0);

-- 9. Swap the identity uniqueness from (name,version,tenant) to (tenant,name).
ALTER TABLE gateway_agent DROP CONSTRAINT IF EXISTS uq_agent_name_version;
ALTER TABLE gateway_agent ADD CONSTRAINT uq_gateway_agent_tenant_name
    UNIQUE (ws_tenant_name, agent_name);

COMMIT;
