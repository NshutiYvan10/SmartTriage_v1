-- V104 — assign a registration RFID reader to a specific registrar.
--
-- A Hospital Admin registers an RFID_READER (V95 device type) and ASSIGNS it to a registrar, so
-- that registrar's Registration Desk surfaces THEIR reader; the admin can reassign it to another
-- reader/registrar. This column records that assignment.
--
-- Nullable: unassigned readers and every non-reader device simply leave it NULL. FK ON DELETE SET
-- NULL so removing a registrar's user account clears the assignment rather than blocking the delete.
ALTER TABLE iot_devices
    ADD COLUMN IF NOT EXISTS assigned_registrar_user_id UUID
        REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_iot_device_assigned_registrar
    ON iot_devices(assigned_registrar_user_id);

COMMENT ON COLUMN iot_devices.assigned_registrar_user_id IS
    'V104 — the Registrar (users.id) this RFID registration reader is assigned to. NULL when '
    'unassigned or for non-reader devices. Hospital Admin owns the assignment; reassignable.';
