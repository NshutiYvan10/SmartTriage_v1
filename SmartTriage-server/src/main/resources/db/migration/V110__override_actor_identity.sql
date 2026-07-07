-- V110: First-class actor identity on the two name-only override records.
--
-- The Override Register (V109) answers who/on-whom/when/why for every safety-gate
-- bypass, but two sources stamped only a DISPLAY NAME: the medication safety-check
-- override and the lab verification bypass. Names are ambiguous (two clinicians can
-- share one) and break on rename — incident forensics needs the user id + the role
-- held AT THE TIME of the override (roles change; the row must remember). The other
-- sources already carry identity (break-the-glass: actor_user_id/role; dose and
-- prescription rows: User FKs). Both write-paths already resolve the authenticated
-- User and simply discarded id/role — now they stamp them.

ALTER TABLE medication_safety_checks ADD COLUMN overridden_by_user_id UUID;
ALTER TABLE medication_safety_checks ADD COLUMN overridden_by_role    VARCHAR(30);

ALTER TABLE lab_orders ADD COLUMN verification_override_by_user_id UUID;
ALTER TABLE lab_orders ADD COLUMN verification_override_by_role    VARCHAR(30);
