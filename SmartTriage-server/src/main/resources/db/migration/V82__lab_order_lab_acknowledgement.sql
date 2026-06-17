-- V82 — Lab-tech "received/acknowledged the order" stage.
--
-- There was no tracked event for the lab acknowledging it has SEEN an order (the first
-- lab-side write was specimen accessioning). A doctor therefore could not tell whether
-- the lab had picked the order up versus it sitting unseen; the only proxy was the
-- inferred stuck-in-ORDERED early-warning. These columns record an explicit, attributable
-- acknowledgement (timestamp + actor) without adding a new workflow status — the order
-- stays in the inbox until a specimen is collected/received.

ALTER TABLE lab_orders ADD COLUMN IF NOT EXISTS acknowledged_by_lab_at   TIMESTAMPTZ;
ALTER TABLE lab_orders ADD COLUMN IF NOT EXISTS acknowledged_by_lab_name VARCHAR(255);
