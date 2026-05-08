-- V48 — Lab workflow Phase 1
--
-- Adds an explicit status column (replacing timestamp-derived state),
-- a clinical-indication field so the technician knows WHY a test was
-- ordered (essential for sample-quality judgement and reflex rules),
-- a lab accession number distinct from the order number (the lab
-- writes its own sequential ID on the specimen tube), specimen-
-- rejection fields for the haemolysed/clotted/mislabelled path, and
-- two-step verification fields (entered-by vs verified-by) which we
-- ship as self-verify by default but persist for the day a hospital
-- has HEAD_LAB_TECHNICIAN coverage and wants to gate it.
--
-- Status backfill mirrors LabOrderMapper.deriveStatus exactly so a
-- restart against existing data is a no-op for the API.

ALTER TABLE lab_orders ADD COLUMN status VARCHAR(30);

UPDATE lab_orders SET status = CASE
    WHEN cancelled_at IS NOT NULL          THEN 'CANCELLED'
    WHEN resulted_at IS NOT NULL           THEN 'RESULTED'
    WHEN processing_started_at IS NOT NULL THEN 'PROCESSING'
    WHEN received_by_lab_at IS NOT NULL    THEN 'RECEIVED_BY_LAB'
    WHEN specimen_collected_at IS NOT NULL THEN 'SPECIMEN_COLLECTED'
    ELSE 'ORDERED'
END;

ALTER TABLE lab_orders ALTER COLUMN status SET NOT NULL;
ALTER TABLE lab_orders ADD CONSTRAINT lab_order_status_chk
    CHECK (status IN ('ORDERED','SPECIMEN_COLLECTED','RECEIVED_BY_LAB',
                      'PROCESSING','RESULTED','REJECTED','CANCELLED'));
CREATE INDEX idx_lab_order_status ON lab_orders(status);

-- Clinical indication: why the test was ordered. Free-text but
-- stored separately from `notes` so the lab UI can foreground it.
ALTER TABLE lab_orders ADD COLUMN clinical_indication VARCHAR(500);

-- Lab-side accession (barcode written on the tube when received).
-- Distinct from order_number which the EHR generates at order time.
ALTER TABLE lab_orders ADD COLUMN accession_number VARCHAR(40);
CREATE UNIQUE INDEX idx_lab_order_accession_number
    ON lab_orders(accession_number)
    WHERE accession_number IS NOT NULL;

-- Specimen rejection (closes the haemolysed/clotted/mislabelled loop).
ALTER TABLE lab_orders ADD COLUMN rejected_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE lab_orders ADD COLUMN rejected_by_name VARCHAR(255);
ALTER TABLE lab_orders ADD COLUMN rejection_reason VARCHAR(50);
ALTER TABLE lab_orders ADD COLUMN rejection_notes VARCHAR(1000);
ALTER TABLE lab_orders ADD CONSTRAINT lab_order_rejection_reason_chk
    CHECK (rejection_reason IS NULL OR rejection_reason IN
           ('HAEMOLYSED','CLOTTED','INSUFFICIENT_VOLUME',
            'MISLABELLED','WRONG_CONTAINER','EXPIRED','OTHER'));

-- Two-step verification (Phase 1 ships self-verify; columns let us
-- enforce HEAD_LAB_TECHNICIAN release without another migration).
ALTER TABLE lab_orders ADD COLUMN entered_by_name VARCHAR(255);
ALTER TABLE lab_orders ADD COLUMN verified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE lab_orders ADD COLUMN verified_by_name VARCHAR(255);

-- Critical-value read-back attestation (JCI NPSG.02.03.01).
-- Stored on the order row in Phase 1 (single notification per order).
-- A separate audit table can be added in Phase 2 if multiple
-- read-back rounds are needed.
ALTER TABLE lab_orders ADD COLUMN critical_readback_text VARCHAR(1000);
ALTER TABLE lab_orders ADD COLUMN critical_contact_method VARCHAR(20);
ALTER TABLE lab_orders ADD CONSTRAINT lab_order_contact_method_chk
    CHECK (critical_contact_method IS NULL OR critical_contact_method IN
           ('PHONE','IN_PERSON','IN_APP'));
