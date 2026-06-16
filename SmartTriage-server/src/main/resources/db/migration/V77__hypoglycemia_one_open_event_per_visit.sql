-- V77 — Enforce at most ONE open (unresolved, active) hypoglycemia event per visit.
--
-- HypoglycemiaService.createEventAndAlert guards on an existsBy... check before
-- inserting, but that is a read-then-write that two near-simultaneous glucose
-- writers (e.g. a manual POC fingerstick recorded at the same instant as an IoT
-- tick) could both pass, inserting two open events + two CRITICAL alerts for one
-- visit. This partial unique index is the DB-level backstop that makes the
-- one-open-event-per-visit invariant a true guarantee, regardless of insert path
-- (app race, manual SQL, or a future caller).

-- Defensive: resolve any pre-existing duplicate OPEN events per visit (keep the
-- most recent) so the unique index below can be created on existing data.
UPDATE hypoglycemia_events e
SET resolved = TRUE,
    resolved_at = COALESCE(e.resolved_at, now())
WHERE e.resolved = FALSE
  AND e.is_active = TRUE
  AND e.id <> (
      SELECT e2.id
      FROM hypoglycemia_events e2
      WHERE e2.visit_id = e.visit_id
        AND e2.resolved = FALSE
        AND e2.is_active = TRUE
      ORDER BY e2.detected_at DESC, e2.id DESC
      LIMIT 1
  );

CREATE UNIQUE INDEX IF NOT EXISTS ux_hypo_open_event_per_visit
    ON hypoglycemia_events (visit_id)
    WHERE resolved = FALSE AND is_active = TRUE;
