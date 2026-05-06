-- V40 — Corrective: rename zone_transfers.updated_by → last_modified_by
-- where it exists.
--
-- V36 was originally written with `updated_by`, then edited to
-- `last_modified_by` to match BaseEntity's `@LastModifiedBy` mapping.
-- Environments that applied V36 between those two states ended up
-- with a table whose audit column (updated_by) doesn't match
-- BaseEntity (last_modified_by), causing Hibernate schema validation
-- to fail at startup.
--
-- This migration is idempotent: it only acts when the old column
-- still exists. Fresh deploys (where V36 ran with the corrected
-- column name) see no change. Affected dev/staging environments
-- self-heal on next startup.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'zone_transfers'
          AND column_name = 'updated_by'
    ) THEN
        ALTER TABLE zone_transfers RENAME COLUMN updated_by TO last_modified_by;
    END IF;
END $$;
