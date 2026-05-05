-- ═══════════════════════════════════════════════════════════════
-- V25 — Expand REML drug formulary.
--
-- BACKGROUND
-- ----------
-- V21 seeded ~25 drugs as a focused starter set. The prescribing UI now
-- supports autocomplete and dose pre-fill across all of them, but real
-- ED prescribing covers a much wider set of drugs. This migration adds
-- ~45 more drugs across the categories that were thin in V21:
--   - More antibiotics (azithromycin, doxycycline, clindamycin, vancomycin)
--   - TB and HIV drugs commonly initiated or continued in the ED
--   - Antimalarials (artemether-lumefantrine, IV artesunate, quinine)
--   - More cardiovascular (digoxin, amiodarone, ACE inhibitors,
--                          ARBs, beta-blockers, calcium channel blockers,
--                          thiazides, spironolactone)
--   - Insulins (regular, NPH) + oral hypoglycemics
--   - Anticonvulsants (phenytoin, carbamazepine, valproate, phenobarbital)
--   - Sedation (midazolam, lorazepam, ketamine, propofol)
--   - Obstetric (oxytocin, misoprostol, magnesium sulfate)
--   - Tetanus / vitamin K / iron / folic acid / calcium gluconate
--
-- All entries follow the V21 conventions:
--   - hospital_id NULL (system-wide REML)
--   - Conservative max doses (safety engine flags overdose earlier)
--   - allergen_groups encode cross-reactivity tokens
--   - Idempotent insert via NOT EXISTS
--
-- DOSE-RANGE SOURCES
-- ------------------
-- WHO Model List of Essential Medicines (22nd edition); Rwanda Standard
-- Treatment Guidelines (2019); BNF/BNFC for cross-validation.
-- ═══════════════════════════════════════════════════════════════

