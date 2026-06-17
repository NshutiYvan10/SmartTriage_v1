-- V79 — Enforce at most ONE ACTIVE activation per (visit, pathway).
--
-- ClinicalPathwayService.activatePathway guards on an existsBy... check before
-- inserting, but that read-then-write can let two near-simultaneous activations of
-- the same pathway for the same visit both pass. This partial unique index is the
-- DB-level backstop (the service saveAndFlush's so the race surfaces as a clean
-- 409 rather than a duplicate ACTIVE pathway).

-- Defensive: abandon any pre-existing duplicate ACTIVE activations (keep the most
-- recent) so the unique index can be created on existing data.
UPDATE pathway_activations a
SET status = 'ABANDONED',
    completed_at = COALESCE(a.completed_at, now()),
    deviation_reason = COALESCE(a.deviation_reason, 'Superseded — duplicate active activation reconciled by V79')
WHERE a.status = 'ACTIVE'
  AND a.is_active = TRUE
  AND a.id <> (
      SELECT a2.id FROM pathway_activations a2
      WHERE a2.visit_id = a.visit_id AND a2.pathway_id = a.pathway_id
        AND a2.status = 'ACTIVE' AND a2.is_active = TRUE
      ORDER BY a2.activated_at DESC, a2.id DESC
      LIMIT 1
  );

CREATE UNIQUE INDEX IF NOT EXISTS ux_pathway_active_per_visit_pathway
    ON pathway_activations (visit_id, pathway_id)
    WHERE status = 'ACTIVE' AND is_active = TRUE;
