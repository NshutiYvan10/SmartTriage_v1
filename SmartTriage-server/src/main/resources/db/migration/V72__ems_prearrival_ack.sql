-- V72 — reflect the ED's pre-arrival acknowledgement back to the paramedic.
--
-- When the receiving hospital acknowledges an inbound ambulance's pre-arrival
-- alert, stamp the run so the paramedic's dashboard can show "ED acknowledged
-- — <name>". Previously the crew got only a local "sent" confirmation and no
-- signal that anyone at the destination had actually seen the inbound until
-- handover. Additive, nullable.

ALTER TABLE ems_runs
    ADD COLUMN pre_arrival_acked_at      TIMESTAMP WITH TIME ZONE,
    ADD COLUMN pre_arrival_acked_by_name VARCHAR(255);

COMMENT ON COLUMN ems_runs.pre_arrival_acked_at IS
    'When the receiving ED acknowledged the EMS_PRE_ARRIVAL alert (first ack wins). NULL until acknowledged.';
