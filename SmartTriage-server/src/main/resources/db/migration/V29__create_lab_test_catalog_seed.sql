-- ═══════════════════════════════════════════════════════════════
-- V24 — Lab / diagnostic test catalog table + Rwanda-relevant seed.
--
-- BACKGROUND
-- ----------
-- The investigation-order form used to require the doctor to type the
-- test name as free text. That broke search, broke summaries, and made
-- the lab tech's job harder when the same test appeared as "FBC", "Full
-- Blood Count", "CBC", and "complete blood count" across charts.
--
-- WHAT THIS MIGRATION DOES
-- ------------------------
-- 1. Creates `lab_test_catalog` (matches the LabTestCatalog JPA entity).
-- 2. Seeds ~50 tests covering routine and emergency-relevant labs and
--    imaging available in Rwandan hospitals:
--      - Hematology: FBC, ESR, blood group, sickle prep, malaria smear
--      - Biochemistry: U&E, LFT, glucose, lipid panel, troponin
--      - Microbiology: blood culture, urine culture, sputum AFB,
--                      stool ova/cysts, CSF analysis
--      - Virology / serology: HIV, hepatitis B/C, malaria mRDT, dengue
--      - Imaging: chest X-ray, abdominal X-ray, CT brain, abdominal US,
--                 bedside echo, FAST scan
--      - Cardio: ECG
--      - Other: urinalysis, ABG, blood gas
--
-- IDEMPOTENCY
-- -----------
-- Insert is gated on test_name so re-running won't duplicate.
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS lab_test_catalog (
    id                          UUID PRIMARY KEY,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE,
    created_by                  VARCHAR(255),
    last_modified_by            VARCHAR(255),
    is_active                   BOOLEAN NOT NULL DEFAULT TRUE,
    version                     BIGINT,

    test_name                   VARCHAR(200) NOT NULL,
    short_name                  VARCHAR(50),
    investigation_type          VARCHAR(30) NOT NULL,
    category                    VARCHAR(100),
    specimen_type               VARCHAR(100),
    stat_turnaround_minutes     INTEGER,
    routine_turnaround_minutes  INTEGER,
    clinical_use                TEXT,
    is_common_in_rwanda         BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_lab_test_name      ON lab_test_catalog (test_name);
CREATE INDEX IF NOT EXISTS idx_lab_test_type      ON lab_test_catalog (investigation_type);
CREATE INDEX IF NOT EXISTS idx_lab_test_common    ON lab_test_catalog (is_common_in_rwanda);
CREATE INDEX IF NOT EXISTS idx_lab_test_active    ON lab_test_catalog (is_active);
CREATE UNIQUE INDEX IF NOT EXISTS uq_lab_test_name_active
    ON lab_test_catalog (LOWER(test_name)) WHERE is_active = TRUE;

INSERT INTO lab_test_catalog (
    id, created_at, updated_at, is_active, version,
    test_name, short_name, investigation_type, category,
    specimen_type, stat_turnaround_minutes, routine_turnaround_minutes,
    clinical_use, is_common_in_rwanda
)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, * FROM (VALUES
    -- ── Hematology ──
    ('Full Blood Count',              'FBC',     'LABORATORY',  'Hematology',     'EDTA whole blood', 30,  120, 'Anemia, infection, thrombocytopenia screening', TRUE),
    ('Erythrocyte Sedimentation Rate','ESR',     'LABORATORY',  'Hematology',     'EDTA whole blood', 60,  240, 'Non-specific inflammation marker', FALSE),
    ('Blood Group and Rh',            NULL,      'LABORATORY',  'Hematology',     'EDTA whole blood', 20,  60,  'Pre-transfusion typing', TRUE),
    ('Group and Crossmatch',          NULL,      'LABORATORY',  'Hematology',     'Clotted + EDTA blood', 45, 120, 'Pre-transfusion compatibility', TRUE),
    ('Sickle Cell Screen',            NULL,      'LABORATORY',  'Hematology',     'EDTA whole blood', 60,  240, 'Suspected sickle cell disease or crisis', TRUE),
    ('Reticulocyte Count',            NULL,      'LABORATORY',  'Hematology',     'EDTA whole blood', NULL,240, 'Bone marrow response in anemia', FALSE),
    ('Coagulation Profile (PT/INR/aPTT)','Coag', 'LABORATORY',  'Hematology',     'Citrate plasma',   45,  180, 'Bleeding risk, anticoagulant monitoring', TRUE),

    -- ── Biochemistry ──
    ('Urea and Electrolytes',         'U&E',     'LABORATORY',  'Biochemistry',   'Serum',            30,  120, 'Renal function, electrolyte status', TRUE),
    ('Creatinine',                    NULL,      'LABORATORY',  'Biochemistry',   'Serum',            30,  120, 'Renal function', TRUE),
    ('Liver Function Tests',          'LFT',     'LABORATORY',  'Biochemistry',   'Serum',            45,  180, 'Hepatic injury / function', TRUE),
    ('Random Blood Glucose',          'RBG',     'LABORATORY',  'Biochemistry',   'Capillary / serum', 5,  30,  'Hypo / hyperglycemia screening', TRUE),
    ('Fasting Blood Glucose',         'FBG',     'LABORATORY',  'Biochemistry',   'Serum',            NULL,60,  'Diabetes diagnosis', FALSE),
    ('HbA1c',                         NULL,      'LABORATORY',  'Biochemistry',   'EDTA whole blood', NULL,240, 'Glycemic control monitoring', FALSE),
    ('Lipid Profile',                 NULL,      'LABORATORY',  'Biochemistry',   'Serum (fasting)',  NULL,240, 'Cardiovascular risk assessment', FALSE),
    ('Troponin I',                    NULL,      'LABORATORY',  'Biochemistry',   'Serum',            45,  90,  'Suspected acute MI / myocardial injury', TRUE),
    ('Cardiac Enzymes (CK-MB)',       'CK-MB',   'LABORATORY',  'Biochemistry',   'Serum',            45,  120, 'Cardiac injury assessment', FALSE),
    ('Amylase / Lipase',              NULL,      'LABORATORY',  'Biochemistry',   'Serum',            45,  120, 'Suspected pancreatitis', FALSE),
    ('Lactate',                       NULL,      'LABORATORY',  'Biochemistry',   'Whole blood',      15,  60,  'Sepsis, shock, perfusion assessment', TRUE),
    ('C-Reactive Protein',            'CRP',     'LABORATORY',  'Biochemistry',   'Serum',            45,  120, 'Inflammation / infection marker', TRUE),
    ('Procalcitonin',                 NULL,      'LABORATORY',  'Biochemistry',   'Serum',            60,  240, 'Bacterial sepsis marker', FALSE),
    ('Beta-hCG (qualitative)',        NULL,      'LABORATORY',  'Biochemistry',   'Urine / serum',    15,  60,  'Pregnancy confirmation', TRUE),
    ('D-dimer',                       NULL,      'LABORATORY',  'Biochemistry',   'Citrate plasma',   45,  120, 'Suspected PE / DVT', FALSE),

    -- ── Microbiology ──
    ('Blood Culture',                 NULL,      'LABORATORY',  'Microbiology',   'Aerobic + anaerobic bottle', NULL, 4320, 'Suspected bacteremia / sepsis', TRUE),
    ('Urine Culture',                 NULL,      'LABORATORY',  'Microbiology',   'Mid-stream urine',  NULL,2880, 'UTI confirmation and sensitivity', TRUE),
    ('Sputum AFB Smear',              NULL,      'LABORATORY',  'Microbiology',   'Sputum',           NULL,1440, 'TB screening', TRUE),
    ('GeneXpert MTB/RIF',             'Xpert',   'LABORATORY',  'Microbiology',   'Sputum',           120, 240,  'TB confirmation + rifampicin resistance', TRUE),
    ('CSF Analysis',                  NULL,      'LABORATORY',  'Microbiology',   'CSF',              60,  240,  'Meningitis evaluation', TRUE),
    ('Stool Microscopy (Ova & Cysts)','Stool O&C','LABORATORY', 'Microbiology',   'Stool',            NULL,240,  'Parasitic GI infection', TRUE),
    ('Wound Swab Culture',            NULL,      'LABORATORY',  'Microbiology',   'Swab',             NULL,2880, 'Infected wound, ulcer', FALSE),

    -- ── Serology / virology / rapid tests ──
    ('Malaria Rapid Diagnostic Test', 'mRDT',    'RAPID_TEST',  'Parasitology',   'Capillary blood',  10,  20,   'Malaria confirmation at point of care', TRUE),
    ('Malaria Thick & Thin Film',     NULL,      'LABORATORY',  'Parasitology',   'EDTA whole blood', 30,  60,   'Malaria species + parasite density', TRUE),
    ('HIV Rapid Test',                NULL,      'RAPID_TEST',  'Serology',       'Capillary blood',  15,  30,   'HIV screening (counsel before testing)', TRUE),
    ('HIV ELISA',                     NULL,      'LABORATORY',  'Serology',       'Serum',            120, 1440, 'HIV confirmation after rapid test', FALSE),
    ('Hepatitis B Surface Antigen',   'HBsAg',   'RAPID_TEST',  'Serology',       'Serum',            30,  240,  'HBV screening', TRUE),
    ('Hepatitis C Antibody',          'Anti-HCV','RAPID_TEST',  'Serology',       'Serum',            30,  240,  'HCV screening', FALSE),
    ('Widal Test',                    NULL,      'LABORATORY',  'Serology',       'Serum',            60,  240,  'Suspected typhoid (interpret with caution)', TRUE),
    ('Dengue NS1/IgM/IgG',            NULL,      'RAPID_TEST',  'Serology',       'Serum',            30,  120,  'Suspected dengue (febrile + travel hx)', FALSE),

    -- ── Urinalysis / point-of-care ──
    ('Urinalysis (Dipstick)',         NULL,      'URINALYSIS',  'Urinalysis',     'Urine',            5,   20,   'UTI screen, ketones, glucose, blood, protein', TRUE),
    ('Urine Microscopy',              NULL,      'LABORATORY',  'Urinalysis',     'Urine',            30,  90,   'Casts, crystals, formed elements', FALSE),
    ('Arterial Blood Gas',            'ABG',     'BLOOD_GAS',   'Blood gas',      'Heparinized arterial blood', 5, 15, 'Oxygenation, ventilation, acid-base status', TRUE),
    ('Venous Blood Gas',              'VBG',     'BLOOD_GAS',   'Blood gas',      'Heparinized venous blood',   5, 15, 'Acid-base / lactate when ABG not feasible', TRUE),

    -- ── Imaging ──
    ('Chest X-Ray',                   'CXR',     'XRAY',        'Imaging',        NULL,                15,  60,   'Pneumonia, pneumothorax, heart failure, TB', TRUE),
    ('Abdominal X-Ray',               'AXR',     'XRAY',        'Imaging',        NULL,                15,  60,   'Obstruction, perforation (free air), foreign body', TRUE),
    ('Pelvic X-Ray',                  NULL,      'XRAY',        'Imaging',        NULL,                15,  60,   'Pelvic fracture in trauma', FALSE),
    ('Skeletal X-Ray (extremity)',    NULL,      'XRAY',        'Imaging',        NULL,                15,  60,   'Fracture / dislocation', TRUE),
    ('CT Brain (non-contrast)',       'CT head', 'CT_SCAN',     'Imaging',        NULL,                30,  120,  'Stroke, head injury, suspected ICH', TRUE),
    ('CT Abdomen / Pelvis',           NULL,      'CT_SCAN',     'Imaging',        NULL,                45,  240,  'Acute abdomen, trauma', FALSE),
    ('Abdominal Ultrasound',          'US abdomen','ULTRASOUND','Imaging',        NULL,                30,  120,  'Hepatobiliary, renal, free fluid, AAA', TRUE),
    ('FAST Scan',                     'FAST',    'ULTRASOUND',  'Imaging',        NULL,                10,  20,   'Bedside trauma scan: free fluid in 4 windows', TRUE),
    ('Obstetric Ultrasound',          'OB US',   'ULTRASOUND',  'Imaging',        NULL,                30,  120,  'Pregnancy viability, ectopic, fetal distress', TRUE),
    ('Bedside Echocardiography',      'POCUS echo','ULTRASOUND','Imaging',        NULL,                15,  30,   'Pericardial effusion, gross LV function', FALSE),
    ('Electrocardiogram',             'ECG',     'ECG',         'Cardiology',     NULL,                10,  20,   'Acute coronary syndrome, arrhythmia, electrolyte effects', TRUE)
) AS t(test_name, short_name, investigation_type, category,
       specimen_type, stat_turnaround_minutes, routine_turnaround_minutes,
       clinical_use, is_common_in_rwanda)
WHERE NOT EXISTS (
    SELECT 1 FROM lab_test_catalog c
    WHERE LOWER(c.test_name) = LOWER(t.test_name) AND c.is_active = TRUE
);
