-- V101 — Let a report document attach to an IMAGING/ECG investigation too, not
-- only a lab order.
--
-- WHY: the interim standard "enter available structured data + attach the full
-- report document" applies just as much to imaging/ECG (X-ray films, CT/US
-- reports, ECG traces) as to lab tests — arguably more, since imaging IS the
-- attached document. Imaging studies are Investigation rows (no LabOrder), so the
-- document must be able to reference either an order OR an investigation.
--
-- lab_order_id becomes nullable; investigation_id is added; a CHECK enforces that
-- EXACTLY ONE owner is set (XOR). Existing rows (all lab_order_id-set,
-- investigation_id-null) satisfy the constraint unchanged.

ALTER TABLE lab_report_document ALTER COLUMN lab_order_id DROP NOT NULL;

ALTER TABLE lab_report_document
    ADD COLUMN investigation_id UUID REFERENCES investigations(id);

ALTER TABLE lab_report_document
    ADD CONSTRAINT chk_lab_report_document_one_owner
    CHECK ((lab_order_id IS NOT NULL) <> (investigation_id IS NOT NULL));

CREATE INDEX IF NOT EXISTS idx_lab_report_document_investigation
    ON lab_report_document (investigation_id);
