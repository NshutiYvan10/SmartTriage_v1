-- V95: RFID card as a co-equal cross-hospital identity anchor (tap-to-identify registration device).
--
-- The RFID card UID joins national ID as an anchor on the SHARED PersonIdentity. A PersonIdentity
-- may now be anchored by national ID AND/OR RFID card. This makes the card a true system-wide
-- identifier: a card tapped at hospital B resolves the same person first seen at hospital A, even
-- for patients with NO national ID (unconscious / newborn / foreign / unidentified) — exactly the
-- high-acuity population that benefits most from instant tap-to-identify.
--
-- national_id therefore becomes NULLABLE, with a CHECK that at least one anchor is always present
-- (a PersonIdentity with neither key would be meaningless). Both anchors are PARTIAL-unique so
-- NULLs never collide. Resolve/merge stays deterministic in PersonIdentityService — a card + NID
-- that point at different identities is REJECTED (never silently merged), since a wrong merge would
-- surface another patient's allergies (a safety incident).

-- national_id: NOT NULL -> nullable, table-constraint unique -> partial-unique index.
ALTER TABLE person_identities ALTER COLUMN national_id DROP NOT NULL;
ALTER TABLE person_identities DROP CONSTRAINT uq_person_identity_national_id;
CREATE UNIQUE INDEX uq_person_identity_national_id
    ON person_identities (national_id) WHERE national_id IS NOT NULL;

-- rfid_card_id: new co-equal anchor, partial-unique + plain lookup index.
ALTER TABLE person_identities ADD COLUMN rfid_card_id VARCHAR(64);
CREATE UNIQUE INDEX uq_person_identity_rfid_card
    ON person_identities (rfid_card_id) WHERE rfid_card_id IS NOT NULL;

-- Every identity must be anchored by at least one key.
ALTER TABLE person_identities ADD CONSTRAINT ck_person_identity_anchored
    CHECK (national_id IS NOT NULL OR rfid_card_id IS NOT NULL);

-- Registration "tap-to-capture" bind window: while now() < rfid_bind_until, the next RFID tap on
-- this device captures the card UID for the requesting registrar instead of an identify lookup.
ALTER TABLE iot_devices ADD COLUMN rfid_bind_until TIMESTAMP;
