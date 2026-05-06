-- V37 — Neonatal zone capability per hospital.
--
-- The previous routing matrix sent every non-RED pediatric patient to
-- the PEDIATRIC zone. That's correct for toddlers and school-age
-- children, but it under-serves neonates (<28 days) and very young
-- infants. Neonatal patients are physiologically different — they
-- need neonatal-specific equipment (smaller tubes, different drug
-- doses, neonatal-mode SpO2 probes) and clinical staff trained on
-- neonatal resuscitation. A typical "pediatric" zone in a Rwandan
-- public hospital is observation-and-treatment, not neonatal-resus
-- equipped.
--
-- This migration adds the per-hospital config flag. NEONATAL is added
-- to the EdZone enum on the application side; because the zone column
-- is VARCHAR (not a Postgres enum), no DB-side enum change is needed.
--
-- Default is FALSE: most hospitals don't have a dedicated neonatal
-- unit and should fall through to the existing peds logic. KFH and
-- larger referral centres can flip the flag on; district hospitals
-- and health centres leave it off.
--
-- When TRUE, neonatal patients route to the NEONATAL zone regardless
-- of triage category — even GREEN-coded neonates belong in the
-- neonatal area for assessment, not the general waiting room.

ALTER TABLE hospitals
    ADD COLUMN has_neonatal_unit BOOLEAN NOT NULL DEFAULT FALSE;
