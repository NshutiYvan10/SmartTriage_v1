-- V63: allow NEONATAL and AMBULATORY beds.
--
-- The V18 chk_bed_zone CHECK constraint only permitted
--   RESUS, ACUTE, GENERAL, TRIAGE, OBSERVATION, ISOLATION, PEDIATRIC
-- but the EdZone enum (and the zone-routing engine) also define NEONATAL and
-- AMBULATORY. ZoneRoutingService / EdZone.forPatientPlacement can route a
-- neonate (<=28 days) to NEONATAL when the hospital has a neonatal unit, and a
-- GREEN walk-in to AMBULATORY when ambulatory beds exist — yet no such bed
-- could ever be created, because the INSERT failed this CHECK. The routed
-- patient was then left with no placeable bed in their destination zone.
--
-- Widen the CHECK to the full EdZone enum so admins can create those beds
-- (manually via Bed Management, or through future tier-based seeding).
ALTER TABLE beds DROP CONSTRAINT IF EXISTS chk_bed_zone;
ALTER TABLE beds ADD CONSTRAINT chk_bed_zone CHECK (zone IN (
    'RESUS', 'ACUTE', 'GENERAL', 'TRIAGE', 'OBSERVATION', 'ISOLATION', 'PEDIATRIC', 'NEONATAL', 'AMBULATORY'
));
