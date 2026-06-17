-- V80 — At most ONE active completion/skip per (activation, step).
--
-- completeStep/skipStep guard with an existsBy... read-then-write; two concurrent
-- requests for the same step (UI double-tap, or complete racing skip) can both pass
-- and insert duplicate completion rows (corrupting progress counts / a step showing
-- both COMPLETED and SKIPPED). This partial unique index is the DB-level backstop
-- (the service saveAndFlush's so the race surfaces as a clean 409), matching the V79
-- pattern used for pathway activation.

-- Defensive: deactivate any pre-existing duplicate completions per (activation, step),
-- keeping the earliest, so the unique index can be created on existing data.
UPDATE pathway_step_completions c
SET is_active = FALSE
WHERE c.is_active = TRUE
  AND c.id <> (
      SELECT c2.id FROM pathway_step_completions c2
      WHERE c2.activation_id = c.activation_id AND c2.step_id = c.step_id AND c2.is_active = TRUE
      ORDER BY c2.completed_at ASC, c2.id ASC
      LIMIT 1
  );

CREATE UNIQUE INDEX IF NOT EXISTS ux_pathway_step_completion_once
    ON pathway_step_completions (activation_id, step_id)
    WHERE is_active = TRUE;
