-- ════════════════════════════════════════════════════════════════════════
-- V46 — Rwanda administrative-units lookup hierarchy
--
-- Replaces the previous free-text "province" / "district" / "address"
-- fields with a structured, hierarchical lookup so:
--   - Patient registration and hospital creation cascade correctly
--     (selecting a province narrows the district list, etc.).
--   - Geographic analytics (catchment maps, MoH reports, follow-up
--     routing) are reliable — no spelling drift between two records
--     of the same village.
--   - Hospital-administrative reports map to the same units the
--     national health system uses.
--
-- Hierarchy (Rwanda's 2006 administrative reform, current law):
--   Province  ─→ District ─→ Sector ─→ Cell ─→ Village
--   5            30          ~416      ~2148    ~14837
--
-- Free-text {address} fields stay on patients/hospitals as
-- supplementary "street, building, landmark" detail. Existing rows are
-- not migrated automatically; the new FK columns are nullable so the
-- system continues to function while the dataset is being populated.
--
-- Source of truth for the seeded province + district names:
-- Republic of Rwanda Organic Law N° 29/2005 (administrative entity
-- determination), as updated. The 5 provinces + 30 districts have
-- been stable since 2006.
--
-- Sectors / cells / villages are NOT seeded in this migration. They
-- are loaded at application startup from CSV files placed in
-- {classpath:rw-locations/sectors.csv}, {.../cells.csv},
-- {.../villages.csv} (see RwandaLocationCsvLoader). Operators supply
-- the official NISR dataset or a curated subset covering pilot
-- catchment areas.
-- ════════════════════════════════════════════════════════════════════════

CREATE TABLE rw_provinces (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code          VARCHAR(8)   UNIQUE NOT NULL,
    name          VARCHAR(120) UNIQUE NOT NULL,
    display_order INTEGER      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE rw_districts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    province_id   UUID         NOT NULL REFERENCES rw_provinces(id),
    code          VARCHAR(16)  UNIQUE NOT NULL,
    name          VARCHAR(120) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Two districts in different provinces could in theory share a
    -- name, but Rwanda's 30 districts all have distinct names today.
    -- Constrain (province, name) to catch dirty seed data and let the
    -- name double as a discriminator inside a province.
    UNIQUE (province_id, name)
);
CREATE INDEX idx_rw_district_province ON rw_districts(province_id);

CREATE TABLE rw_sectors (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    district_id   UUID         NOT NULL REFERENCES rw_districts(id),
    code          VARCHAR(24)  UNIQUE NOT NULL,
    name          VARCHAR(120) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (district_id, name)
);
CREATE INDEX idx_rw_sector_district ON rw_sectors(district_id);

CREATE TABLE rw_cells (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sector_id     UUID         NOT NULL REFERENCES rw_sectors(id),
    code          VARCHAR(32)  UNIQUE NOT NULL,
    name          VARCHAR(120) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (sector_id, name)
);
CREATE INDEX idx_rw_cell_sector ON rw_cells(sector_id);

CREATE TABLE rw_villages (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cell_id       UUID         NOT NULL REFERENCES rw_cells(id),
    code          VARCHAR(40)  UNIQUE NOT NULL,
    name          VARCHAR(120) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (cell_id, name)
);
CREATE INDEX idx_rw_village_cell ON rw_villages(cell_id);

-- ── Patient location FKs ─────────────────────────────────────────────
-- All five levels are nullable: a patient may credibly know only down
-- to sector ("I live in Nyarugenge → Nyamirambo") and that's still a
-- useful record. The free-text {address} column stays for
-- street/building/landmark detail. Existing rows have all five FKs
-- NULL until a clinician opens the patient and re-saves with
-- structured location.
ALTER TABLE patients
    ADD COLUMN province_id UUID REFERENCES rw_provinces(id),
    ADD COLUMN district_id UUID REFERENCES rw_districts(id),
    ADD COLUMN sector_id   UUID REFERENCES rw_sectors(id),
    ADD COLUMN cell_id     UUID REFERENCES rw_cells(id),
    ADD COLUMN village_id  UUID REFERENCES rw_villages(id);

CREATE INDEX idx_patient_province ON patients(province_id);
CREATE INDEX idx_patient_district ON patients(district_id);
CREATE INDEX idx_patient_sector   ON patients(sector_id);
CREATE INDEX idx_patient_cell     ON patients(cell_id);
CREATE INDEX idx_patient_village  ON patients(village_id);

-- ── Hospital location FKs ────────────────────────────────────────────
-- Same nullability + indexing pattern. The existing free-text
-- {province} column is left in place for now; the structured FK
-- {province_id} takes precedence when both are populated.
ALTER TABLE hospitals
    ADD COLUMN province_id UUID REFERENCES rw_provinces(id),
    ADD COLUMN district_id UUID REFERENCES rw_districts(id),
    ADD COLUMN sector_id   UUID REFERENCES rw_sectors(id),
    ADD COLUMN cell_id     UUID REFERENCES rw_cells(id),
    ADD COLUMN village_id  UUID REFERENCES rw_villages(id);

CREATE INDEX idx_hospital_province ON hospitals(province_id);
CREATE INDEX idx_hospital_district ON hospitals(district_id);
