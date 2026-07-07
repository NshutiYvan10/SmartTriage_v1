-- V111: IDSR notifiable-disease section on MoH reports.
--
-- The Rwanda IDSR return (notifiable diseases: measles, cholera, ebola/marburg,
-- meningococcal, ...) is the single most Ministry-critical piece of a facility
-- report, and the isolation module already tracks every case (NotifiableDisease
-- per infection screening + the RBC public-health-notification stamp) — but the
-- MoH report had no field for it at all. These columns carry the aggregate,
-- de-identified IDSR section: how many notifiable cases were detected in the
-- period, the per-disease breakdown, and how many were actually reported to RBC
-- (the notified/detected gap is itself a compliance indicator).

ALTER TABLE moh_reports ADD COLUMN notifiable_disease_count      INTEGER;
ALTER TABLE moh_reports ADD COLUMN notifiable_disease_breakdown  TEXT;
ALTER TABLE moh_reports ADD COLUMN public_health_notified_count  INTEGER;
