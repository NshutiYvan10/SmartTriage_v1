-- V97 — Record that the ED acknowledged the patient AT THE DOOR (arrival receipt).
--
-- Closes the "two acknowledgements" gap: the EMS_ARRIVED clinical alert (Alert
-- Center) and the inbound-ambulance "at door" card (driven by EmsRun.status)
-- were independent — acknowledging one left the other. We now stamp the run when
-- the EMS_ARRIVED alert is acknowledged, so a single acknowledge clears the alert
-- AND marks the run "received by <name>", which the dashboard card reflects.
--
-- This is the RECEIPT of the patient (a clinician has eyes on them at the door),
-- distinct from pre_arrival_acked_* (ED saw the inbound ping while en route) and
-- from the formal transfer-of-care attestation/HANDED_OFF (the deliberate
-- read-back handover, which stays a separate step).
ALTER TABLE ems_runs ADD COLUMN IF NOT EXISTS arrival_acked_at        TIMESTAMP;
ALTER TABLE ems_runs ADD COLUMN IF NOT EXISTS arrival_acked_by_name   VARCHAR(255);

COMMENT ON COLUMN ems_runs.arrival_acked_at IS
    'When the ED acknowledged the patient AT THE DOOR (acknowledging the EMS_ARRIVED '
    'alert, or via the inbound board). First ack wins. NULL until acknowledged. '
    'Distinct from pre_arrival_acked_at (en-route ping seen) and handed_off_at (formal handover).';
