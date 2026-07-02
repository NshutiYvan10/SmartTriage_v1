-- V100 — Lab report document attachments (interim standard).
--
-- WHY: the full structured lab-results pipeline (digesting analyser output via a
-- future API integration) is out of scope for now. Until it exists, the accepted
-- interim standard for a lab tech fulfilling an order is: enter the available
-- structured data (already supported) AND attach the full lab report document
-- (a scanned/printed PDF or image) to the order. This table stores that file.
--
-- Storage: in-database BYTEA. For a single-node deployment this keeps the file
-- transactional with the order, backed up with the DB, and needs no external
-- object store. Size is capped at the application + multipart layer (see
-- application.properties spring.servlet.multipart.*) and content-type is
-- allow-listed (PDF / PNG / JPEG / TIFF) in the service.

CREATE TABLE IF NOT EXISTS lab_report_document (
    id               UUID PRIMARY KEY,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ,
    created_by       VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    version          BIGINT NOT NULL DEFAULT 0,

    lab_order_id     UUID NOT NULL REFERENCES lab_orders(id),
    -- Denormalised visit id so the scoped record can be queried/authz'd without
    -- dereferencing the order every time.
    visit_id         UUID NOT NULL,

    file_name        VARCHAR(255) NOT NULL,
    content_type     VARCHAR(120) NOT NULL,
    size_bytes       BIGINT NOT NULL,
    content          BYTEA NOT NULL,

    -- Non-repudiation: server-attributed uploader (principal), never client text.
    uploaded_by_id   UUID,
    uploaded_by_name VARCHAR(255),
    description      VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_lab_report_document_order ON lab_report_document (lab_order_id);
CREATE INDEX IF NOT EXISTS idx_lab_report_document_visit ON lab_report_document (visit_id);
