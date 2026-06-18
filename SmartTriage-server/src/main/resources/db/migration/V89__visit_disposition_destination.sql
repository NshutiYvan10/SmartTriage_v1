-- V89: Persist the disposition DESTINATION (gap #7 data-loss bug).
--
-- DispositionRequest advertised destinationWard (ADMITTED_TO_WARD / ICU) and
-- receivingFacility (TRANSFERRED), but VisitService.recordDisposition never read
-- them — the destination was silently discarded, so "admitted to ward X" or
-- "transferred to facility Y" was lost unless hand-typed into free-text notes.
-- These columns persist it.

ALTER TABLE visits
    ADD COLUMN IF NOT EXISTS disposition_destination_ward   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS disposition_receiving_facility VARCHAR(255);
