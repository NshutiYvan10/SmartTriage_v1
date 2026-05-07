-- ════════════════════════════════════════════════════════════════════════
-- V47 — Seed Rwanda's 5 provinces + 30 districts.
--
-- The 5/30 hierarchy has been stable since the 2006 administrative reform
-- (Organic Law N° 29/2005). Names below are the official Kinyarwanda /
-- English forms used by the Government of Rwanda; the codes are an
-- internal scheme (RW.<province>.<district>) chosen for join stability —
-- they are NOT the official ISO 3166-2:RW codes (those exist for the 5
-- provinces only).
--
-- Province display order matches the order most Rwandan government
-- forms use: Kigali City first, then geographic compass.
--
-- This seed is idempotent: re-running on an already-seeded database is
-- a no-op via ON CONFLICT (code) DO NOTHING. Sectors / cells / villages
-- are NOT seeded here — those are loaded at startup by
-- RwandaLocationCsvLoader from {classpath:rw-locations/*.csv} so an
-- operator can supply the official NISR dataset for production.
-- ════════════════════════════════════════════════════════════════════════

-- ── PROVINCES ────────────────────────────────────────────────────────
INSERT INTO rw_provinces (code, name, display_order) VALUES
    ('RW.01', 'Kigali City', 1),
    ('RW.02', 'Eastern Province', 2),
    ('RW.03', 'Northern Province', 3),
    ('RW.04', 'Southern Province', 4),
    ('RW.05', 'Western Province', 5)
ON CONFLICT (code) DO NOTHING;

-- ── DISTRICTS ────────────────────────────────────────────────────────
-- 30 total: 3 + 7 + 5 + 8 + 7 = 30.
-- Inserted with WITH-clauses so each district can resolve its
-- province_id by code without relying on a pre-known UUID.

-- Kigali City (3)
INSERT INTO rw_districts (province_id, code, name)
SELECT id, x.code, x.name FROM rw_provinces, (VALUES
    ('RW.01.01', 'Gasabo'),
    ('RW.01.02', 'Kicukiro'),
    ('RW.01.03', 'Nyarugenge')
) AS x(code, name)
WHERE rw_provinces.code = 'RW.01'
ON CONFLICT (code) DO NOTHING;

-- Eastern Province (7)
INSERT INTO rw_districts (province_id, code, name)
SELECT id, x.code, x.name FROM rw_provinces, (VALUES
    ('RW.02.01', 'Bugesera'),
    ('RW.02.02', 'Gatsibo'),
    ('RW.02.03', 'Kayonza'),
    ('RW.02.04', 'Kirehe'),
    ('RW.02.05', 'Ngoma'),
    ('RW.02.06', 'Nyagatare'),
    ('RW.02.07', 'Rwamagana')
) AS x(code, name)
WHERE rw_provinces.code = 'RW.02'
ON CONFLICT (code) DO NOTHING;

-- Northern Province (5)
INSERT INTO rw_districts (province_id, code, name)
SELECT id, x.code, x.name FROM rw_provinces, (VALUES
    ('RW.03.01', 'Burera'),
    ('RW.03.02', 'Gakenke'),
    ('RW.03.03', 'Gicumbi'),
    ('RW.03.04', 'Musanze'),
    ('RW.03.05', 'Rulindo')
) AS x(code, name)
WHERE rw_provinces.code = 'RW.03'
ON CONFLICT (code) DO NOTHING;

-- Southern Province (8)
INSERT INTO rw_districts (province_id, code, name)
SELECT id, x.code, x.name FROM rw_provinces, (VALUES
    ('RW.04.01', 'Gisagara'),
    ('RW.04.02', 'Huye'),
    ('RW.04.03', 'Kamonyi'),
    ('RW.04.04', 'Muhanga'),
    ('RW.04.05', 'Nyamagabe'),
    ('RW.04.06', 'Nyanza'),
    ('RW.04.07', 'Nyaruguru'),
    ('RW.04.08', 'Ruhango')
) AS x(code, name)
WHERE rw_provinces.code = 'RW.04'
ON CONFLICT (code) DO NOTHING;

-- Western Province (7)
INSERT INTO rw_districts (province_id, code, name)
SELECT id, x.code, x.name FROM rw_provinces, (VALUES
    ('RW.05.01', 'Karongi'),
    ('RW.05.02', 'Ngororero'),
    ('RW.05.03', 'Nyabihu'),
    ('RW.05.04', 'Nyamasheke'),
    ('RW.05.05', 'Rubavu'),
    ('RW.05.06', 'Rusizi'),
    ('RW.05.07', 'Rutsiro')
) AS x(code, name)
WHERE rw_provinces.code = 'RW.05'
ON CONFLICT (code) DO NOTHING;