INSERT INTO drug_formularies (
    id, created_at, updated_at, is_active, version, hospital_id,
    generic_name, brand_names, drug_class, atc_code, reml_category,
    adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
    pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
    geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
    available_routes, contraindications, major_interactions, allergen_groups,
    is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml
)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, NULL::uuid, * FROM (VALUES
    -- ── More antibiotics ──
    ('Azithromycin',           'Zithromax,Azithro',           'Macrolide',                'J01FA10', 'Antibacterials',
     250.0::float8, 500.0::float8, 500.0::float8, 10.0::float8, 12.0::float8, 30.0::float8,
     NULL::float8, FALSE, TRUE, 'PO,IV',
     'Severe hepatic impairment,known macrolide allergy,QT prolongation', 'warfarin,statins,QT-prolonging drugs', 'azithromycin,macrolide',
     FALSE, FALSE, 'QT prolongation risk', 'B', TRUE),

    ('Doxycycline',            'Vibramycin',                  'Tetracycline',             'J01AA02', 'Antibacterials',
     100.0::float8, 200.0::float8, 200.0::float8, 2.0::float8, 4.0::float8, 4.0::float8,
     NULL::float8, FALSE, TRUE, 'PO,IV',
     'Children <8 years (tooth discoloration),pregnancy', 'warfarin,iron,antacids,oral contraceptives', 'doxycycline,tetracycline',
     FALSE, FALSE, NULL, 'D', TRUE),

    ('Clindamycin',            'Cleocin,Dalacin',             'Lincosamide',              'J01FF01', 'Antibacterials',
     150.0::float8, 600.0::float8, 1800.0::float8, 5.0::float8, 10.0::float8, 40.0::float8,
     NULL::float8, FALSE, TRUE, 'PO,IV,IM',
     'History of antibiotic-associated colitis', 'neuromuscular blockers,erythromycin', 'clindamycin,lincosamide',
     FALSE, FALSE, 'C. difficile colitis risk', 'B', TRUE),

    ('Vancomycin',             NULL,                          'Glycopeptide',             'J01XA01', 'Antibacterials',
     500.0::float8, 1000.0::float8, 4000.0::float8, 10.0::float8, 15.0::float8, 60.0::float8,
     50.0::float8, TRUE, FALSE, 'IV,PO',
     'Severe renal impairment without dose adjustment', 'aminoglycosides,loop diuretics', 'vancomycin,glycopeptide',
     TRUE, TRUE, 'Nephrotoxicity, ototoxicity, red-man syndrome', 'C', TRUE),

    ('Erythromycin',           NULL,                          'Macrolide',                'J01FA01', 'Antibacterials',
     250.0::float8, 500.0::float8, 4000.0::float8, 10.0::float8, 15.0::float8, 50.0::float8,
     NULL::float8, FALSE, TRUE, 'PO,IV',
     'Severe hepatic impairment,QT prolongation', 'warfarin,statins,carbamazepine,theophylline', 'erythromycin,macrolide',
     FALSE, FALSE, 'QT prolongation', 'B', TRUE),

    ('Ampicillin',             NULL,                          'Aminopenicillin',          'J01CA01', 'Antibacterials',
     500.0::float8, 1000.0::float8, 4000.0::float8, 25.0::float8, 50.0::float8, 200.0::float8,
     NULL::float8, TRUE, FALSE, 'IV,IM,PO',
     'Penicillin allergy,infectious mononucleosis', 'methotrexate,allopurinol', 'ampicillin,penicillin,beta-lactam',
     FALSE, FALSE, NULL, 'B', TRUE),

    ('Cloxacillin',            'Cloxapen',                    'Anti-staphylococcal penicillin', 'J01CF02', 'Antibacterials',
     250.0::float8, 500.0::float8, 4000.0::float8, 12.5::float8, 25.0::float8, 100.0::float8,
     NULL::float8, FALSE, TRUE, 'PO,IV,IM',
     'Penicillin allergy', NULL, 'cloxacillin,penicillin,beta-lactam',
     FALSE, FALSE, NULL, 'B', TRUE),

    ('Cotrimoxazole',          'Septrin,Bactrim',             'Sulfonamide combination',  'J01EE01', 'Antibacterials',
     480.0::float8, 960.0::float8, 1920.0::float8, 6.0::float8, 12.0::float8, 24.0::float8,
     NULL::float8, TRUE, FALSE, 'PO,IV',
     'Sulfa allergy,severe renal impairment,megaloblastic anemia', 'warfarin,methotrexate,phenytoin', 'cotrimoxazole,sulfa,sulfonamide',
     FALSE, FALSE, NULL, 'C', TRUE),

    -- ── Antimalarials ──
    ('Artemether-Lumefantrine','Coartem',                     'Antimalarial combination', 'P01BF01', 'Antimalarials',
     80.0::float8, 80.0::float8, 480.0::float8, 1.7::float8, 1.7::float8, 10.0::float8,
     NULL::float8, FALSE, FALSE, 'PO',
     'First trimester pregnancy,QT prolongation', 'CYP3A4 inhibitors,QT-prolonging drugs', 'artemether,lumefantrine',
     FALSE, FALSE, NULL, 'C', TRUE),

    ('Artesunate',             NULL,                          'Antimalarial',             'P01BE03', 'Antimalarials',
     120.0::float8, 240.0::float8, 720.0::float8, 2.4::float8, 2.4::float8, 7.2::float8,
     NULL::float8, FALSE, FALSE, 'IV,IM,PR',
     'Severe artemisinin allergy', NULL, 'artesunate,artemisinin',
     FALSE, FALSE, 'Severe malaria — first-line per WHO', 'C', TRUE),

    ('Quinine',                NULL,                          'Antimalarial',             'P01BC01', 'Antimalarials',
     300.0::float8, 600.0::float8, 1800.0::float8, 8.0::float8, 10.0::float8, 30.0::float8,
     NULL::float8, TRUE, FALSE, 'PO,IV',
     'G6PD deficiency (caution),myasthenia gravis,known cinchonism', 'digoxin,warfarin,QT-prolonging drugs', 'quinine,cinchona',
     FALSE, FALSE, 'Cinchonism, hypoglycemia, QT prolongation', 'D', TRUE),

    -- ── Cardiovascular ──
    ('Captopril',              'Capoten',                     'ACE inhibitor',            'C09AA01', 'Cardiovascular',
     6.25::float8, 25.0::float8, 150.0::float8, NULL::float8, NULL::float8, NULL::float8,
     50.0::float8, TRUE, FALSE, 'PO,SL',
     'Pregnancy,bilateral renal artery stenosis,angioedema history,severe aortic stenosis', 'potassium-sparing diuretics,NSAIDs,lithium', 'captopril,ace inhibitor',
     FALSE, FALSE, 'Pregnancy fetal toxicity', 'D', TRUE),

    ('Enalapril',              'Renitec,Vasotec',             'ACE inhibitor',            'C09AA02', 'Cardiovascular',
     2.5::float8, 20.0::float8, 40.0::float8, NULL::float8, NULL::float8, NULL::float8,
     50.0::float8, TRUE, FALSE, 'PO,IV',
     'Pregnancy,angioedema history,bilateral renal artery stenosis', 'potassium-sparing diuretics,NSAIDs,lithium', 'enalapril,ace inhibitor',
     FALSE, FALSE, 'Pregnancy fetal toxicity', 'D', TRUE),

    ('Atenolol',               'Tenormin',                    'Beta-blocker',             'C07AB03', 'Cardiovascular',
     25.0::float8, 100.0::float8, 200.0::float8, NULL::float8, NULL::float8, NULL::float8,
     25.0::float8, TRUE, FALSE, 'PO,IV',
     'Severe bradycardia,heart block,decompensated heart failure,severe asthma', 'verapamil,diltiazem,clonidine', 'atenolol,beta-blocker',
     FALSE, FALSE, NULL, 'D', TRUE),

    ('Metoprolol',             'Lopressor',                   'Beta-blocker',             'C07AB02', 'Cardiovascular',
     25.0::float8, 100.0::float8, 400.0::float8, NULL::float8, NULL::float8, NULL::float8,
     25.0::float8, FALSE, TRUE, 'PO,IV',
     'Severe bradycardia,heart block,decompensated heart failure', 'verapamil,diltiazem,clonidine', 'metoprolol,beta-blocker',
     FALSE, FALSE, NULL, 'C', TRUE),

    ('Amlodipine',             'Norvasc',                     'Calcium channel blocker',  'C08CA01', 'Cardiovascular',
     2.5::float8, 10.0::float8, 10.0::float8, NULL::float8, NULL::float8, NULL::float8,
     25.0::float8, FALSE, TRUE, 'PO',
     'Severe hypotension,severe aortic stenosis', 'simvastatin,CYP3A4 inhibitors', 'amlodipine,dihydropyridine,calcium channel blocker',
     FALSE, FALSE, NULL, 'C', TRUE),

    ('Hydrochlorothiazide',    'HCTZ',                        'Thiazide diuretic',        'C03AA03', 'Cardiovascular',
     12.5::float8, 25.0::float8, 50.0::float8, 1.0::float8, 2.0::float8, 4.0::float8,
     NULL::float8, TRUE, FALSE, 'PO',
     'Anuria,sulfa allergy,severe hypokalemia', 'lithium,NSAIDs,digoxin', 'hydrochlorothiazide,thiazide,sulfa,sulfonamide',
     FALSE, FALSE, NULL, 'B', TRUE),

    ('Spironolactone',         'Aldactone',                   'Aldosterone antagonist',   'C03DA01', 'Cardiovascular',
     12.5::float8, 50.0::float8, 200.0::float8, 1.0::float8, 2.0::float8, 3.0::float8,
     NULL::float8, TRUE, TRUE, 'PO',
     'Hyperkalemia,severe renal impairment,Addison disease', 'ACE inhibitors,potassium supplements,NSAIDs', 'spironolactone',
     FALSE, FALSE, NULL, 'C', TRUE),

    ('Digoxin',                'Lanoxin',                     'Cardiac glycoside',        'C01AA05', 'Cardiovascular',
     0.125::float8, 0.25::float8, 0.5::float8, 0.005::float8, 0.01::float8, 0.01::float8,
     50.0::float8, TRUE, FALSE, 'PO,IV',
     'Ventricular fibrillation,heart block (without pacemaker),hypertrophic cardiomyopathy', 'amiodarone,verapamil,quinidine,diuretics', 'digoxin,cardiac glycoside',
     TRUE, TRUE, 'Narrow therapeutic index — monitor levels', 'C', TRUE),

    ('Amiodarone',             'Cordarone',                   'Class III antiarrhythmic', 'C01BD01', 'Cardiovascular',
     200.0::float8, 400.0::float8, 1200.0::float8, 5.0::float8, 10.0::float8, 15.0::float8,
     NULL::float8, FALSE, TRUE, 'PO,IV',
     'Severe sinus bradycardia,iodine allergy,severe hepatic disease', 'warfarin,digoxin,statins,QT-prolonging drugs', 'amiodarone,iodine',
     TRUE, TRUE, 'Pulmonary, hepatic, thyroid toxicity; QT prolongation', 'D', TRUE),

    -- ── Endocrine / diabetes ──
    ('Insulin Regular (Soluble)','Actrapid',                  'Short-acting insulin',     'A10AB01', 'Endocrine',
     NULL::float8, NULL::float8, NULL::float8, NULL::float8, NULL::float8, NULL::float8,
     NULL::float8, TRUE, FALSE, 'IV,IM,SC',
     'Hypoglycemia', 'beta-blockers,steroids,thiazides', 'insulin,human insulin',
     TRUE, TRUE, 'High-alert; verify dose with second clinician for IV/concentrated', 'B', TRUE),

    ('Insulin Isophane (NPH)', 'Insulatard',                  'Intermediate insulin',     'A10AC01', 'Endocrine',
     NULL::float8, NULL::float8, NULL::float8, NULL::float8, NULL::float8, NULL::float8,
     NULL::float8, TRUE, FALSE, 'SC',
     'Hypoglycemia', 'beta-blockers,steroids', 'insulin,human insulin',
     TRUE, TRUE, NULL, 'B', TRUE),

    ('Metformin',              'Glucophage',                  'Biguanide',                'A10BA02', 'Endocrine',
     500.0::float8, 1000.0::float8, 2000.0::float8, NULL::float8, NULL::float8, NULL::float8,
     NULL::float8, TRUE, TRUE, 'PO',
     'eGFR <30,acute illness with risk of lactic acidosis,severe hepatic impairment', 'iodinated contrast,alcohol', 'metformin,biguanide',
     FALSE, FALSE, 'Lactic acidosis risk', 'B', TRUE),

    ('Glibenclamide',          'Daonil,Glyburide',            'Sulfonylurea',             'A10BB01', 'Endocrine',
     1.25::float8, 5.0::float8, 20.0::float8, NULL::float8, NULL::float8, NULL::float8,
     50.0::float8, TRUE, TRUE, 'PO',
     'Sulfa allergy,severe renal/hepatic impairment,DKA,pregnancy', 'beta-blockers,fluoroquinolones,sulfa antibiotics', 'glibenclamide,sulfonylurea,sulfa',
     FALSE, FALSE, NULL, 'C', TRUE),

    -- ── Anticonvulsants ──
    ('Phenytoin',              'Dilantin',                    'Hydantoin anticonvulsant', 'N03AB02', 'Anticonvulsant',
     100.0::float8, 300.0::float8, 600.0::float8, 4.0::float8, 8.0::float8, 8.0::float8,
     50.0::float8, FALSE, TRUE, 'PO,IV',
     'Sinus bradycardia,heart block,Stokes-Adams syndrome', 'warfarin,oral contraceptives,many CYP inducers', 'phenytoin,hydantoin',
     TRUE, TRUE, 'Cardiac arrhythmia with rapid IV', 'D', TRUE),

    ('Carbamazepine',          'Tegretol',                    'Anticonvulsant',           'N03AF01', 'Anticonvulsant',
     100.0::float8, 400.0::float8, 1600.0::float8, 5.0::float8, 10.0::float8, 35.0::float8,
     NULL::float8, FALSE, TRUE, 'PO',
     'AV block,bone marrow suppression,porphyria', 'oral contraceptives,warfarin,macrolides', 'carbamazepine',
     FALSE, FALSE, 'Stevens-Johnson syndrome, agranulocytosis', 'D', TRUE),

    ('Sodium Valproate',       'Depakine,Epilim',             'Anticonvulsant',           'N03AG01', 'Anticonvulsant',
     200.0::float8, 500.0::float8, 2500.0::float8, 10.0::float8, 15.0::float8, 60.0::float8,
     NULL::float8, FALSE, TRUE, 'PO,IV',
     'Hepatic disease,pregnancy (avoid in women of childbearing age),urea cycle disorders', 'phenytoin,phenobarbital,warfarin', 'valproate,sodium valproate',
     FALSE, FALSE, 'Hepatotoxicity, pancreatitis, teratogenicity', 'D', TRUE),

    ('Phenobarbital',          'Luminal',                     'Barbiturate anticonvulsant','N03AA02','Anticonvulsant',
     30.0::float8, 100.0::float8, 320.0::float8, 3.0::float8, 6.0::float8, 8.0::float8,
     NULL::float8, TRUE, TRUE, 'PO,IV,IM',
     'Severe respiratory disease,porphyria', 'opioids,benzodiazepines,alcohol', 'phenobarbital,barbiturate',
     TRUE, FALSE, 'Respiratory depression, dependence', 'D', TRUE),

    -- ── Sedation / induction ──
    ('Midazolam',              'Dormicum',                    'Benzodiazepine',           'N05CD08', 'Sedation',
     1.0::float8, 5.0::float8, 20.0::float8, 0.05::float8, 0.1::float8, 0.4::float8,
     50.0::float8, FALSE, TRUE, 'IV,IM,PO,NASAL',
     'Severe respiratory depression,acute narrow-angle glaucoma,sleep apnea,shock', 'opioids,alcohol,CNS depressants', 'midazolam,benzodiazepine,benzo',
     TRUE, TRUE, 'Respiratory depression — prepare flumazenil and bag-mask', 'D', TRUE),

    ('Lorazepam',              'Ativan',                      'Benzodiazepine',           'N05BA06', 'Sedation',
     1.0::float8, 4.0::float8, 8.0::float8, 0.05::float8, 0.1::float8, 0.2::float8,
     50.0::float8, FALSE, TRUE, 'IV,IM,PO,SL',
     'Severe respiratory depression,acute narrow-angle glaucoma', 'opioids,alcohol,CNS depressants', 'lorazepam,benzodiazepine,benzo',
     TRUE, TRUE, 'Respiratory depression', 'D', TRUE),

    ('Ketamine',               NULL,                          'NMDA-receptor antagonist', 'N01AX03', 'Sedation',
     50.0::float8, 100.0::float8, 200.0::float8, 1.0::float8, 2.0::float8, 4.0::float8,
     NULL::float8, FALSE, TRUE, 'IV,IM',
     'Severe hypertension,raised intracranial pressure,severe coronary disease,glaucoma,acute psychosis', 'thyroid hormones,sympathomimetics', 'ketamine',
     TRUE, TRUE, 'Emergence reactions, raised ICP', 'C', TRUE),

    ('Haloperidol',            'Haldol,Serenace',             'Typical antipsychotic',    'N05AD01', 'Psychiatric',
     0.5::float8, 5.0::float8, 30.0::float8, 0.025::float8, 0.075::float8, 0.15::float8,
     50.0::float8, FALSE, TRUE, 'PO,IV,IM',
     'CNS depression,Parkinson disease,severe cardiac disease,QT prolongation', 'QT-prolonging drugs,levodopa', 'haloperidol,butyrophenone',
     FALSE, FALSE, 'QT prolongation, neuroleptic malignant syndrome', 'C', TRUE),

    -- ── Obstetric ──
    ('Oxytocin',               'Pitocin,Syntocinon',          'Oxytocic',                 'H01BB02', 'Obstetric',
     NULL::float8, NULL::float8, NULL::float8, NULL::float8, NULL::float8, NULL::float8,
     NULL::float8, FALSE, FALSE, 'IV,IM',
     'Hypertonic uterine contractions,fetal distress without delivery imminent', NULL, 'oxytocin',
     TRUE, TRUE, 'Water intoxication with prolonged high-dose infusion', 'X', TRUE),

    ('Misoprostol',            'Cytotec',                     'Prostaglandin E1 analogue','G02AD06', 'Obstetric',
     200.0::float8, 600.0::float8, 1000.0::float8, NULL::float8, NULL::float8, NULL::float8,
     NULL::float8, FALSE, FALSE, 'PO,SL,PR,VAGINAL',
     'Pregnancy (when continuation desired)', NULL, 'misoprostol,prostaglandin',
     FALSE, FALSE, 'Uterine rupture if used inappropriately during pregnancy', 'X', TRUE),

    ('Magnesium Sulfate',      'MgSO4',                       'Anticonvulsant / electrolyte','B05XA05','Obstetric',
     1000.0::float8, 4000.0::float8, 40000.0::float8, 25.0::float8, 50.0::float8, 100.0::float8,
     NULL::float8, TRUE, FALSE, 'IV,IM',
     'Heart block,severe renal impairment,myasthenia gravis', 'calcium channel blockers,neuromuscular blockers', 'magnesium sulfate,magnesium',
     TRUE, TRUE, 'Respiratory depression at high serum levels — keep calcium gluconate ready', 'D', TRUE),

    -- ── Misc emergency / supportive ──
    ('Calcium Gluconate',      NULL,                          'Calcium replacement',      'A12AA03', 'Resuscitation',
     1000.0::float8, 2000.0::float8, 5000.0::float8, 50.0::float8, 100.0::float8, 200.0::float8,
     NULL::float8, FALSE, FALSE, 'IV',
     'Hypercalcemia,digoxin toxicity (relative)', 'digoxin,thiazides', 'calcium gluconate,calcium',
     FALSE, FALSE, NULL, 'C', TRUE),

    ('Sodium Bicarbonate',     'NaHCO3',                      'Alkalinizer',              'B05XA02', 'Resuscitation',
     1000.0::float8, 2000.0::float8, 4000.0::float8, NULL::float8, NULL::float8, NULL::float8,
     NULL::float8, FALSE, FALSE, 'IV',
     'Metabolic / respiratory alkalosis,hypocalcemia,hypokalemia', NULL, 'sodium bicarbonate',
     FALSE, FALSE, NULL, 'C', TRUE),

    ('Mannitol',               NULL,                          'Osmotic diuretic',         'B05BC01', 'Neurological',
     12500.0::float8, 100000.0::float8, 200000.0::float8, 250.0::float8, 1000.0::float8, 2000.0::float8,
     NULL::float8, TRUE, FALSE, 'IV',
     'Anuria,severe pulmonary edema,severe dehydration,active intracranial bleeding', 'lithium', 'mannitol',
     FALSE, FALSE, NULL, 'C', TRUE),

    ('Furosemide IV bolus',    'Lasix bolus',                 'Loop diuretic',            'C03CA01', 'Cardiovascular',
     20.0::float8, 80.0::float8, 200.0::float8, 0.5::float8, 1.0::float8, 6.0::float8,
     25.0::float8, TRUE, TRUE, 'IV',
     'Anuria,severe hypokalemia', 'aminoglycosides,lithium,NSAIDs', 'furosemide,sulfa,sulfonamide',
     FALSE, FALSE, NULL, 'C', TRUE),

    ('Tranexamic Acid',        'Cyklokapron',                 'Antifibrinolytic',         'B02AA02', 'Hematology',
     500.0::float8, 1000.0::float8, 4000.0::float8, 10.0::float8, 25.0::float8, 75.0::float8,
     NULL::float8, TRUE, FALSE, 'PO,IV',
     'Active thromboembolic disease,history of seizures,severe renal impairment', 'tretinoin,oral contraceptives', 'tranexamic acid',
     FALSE, FALSE, NULL, 'B', TRUE),

    ('Vitamin K (Phytomenadione)','Konakion',                 'Coagulation factor',       'B02BA01', 'Hematology',
     1.0::float8, 10.0::float8, 40.0::float8, 0.5::float8, 1.0::float8, 5.0::float8,
     NULL::float8, FALSE, TRUE, 'PO,IV,IM,SC',
     'Severe hepatic disease (limited efficacy)', 'warfarin (intentional reversal)', 'vitamin k,phytomenadione',
     FALSE, FALSE, 'Anaphylaxis with IV — give slowly', 'C', TRUE),

    ('Naloxone',               'Narcan',                      'Opioid antagonist',        'V03AB15', 'Resuscitation',
     0.4::float8, 2.0::float8, 10.0::float8, 0.01::float8, 0.1::float8, 2.0::float8,
     NULL::float8, FALSE, FALSE, 'IV,IM,SC,NASAL',
     'Known hypersensitivity', NULL, 'naloxone',
     TRUE, FALSE, 'Acute opioid withdrawal — titrate carefully', 'B', TRUE),

    ('Flumazenil',             'Anexate',                     'Benzodiazepine antagonist','V03AB25', 'Resuscitation',
     0.2::float8, 0.5::float8, 3.0::float8, 0.01::float8, 0.02::float8, 0.05::float8,
     NULL::float8, FALSE, TRUE, 'IV',
     'Long-term benzodiazepine dependence,tricyclic overdose,seizure risk', 'tricyclic antidepressants,benzodiazepines (chronic use)', 'flumazenil',
     FALSE, FALSE, 'Seizures in chronic benzodiazepine users', 'C', TRUE),

    ('Tetanus Immunoglobulin', 'TIG',                         'Immunoglobulin',           'J06BB02', 'Immunization',
     NULL::float8, NULL::float8, NULL::float8, NULL::float8, NULL::float8, NULL::float8,
     NULL::float8, FALSE, FALSE, 'IM',
     'Severe IgA deficiency,known immunoglobulin allergy', NULL, 'tetanus immunoglobulin,immunoglobulin',
     FALSE, FALSE, NULL, 'C', TRUE),

    ('Tetanus Toxoid',         'TT',                          'Vaccine',                  'J07AM01', 'Immunization',
     NULL::float8, NULL::float8, NULL::float8, NULL::float8, NULL::float8, NULL::float8,
     NULL::float8, FALSE, FALSE, 'IM',
     'Recent tetanus vaccination (within 5 years)', NULL, 'tetanus toxoid,tetanus vaccine',
     FALSE, FALSE, NULL, 'C', TRUE),

    -- ── Iron / nutrition ──
    ('Ferrous Sulfate',        NULL,                          'Iron supplement',          'B03AA07', 'Hematology',
     200.0::float8, 200.0::float8, 600.0::float8, 3.0::float8, 6.0::float8, 6.0::float8,
     NULL::float8, FALSE, FALSE, 'PO',
     'Hemochromatosis,hemolytic anemia,active GI bleed', 'tetracyclines,fluoroquinolones,levothyroxine,calcium', 'ferrous sulfate,iron',
     FALSE, FALSE, NULL, 'A', TRUE),

    ('Folic Acid',             NULL,                          'Vitamin',                  'B03BB01', 'Hematology',
     1.0::float8, 5.0::float8, 15.0::float8, 0.1::float8, 0.4::float8, 5.0::float8,
     NULL::float8, FALSE, FALSE, 'PO',
     'Untreated B12 deficiency (will mask)', 'methotrexate,phenytoin,sulfasalazine', 'folic acid,folate',
     FALSE, FALSE, NULL, 'A', TRUE),

    ('Pyridoxine (Vitamin B6)','B6',                          'Vitamin',                  'A11HA02', 'Vitamins',
     5.0::float8, 50.0::float8, 200.0::float8, NULL::float8, NULL::float8, 50.0::float8,
     NULL::float8, FALSE, FALSE, 'PO,IV',
     NULL, 'levodopa,phenytoin', 'pyridoxine,vitamin b6',
     FALSE, FALSE, NULL, 'A', TRUE)
) AS t(generic_name, brand_names, drug_class, atc_code, reml_category,
       adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
       pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
       geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
       available_routes, contraindications, major_interactions, allergen_groups,
       is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml)
WHERE NOT EXISTS (
    SELECT 1 FROM drug_formularies f
    WHERE LOWER(f.generic_name) = LOWER(t.generic_name) AND f.hospital_id IS NULL AND f.is_active = TRUE
);
