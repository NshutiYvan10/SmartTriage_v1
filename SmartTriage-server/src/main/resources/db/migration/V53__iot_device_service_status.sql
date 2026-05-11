-- V53 — IoT device service-status (replaces admin "power on / off").
--
-- The old power-on / power-off admin actions conflated two concepts:
-- the runtime connection state (online vs offline, driven by the
-- device's heartbeats) and the inventory state (is this device
-- part of the available pool right now, decided by the hospital
-- admin). This commit splits them.
--
-- The new `in_service` flag is the admin-controlled inventory state:
--
--   TRUE  → device is part of the active pool and may be assigned
--           to a visit. Default on registration so a freshly-added
--           device is usable immediately.
--   FALSE → device is parked. Won't appear in nurse "assign
--           monitor" pickers. Toggled back by admin when the device
--           is repaired / re-introduced.
--
-- The existing DeviceStatus column (REGISTERED / ONLINE / OFFLINE /
-- MONITORING / ERROR / DECOMMISSIONED) stays — it is the runtime
-- state, set by the device itself. Independent of service status.

ALTER TABLE iot_devices
    ADD COLUMN in_service BOOLEAN NOT NULL DEFAULT TRUE;

-- Existing rows: anything that was DECOMMISSIONED is also out of
-- service; everything else is in service.
UPDATE iot_devices
   SET in_service = FALSE
 WHERE status = 'DECOMMISSIONED';
