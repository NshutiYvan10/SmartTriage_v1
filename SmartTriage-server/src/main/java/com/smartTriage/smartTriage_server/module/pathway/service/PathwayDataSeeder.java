package com.smartTriage.smartTriage_server.module.pathway.service;

import com.smartTriage.smartTriage_server.common.enums.PathwayCategory;
import com.smartTriage.smartTriage_server.module.pathway.entity.ClinicalPathway;
import com.smartTriage.smartTriage_server.module.pathway.entity.PathwayStep;
import com.smartTriage.smartTriage_server.module.pathway.repository.ClinicalPathwayRepository;
import com.smartTriage.smartTriage_server.module.pathway.repository.PathwayStepRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PathwayDataSeeder — seeds initial Rwanda-specific clinical pathways on startup.
 *
 * These evidence-based pathways cover the most common emergency presentations
 * in the Rwandan context, referencing national and international guidelines.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PathwayDataSeeder {

    private final ClinicalPathwayRepository pathwayRepository;
    private final PathwayStepRepository stepRepository;

    @PostConstruct
    @Transactional
    public void seedPathways() {
        log.info("Checking clinical pathway seed data...");

        seedSevereMalaria();
        seedHeadTrauma();
        seedAcuteAsthma();
        seedAcuteCoronarySyndrome();
        seedStatusEpilepticus();
        seedObstetricEmergency();
        seedSepsisManagement();
        seedSnakebiteManagement();
        seedBurnsManagement();
        seedPoisoningManagement();

        log.info("Clinical pathway seed data check complete.");
    }

    // ====================================================================
    // PATHWAY 1: Severe Malaria (MAL-SEV)
    // ====================================================================

    private void seedSevereMalaria() {
        if (pathwayRepository.existsByPathwayCode("MAL-SEV")) return;

        ClinicalPathway pathway = pathwayRepository.save(ClinicalPathway.builder()
                .pathwayCode("MAL-SEV")
                .pathwayName("Severe Malaria Management")
                .category(PathwayCategory.MALARIA)
                .description("Evidence-based protocol for management of severe malaria in the emergency department. "
                        + "Malaria is the leading cause of morbidity in Rwanda. Severe malaria requires "
                        + "immediate IV artesunate and aggressive supportive care.")
                .targetPopulation("All")
                .protocolVersion("2.0")
                .sourceGuideline("Rwanda MoH National Malaria Treatment Guidelines 2023; WHO Severe Malaria Guidelines 2022")
                .build());

        addStep(pathway, 1, "Confirm diagnosis",
                "Perform malaria RDT or blood smear. Identify Plasmodium species. If RDT positive or "
                + "clinical suspicion high, proceed with treatment while awaiting confirmation.",
                5, true, "Assessment");
        addStep(pathway, 2, "Assess severity criteria",
                "Check for severe malaria criteria: cerebral malaria (GCS <11 or BCS <3), severe anemia "
                + "(Hb <5 g/dL), respiratory distress (acidotic breathing), hypoglycemia (<3.0 mmol/L), "
                + "shock (MAP <65), prostration, repeated convulsions (>2 in 24h), jaundice, "
                + "significant bleeding, pulmonary edema.",
                5, true, "Assessment");
        addStep(pathway, 3, "Start IV Artesunate",
                "Administer IV Artesunate 2.4 mg/kg at 0 hours. Repeat at 12 hours and 24 hours, then "
                + "daily until patient can tolerate oral. This is FIRST-LINE per Rwanda MoH — do NOT "
                + "use IV quinine unless artesunate unavailable.",
                10, true, "Treatment");
        addStep(pathway, 4, "Check blood glucose",
                "Measure blood glucose immediately. If <3.0 mmol/L, treat with 50mL of 50% dextrose IV "
                + "(or 1mL/kg of 10% dextrose in children). Monitor glucose every 4 hours.",
                5, true, "Investigation");
        addStep(pathway, 5, "Check hemoglobin",
                "Obtain FBC/Hb. If Hb <5 g/dL, initiate blood transfusion with packed RBCs. "
                + "Cross-match blood urgently. Target Hb >7 g/dL.",
                15, true, "Investigation");
        addStep(pathway, 6, "IV fluid management",
                "Establish IV access. Administer careful IV fluids — avoid overhydration. "
                + "Use Normal Saline or Ringer's Lactate. Monitor for signs of fluid overload "
                + "(pulmonary edema, raised JVP). In children, use 20mL/kg bolus if shocked.",
                15, true, "Treatment");
        addStep(pathway, 7, "Monitor parasitemia",
                "Repeat parasitemia count at 24h, 48h, and 72h to confirm treatment response. "
                + "Expect >75% parasite clearance by 48h. Alert if no improvement.",
                null, true, "Monitoring");
        addStep(pathway, 8, "Switch to oral ACT",
                "When patient can tolerate oral medication, switch to Artemether-Lumefantrine "
                + "(Coartem) for 3-day course. Ensure all 6 doses completed.",
                null, true, "Treatment");

        log.info("Seeded pathway: MAL-SEV (Severe Malaria Management)");
    }

    // ====================================================================
    // PATHWAY 2: Head Trauma (TRA-HEAD)
    // ====================================================================

    private void seedHeadTrauma() {
        if (pathwayRepository.existsByPathwayCode("TRA-HEAD")) return;

        ClinicalPathway pathway = pathwayRepository.save(ClinicalPathway.builder()
                .pathwayCode("TRA-HEAD")
                .pathwayName("Head Trauma Management")
                .category(PathwayCategory.TRAUMA)
                .description("Systematic protocol for evaluation and management of traumatic brain injury. "
                        + "Road traffic injuries are a leading cause of head trauma in Rwanda.")
                .targetPopulation("All")
                .protocolVersion("1.0")
                .sourceGuideline("ATLS Guidelines; Rwanda Surgical Capacity Assessment 2022")
                .build());

        addStep(pathway, 1, "Primary survey ABCDE",
                "Airway with C-spine protection, Breathing, Circulation, Disability (AVPU/GCS), "
                + "Exposure. Address life threats immediately. Maintain SpO2 >94%.",
                5, true, "Assessment");
        addStep(pathway, 2, "GCS assessment",
                "Perform full Glasgow Coma Scale assessment (Eye + Verbal + Motor). "
                + "Document baseline GCS. Categorize: Mild (13-15), Moderate (9-12), Severe (3-8).",
                5, true, "Assessment");
        addStep(pathway, 3, "C-spine immobilization",
                "Apply cervical collar if mechanism suggests C-spine injury: fall >1m, "
                + "high-speed RTA, assault, diving injury, or if patient has neck pain/tenderness. "
                + "Maintain until C-spine cleared clinically or by imaging.",
                5, true, "Treatment");
        addStep(pathway, 4, "CT Head scan",
                "Request CT Head if: GCS <15, focal neurological deficit, suspected skull fracture, "
                + "post-traumatic seizure, vomiting >1 episode, amnesia >30min, "
                + "dangerous mechanism, coagulopathy. Target: within 15 minutes for GCS <13.",
                15, true, "Investigation");
        addStep(pathway, 5, "Neurosurgical consultation",
                "Request neurosurgical consult if: GCS <9, CT showing intracranial hemorrhage, "
                + "depressed skull fracture, focal deficit, deteriorating GCS, penetrating injury.",
                20, true, "Assessment");
        addStep(pathway, 6, "ICP management if indicated",
                "If signs of raised ICP: head elevation 30 degrees, IV Mannitol 0.5-1g/kg over 15min "
                + "or hypertonic saline 3% 250mL. Maintain MAP >80mmHg, avoid hyperthermia.",
                30, false, "Treatment");
        addStep(pathway, 7, "Serial neurological observations",
                "Perform and document neurological observations every 15 minutes for GCS <9, "
                + "every 30 minutes for GCS 9-14, every hour for GCS 15. "
                + "Alert immediately if GCS drops by >2 points.",
                null, true, "Monitoring");
        addStep(pathway, 8, "Disposition decision",
                "ICU admission if GCS <9. Ward admission with neuro obs if GCS 9-14. "
                + "Consider discharge with head injury advice sheet if GCS 15, normal CT, "
                + "and responsible adult for observation.",
                null, true, "Disposition");

        log.info("Seeded pathway: TRA-HEAD (Head Trauma Management)");
    }

    // ====================================================================
    // PATHWAY 3: Acute Asthma (RESP-ASTHMA)
    // ====================================================================

    private void seedAcuteAsthma() {
        if (pathwayRepository.existsByPathwayCode("RESP-ASTHMA")) return;

        ClinicalPathway pathway = pathwayRepository.save(ClinicalPathway.builder()
                .pathwayCode("RESP-ASTHMA")
                .pathwayName("Acute Asthma Management")
                .category(PathwayCategory.RESPIRATORY)
                .description("Evidence-based protocol for acute asthma exacerbation in the ED. "
                        + "Classifies severity and escalates treatment accordingly.")
                .targetPopulation("All")
                .protocolVersion("1.0")
                .sourceGuideline("GINA Guidelines 2023; Rwanda Essential Medicines List")
                .build());

        addStep(pathway, 1, "Assess severity",
                "Classify: Mild/Moderate (can talk, RR <25, HR <110, SpO2 >92%), "
                + "Severe (can't complete sentences, RR >25, HR >110, SpO2 90-92%), "
                + "Life-threatening (silent chest, cyanosis, bradycardia, confusion, SpO2 <90%).",
                5, true, "Assessment");
        addStep(pathway, 2, "Salbutamol nebulization",
                "Administer Salbutamol 5mg via nebulizer driven by oxygen. "
                + "In children: 2.5mg for <5 years, 5mg for >5 years.",
                5, true, "Treatment");
        addStep(pathway, 3, "Ipratropium bromide",
                "Add Ipratropium bromide 0.5mg to nebulizer for severe or life-threatening. "
                + "Children: 0.25mg. Can be mixed with salbutamol.",
                5, true, "Treatment");
        addStep(pathway, 4, "Supplemental oxygen",
                "Apply oxygen to maintain SpO2 >92% (>94% in pregnant or cardiac patients). "
                + "Use nasal cannula 2-4 L/min or face mask 6-10 L/min as needed.",
                5, true, "Treatment");
        addStep(pathway, 5, "Systemic corticosteroids",
                "Prednisolone 40-50mg PO (1mg/kg in children, max 40mg) or IV Hydrocortisone "
                + "100mg if unable to take oral. Give EARLY — reduces hospital admission.",
                15, true, "Treatment");
        addStep(pathway, 6, "Reassess after 15-20 minutes",
                "Reassess severity after initial treatment. Check RR, HR, SpO2, ability to speak, "
                + "work of breathing. Good response: SpO2 >92%, can speak, RR normalizing.",
                20, true, "Assessment");
        addStep(pathway, 7, "Repeat nebulization if needed",
                "If incomplete response, repeat salbutamol nebulization every 20 minutes "
                + "for up to 3 doses in the first hour. Add ipratropium to each.",
                40, false, "Treatment");
        addStep(pathway, 8, "IV Magnesium sulfate for life-threatening",
                "If life-threatening or no response to initial treatment: IV Magnesium Sulfate "
                + "2g (1.2-2g) in 100mL NaCl over 20 minutes. Single dose. "
                + "Not for mild/moderate unless refractory.",
                30, false, "Treatment");
        addStep(pathway, 9, "Disposition",
                "Discharge if good response (>1h post-treatment, SpO2 >94% on room air, PEF >75%). "
                + "Admit if poor response, life-threatening features, or psychosocial concerns. "
                + "Provide written action plan and salbutamol inhaler on discharge.",
                null, true, "Disposition");

        log.info("Seeded pathway: RESP-ASTHMA (Acute Asthma Management)");
    }

    // ====================================================================
    // PATHWAY 4: Acute Coronary Syndrome (CARD-ACS)
    // ====================================================================

    private void seedAcuteCoronarySyndrome() {
        if (pathwayRepository.existsByPathwayCode("CARD-ACS")) return;

        ClinicalPathway pathway = pathwayRepository.save(ClinicalPathway.builder()
                .pathwayCode("CARD-ACS")
                .pathwayName("Acute Coronary Syndrome Management")
                .category(PathwayCategory.CARDIAC)
                .description("Protocol for evaluation and initial management of suspected acute coronary "
                        + "syndrome (ACS) including STEMI, NSTEMI, and unstable angina.")
                .targetPopulation("Adult")
                .protocolVersion("1.0")
                .sourceGuideline("AHA/ACC ACS Guidelines 2023; Adapted for Rwanda resource setting")
                .build());

        addStep(pathway, 1, "12-lead ECG",
                "Obtain 12-lead ECG within 10 minutes of arrival. Interpret for STEMI criteria "
                + "(ST elevation >1mm in 2 contiguous leads), ST depression, T-wave inversion, "
                + "new LBBB. Repeat if initial ECG non-diagnostic but high suspicion.",
                10, true, "Investigation");
        addStep(pathway, 2, "Aspirin 300mg",
                "Give Aspirin 300mg chewed immediately (unless true aspirin allergy). "
                + "This is time-critical — do not delay for other investigations.",
                5, true, "Treatment");
        addStep(pathway, 3, "IV access and bloods",
                "Establish IV access. Send troponin, FBC, U&E, glucose, coagulation studies. "
                + "High-sensitivity troponin if available — repeat at 3h if initial negative.",
                10, true, "Investigation");
        addStep(pathway, 4, "Pain management",
                "GTN sublingual 0.4mg (if SBP >90). IV Morphine 2-5mg for persistent pain. "
                + "Oxygen only if SpO2 <94%. Avoid IM injections.",
                15, true, "Treatment");
        addStep(pathway, 5, "Anticoagulation",
                "Enoxaparin 1mg/kg SC or Unfractionated Heparin 60 IU/kg IV bolus. "
                + "Clopidogrel 300mg loading dose.",
                20, true, "Treatment");
        addStep(pathway, 6, "Risk stratification",
                "Apply TIMI or GRACE score. STEMI → activate cath lab or thrombolysis pathway. "
                + "High-risk NSTEMI → cardiology consultation for early invasive strategy.",
                30, true, "Assessment");
        addStep(pathway, 7, "Continuous monitoring",
                "Continuous cardiac monitoring. Repeat ECG at 60 minutes and with any change in symptoms. "
                + "Monitor for arrhythmias — have defibrillator at bedside.",
                null, true, "Monitoring");
        addStep(pathway, 8, "Disposition",
                "STEMI: CCU/ICU. High-risk NSTEMI: CCU with early cardiology review. "
                + "Low-risk chest pain with negative troponin x2: consider discharge with "
                + "outpatient follow-up.",
                null, true, "Disposition");

        log.info("Seeded pathway: CARD-ACS (Acute Coronary Syndrome Management)");
    }

    // ====================================================================
    // PATHWAY 5: Status Epilepticus (NEURO-SEIZ)
    // ====================================================================

    private void seedStatusEpilepticus() {
        if (pathwayRepository.existsByPathwayCode("NEURO-SEIZ")) return;

        ClinicalPathway pathway = pathwayRepository.save(ClinicalPathway.builder()
                .pathwayCode("NEURO-SEIZ")
                .pathwayName("Status Epilepticus Management")
                .category(PathwayCategory.NEUROLOGICAL)
                .description("Time-critical protocol for management of prolonged seizures and status "
                        + "epilepticus. Seizures >5 minutes require active management.")
                .targetPopulation("All")
                .protocolVersion("1.0")
                .sourceGuideline("ILAE Status Epilepticus Guidelines; WHO Essential Medicines")
                .build());

        addStep(pathway, 1, "Secure airway and position",
                "Place patient in recovery position. Clear airway. Apply oxygen. "
                + "Do NOT insert anything into the mouth. Suction if needed. "
                + "Note time of seizure onset.",
                2, true, "Treatment");
        addStep(pathway, 2, "Check blood glucose",
                "Immediate bedside glucose. If <3.0 mmol/L: IV Dextrose 50% 50mL (adults) "
                + "or Dextrose 10% 5mL/kg (children). Hypoglycemia is a common reversible cause.",
                5, true, "Investigation");
        addStep(pathway, 3, "First-line: IV Diazepam",
                "If seizure >5 minutes: IV Diazepam 10mg (0.3mg/kg in children, max 10mg) "
                + "given slowly over 2 minutes. Can give PR if no IV access. "
                + "OR Lorazepam 4mg IV (0.1mg/kg).",
                5, true, "Treatment");
        addStep(pathway, 4, "Repeat benzodiazepine if needed",
                "If seizure continues after 5 minutes: repeat IV Diazepam once. "
                + "Maximum 2 doses of benzodiazepine. Monitor respiratory status closely.",
                10, true, "Treatment");
        addStep(pathway, 5, "Second-line: IV Phenytoin",
                "If seizure persists after 2 doses benzodiazepine: IV Phenytoin loading dose "
                + "15-20mg/kg in Normal Saline over 20 minutes (max rate 50mg/min). "
                + "ECG monitoring required. Alternative: IV Phenobarbital if phenytoin unavailable.",
                20, true, "Treatment");
        addStep(pathway, 6, "Blood investigations",
                "Send FBC, U&E, calcium, magnesium, LFTs, AED levels if on medications. "
                + "Blood cultures if febrile. Toxicology screen if overdose suspected.",
                15, true, "Investigation");
        addStep(pathway, 7, "Consider intubation and ICU",
                "If refractory status (>30 min despite treatment): prepare for intubation and "
                + "ICU admission for IV midazolam or propofol infusion. "
                + "Consult neurology/ICU early.",
                30, false, "Treatment");
        addStep(pathway, 8, "Post-seizure management",
                "Once seizure controlled: post-ictal monitoring, neurological observations q30min, "
                + "investigate cause (CT if new onset, LP if meningitis suspected), "
                + "commence/adjust maintenance AEDs.",
                null, true, "Monitoring");

        log.info("Seeded pathway: NEURO-SEIZ (Status Epilepticus Management)");
    }

    // ====================================================================
    // PATHWAY 6: Obstetric Emergency (OBS-EMERG)
    // ====================================================================

    private void seedObstetricEmergency() {
        if (pathwayRepository.existsByPathwayCode("OBS-EMERG")) return;

        ClinicalPathway pathway = pathwayRepository.save(ClinicalPathway.builder()
                .pathwayCode("OBS-EMERG")
                .pathwayName("Obstetric Emergency Management")
                .category(PathwayCategory.OBSTETRIC)
                .description("Protocol for management of obstetric emergencies including antepartum "
                        + "hemorrhage, eclampsia, and obstetric shock. Maternal mortality reduction "
                        + "is a key health priority in Rwanda.")
                .targetPopulation("Adult")
                .protocolVersion("1.0")
                .sourceGuideline("Rwanda MoH Maternal Health Guidelines; WHO Managing Complications in Pregnancy")
                .build());

        addStep(pathway, 1, "Rapid obstetric assessment",
                "Gestational age, presenting complaint, vaginal bleeding assessment, "
                + "fetal heart rate (doppler), uterine tone, blood pressure. "
                + "Assess for eclampsia (seizures + hypertension + proteinuria).",
                5, true, "Assessment");
        addStep(pathway, 2, "Two large-bore IV lines",
                "Establish 2 large-bore (16-18G) IV lines. Start Normal Saline or Ringer's Lactate. "
                + "Cross-match 4 units packed RBCs. Send FBC, coagulation, group & screen.",
                10, true, "Treatment");
        addStep(pathway, 3, "If eclampsia: IV Magnesium Sulfate",
                "Loading dose: 4g IV over 15-20 minutes PLUS 10g IM (5g each buttock). "
                + "Maintenance: 5g IM every 4 hours for 24 hours after last seizure. "
                + "Monitor: respiratory rate >16, urine output >30mL/h, knee reflexes present.",
                15, true, "Treatment");
        addStep(pathway, 4, "Control hypertension",
                "If SBP >160 or DBP >110: IV Hydralazine 5mg slow bolus, repeat q20min. "
                + "OR Nifedipine 10mg sublingual. Target: SBP 130-150, DBP 80-100. "
                + "Avoid precipitous drops.",
                15, true, "Treatment");
        addStep(pathway, 5, "Obstetric consultation",
                "Urgent obstetric team notification. Decision for emergency cesarean section "
                + "if: placental abruption with fetal distress, placenta previa with heavy bleeding, "
                + "uterine rupture, cord prolapse, eclampsia with failed seizure control.",
                10, true, "Assessment");
        addStep(pathway, 6, "Blood product preparation",
                "If active hemorrhage: activate massive transfusion protocol if available. "
                + "Transfuse O-negative if cross-matched blood not yet available. "
                + "Target Hb >7, platelets >50, fibrinogen >2.",
                20, true, "Treatment");
        addStep(pathway, 7, "Continuous fetal monitoring",
                "Continuous CTG if >28 weeks and viable. Monitor fetal heart rate for decelerations. "
                + "Alert if FHR <110 or >160 or decelerations present.",
                null, true, "Monitoring");
        addStep(pathway, 8, "Disposition",
                "Theatre for emergency cesarean, or labor ward for continued management. "
                + "ICU/HDU if eclampsia, massive hemorrhage, or hemodynamic instability. "
                + "Document estimated blood loss.",
                null, true, "Disposition");

        log.info("Seeded pathway: OBS-EMERG (Obstetric Emergency Management)");
    }

    // ====================================================================
    // PATHWAY 7: Sepsis Management (INF-SEPSIS)
    // ====================================================================

    private void seedSepsisManagement() {
        if (pathwayRepository.existsByPathwayCode("INF-SEPSIS")) return;

        ClinicalPathway pathway = pathwayRepository.save(ClinicalPathway.builder()
                .pathwayCode("INF-SEPSIS")
                .pathwayName("Sepsis Management — 1-Hour Bundle")
                .category(PathwayCategory.INFECTIOUS_DISEASE)
                .description("Time-critical sepsis bundle based on the Surviving Sepsis Campaign. "
                        + "All bundle elements must be completed within 1 hour of recognition.")
                .targetPopulation("All")
                .protocolVersion("1.0")
                .sourceGuideline("Surviving Sepsis Campaign 2021; Rwanda MoH Infection Management Guidelines")
                .build());

        addStep(pathway, 1, "Measure lactate",
                "Obtain serum lactate level. If lactate >2 mmol/L, re-measure within 2-4 hours. "
                + "Lactate >4 mmol/L indicates tissue hypoperfusion.",
                10, true, "Investigation");
        addStep(pathway, 2, "Obtain blood cultures",
                "Draw 2 sets of blood cultures (aerobic + anaerobic) from different sites BEFORE "
                + "antibiotics. Do not delay antibiotics if cultures cannot be drawn quickly.",
                15, true, "Investigation");
        addStep(pathway, 3, "Administer broad-spectrum antibiotics",
                "IV antibiotics within 1 hour of sepsis recognition. "
                + "Suggested: Ceftriaxone 2g IV + Metronidazole 500mg IV for community-acquired. "
                + "Adjust based on suspected source and local antibiogram.",
                15, true, "Treatment");
        addStep(pathway, 4, "Fluid resuscitation",
                "If hypotensive (MAP <65) or lactate >4: 30mL/kg crystalloid within first 3 hours. "
                + "Reassess after each 500mL bolus. Monitor for fluid overload.",
                30, true, "Treatment");
        addStep(pathway, 5, "Vasopressors if needed",
                "If MAP remains <65 after adequate fluid resuscitation: start Noradrenaline "
                + "(Norepinephrine) via central line, target MAP >65. "
                + "Consider ICU admission for vasopressor support.",
                30, false, "Treatment");
        addStep(pathway, 6, "Source control",
                "Identify and control source of infection: drain abscess, remove infected device, "
                + "surgical consultation for peritonitis, debridement for necrotizing fasciitis. "
                + "Source control within 6-12 hours.",
                60, true, "Treatment");
        addStep(pathway, 7, "Reassess clinical response",
                "Reassess after fluid bolus: MAP, lactate clearance, urine output (>0.5mL/kg/h), "
                + "mental status, capillary refill. If not improving, escalate care.",
                60, true, "Assessment");
        addStep(pathway, 8, "Disposition",
                "ICU if requiring vasopressors, mechanical ventilation, or organ support. "
                + "HDU if stable on antibiotics and fluids. Ward if mild sepsis responding well.",
                null, true, "Disposition");

        log.info("Seeded pathway: INF-SEPSIS (Sepsis Management)");
    }

    // ====================================================================
    // PATHWAY 8: Snakebite Management (BITE-SNAKE)
    // ====================================================================

    private void seedSnakebiteManagement() {
        if (pathwayRepository.existsByPathwayCode("BITE-SNAKE")) return;

        ClinicalPathway pathway = pathwayRepository.save(ClinicalPathway.builder()
                .pathwayCode("BITE-SNAKE")
                .pathwayName("Snakebite Management")
                .category(PathwayCategory.SNAKEBITE)
                .description("Protocol for management of snakebite envenomation. Common in rural Rwanda, "
                        + "particularly during agricultural seasons. Boomslang, puff adder, and "
                        + "green/black mamba are the most dangerous species in the region.")
                .targetPopulation("All")
                .protocolVersion("1.0")
                .sourceGuideline("WHO Guidelines for Management of Snakebites 2016; Rwanda MoH Tropical Disease Guidelines")
                .build());

        addStep(pathway, 1, "Immobilize affected limb",
                "Immobilize the bitten limb with a splint below heart level. Remove constrictive "
                + "clothing/jewelry. Do NOT apply tourniquet, do NOT cut/suck wound. "
                + "Mark leading edge of swelling with pen and time.",
                5, true, "Treatment");
        addStep(pathway, 2, "Assess for envenomation",
                "Check for local signs: swelling, pain, bruising, necrosis. "
                + "Systemic signs: coagulopathy (bleeding gums, oozing), neurotoxicity (ptosis, "
                + "dysphagia, weakness), hemolysis, renal failure. "
                + "20-minute whole blood clotting test (WBCT20).",
                10, true, "Assessment");
        addStep(pathway, 3, "IV access and baseline bloods",
                "Establish IV access. Send FBC, coagulation (INR/PT), U&E, creatinine, CK. "
                + "Group and save. Urinalysis for myoglobinuria/hemoglobinuria.",
                10, true, "Investigation");
        addStep(pathway, 4, "Antivenom if indicated",
                "Administer polyvalent antivenom if signs of systemic envenomation. "
                + "Give IV in 250mL Normal Saline over 30-60 minutes. "
                + "Have adrenaline ready for anaphylaxis (1:1000, 0.5mg IM). "
                + "Premedicate with IV hydrocortisone and promethazine if available.",
                30, true, "Treatment");
        addStep(pathway, 5, "Pain management",
                "IV Paracetamol 1g and/or IV Tramadol 50-100mg. "
                + "Avoid NSAIDs (may worsen bleeding). Avoid IM injections if coagulopathy.",
                15, true, "Treatment");
        addStep(pathway, 6, "Monitor for antivenom reaction",
                "Observe closely for 1 hour after antivenom: anaphylaxis (urticaria, bronchospasm, "
                + "hypotension), serum sickness (fever, arthralgia, rash at 5-14 days). "
                + "Repeat WBCT20 at 6 hours — if still non-clotting, repeat antivenom dose.",
                60, true, "Monitoring");
        addStep(pathway, 7, "Wound care",
                "Clean wound with antiseptic. Tetanus prophylaxis if indicated. "
                + "No debridement in first 24 hours. Watch for compartment syndrome "
                + "(pain on passive stretch, tense swelling). Fasciotomy only if confirmed.",
                null, false, "Treatment");
        addStep(pathway, 8, "Disposition",
                "Admit all patients with systemic envenomation for minimum 24 hours observation. "
                + "Repeat coagulation studies at 6h and 24h. Discharge if stable with no envenomation "
                + "signs after 24h observation.",
                null, true, "Disposition");

        log.info("Seeded pathway: BITE-SNAKE (Snakebite Management)");
    }

    // ====================================================================
    // PATHWAY 9: Burns Management (BURN-MGMT)
    // ====================================================================

    private void seedBurnsManagement() {
        if (pathwayRepository.existsByPathwayCode("BURN-MGMT")) return;

        ClinicalPathway pathway = pathwayRepository.save(ClinicalPathway.builder()
                .pathwayCode("BURN-MGMT")
                .pathwayName("Burns Management")
                .category(PathwayCategory.BURNS)
                .description("Protocol for initial ED management of burn injuries. House fires, "
                        + "cooking accidents, and scalds are common burn mechanisms in Rwanda.")
                .targetPopulation("All")
                .protocolVersion("1.0")
                .sourceGuideline("ISBI Practice Guidelines for Burn Care 2023; WHO Emergency Care")
                .build());

        addStep(pathway, 1, "Stop the burning process",
                "Remove patient from source. Remove clothing/jewelry from burned area. "
                + "Cool with running tepid water for 20 minutes (within first 3 hours). "
                + "Do NOT use ice. Cover with clean cling film.",
                5, true, "Treatment");
        addStep(pathway, 2, "Primary survey ABCDE",
                "Assess airway: singed nasal hairs, soot in mouth, stridor, hoarse voice = "
                + "suspect inhalation injury → early intubation. "
                + "Breathing: circumferential chest burns may need escharotomy.",
                5, true, "Assessment");
        addStep(pathway, 3, "Estimate burn area (TBSA)",
                "Use Rule of Nines (adult) or Lund-Browder chart (pediatric). "
                + "Only count partial and full thickness. Palm of patient's hand = 1% TBSA. "
                + "Document depth: superficial, partial, full thickness.",
                10, true, "Assessment");
        addStep(pathway, 4, "IV fluid resuscitation — Parkland formula",
                "If TBSA >15% (adult) or >10% (child): Parkland formula = 4mL x weight(kg) x %TBSA "
                + "of Ringer's Lactate over 24 hours. Give half in first 8 hours from burn time "
                + "(not arrival time). Titrate to urine output 0.5-1 mL/kg/h.",
                15, true, "Treatment");
        addStep(pathway, 5, "Pain management",
                "Burns are extremely painful. IV Morphine 0.1mg/kg titrated. "
                + "IV Paracetamol. Ketamine 0.3mg/kg IV for procedural sedation during dressings. "
                + "Ensure adequate analgesia BEFORE wound care.",
                15, true, "Treatment");
        addStep(pathway, 6, "Wound care",
                "Clean with Normal Saline. Apply Silver Sulfadiazine cream or honey-based dressings "
                + "(locally available in Rwanda). Blister management: leave small blisters, "
                + "aspirate large tense blisters. Tetanus prophylaxis.",
                30, true, "Treatment");
        addStep(pathway, 7, "Investigations",
                "FBC, U&E, glucose, coagulation if >20% TBSA. "
                + "ABG/VBG if inhalation injury. "
                + "COHb level if enclosed space fire.",
                20, false, "Investigation");
        addStep(pathway, 8, "Referral criteria and disposition",
                "Refer to burns center if: >20% TBSA (>10% child), full thickness >5%, "
                + "burns to face/hands/feet/genitalia/major joints, inhalation injury, "
                + "circumferential burns, electrical/chemical burns. "
                + "Consider transfer to referral hospital if local capacity insufficient.",
                null, true, "Disposition");

        log.info("Seeded pathway: BURN-MGMT (Burns Management)");
    }

    // ====================================================================
    // PATHWAY 10: Poisoning Management (TOX-POIS)
    // ====================================================================

    private void seedPoisoningManagement() {
        if (pathwayRepository.existsByPathwayCode("TOX-POIS")) return;

        ClinicalPathway pathway = pathwayRepository.save(ClinicalPathway.builder()
                .pathwayCode("TOX-POIS")
                .pathwayName("Poisoning and Overdose Management")
                .category(PathwayCategory.POISONING)
                .description("Protocol for management of acute poisoning and drug overdose. "
                        + "Organophosphate poisoning from agricultural chemicals is particularly "
                        + "common in rural Rwanda.")
                .targetPopulation("All")
                .protocolVersion("1.0")
                .sourceGuideline("WHO Guidelines for Poison Management; Rwanda Poison Information")
                .build());

        addStep(pathway, 1, "Primary survey and stabilize",
                "ABCDE approach. Secure airway if GCS <8. Treat seizures, hypotension, arrhythmias. "
                + "Remove contaminated clothing. Decontaminate skin if dermal exposure.",
                5, true, "Assessment");
        addStep(pathway, 2, "Identify the poison",
                "History: what, when, how much, route. Bring container/label if available. "
                + "Toxidrome recognition: cholinergic (organophosphate), anticholinergic, "
                + "opioid, sympathomimetic, sedative-hypnotic.",
                10, true, "Assessment");
        addStep(pathway, 3, "Specific antidote if available",
                "Organophosphate: Atropine 2mg IV every 5 min until secretions dry + Pralidoxime. "
                + "Opioid: Naloxone 0.4-2mg IV. Paracetamol: N-Acetylcysteine if <24h. "
                + "Benzodiazepine: Flumazenil (CAUTION in chronic use/seizure risk).",
                15, true, "Treatment");
        addStep(pathway, 4, "Decontamination if appropriate",
                "Activated charcoal 1g/kg (max 50g) if ingestion <1 hour and patient alert. "
                + "Contraindicated: corrosives, hydrocarbons, reduced GCS, unprotected airway. "
                + "Gastric lavage rarely indicated.",
                15, false, "Treatment");
        addStep(pathway, 5, "IV access and investigations",
                "Establish IV access. Send FBC, U&E, LFTs, glucose, coagulation, blood gas, "
                + "paracetamol level, salicylate level. ECG for QRS/QTc prolongation. "
                + "Urine toxicology screen.",
                15, true, "Investigation");
        addStep(pathway, 6, "Supportive care",
                "IV fluids for hypotension. Vasopressors if refractory. Benzodiazepines for agitation "
                + "or seizures. Temperature management. Correct electrolyte abnormalities. "
                + "Continuous cardiac monitoring.",
                30, true, "Treatment");
        addStep(pathway, 7, "Psychiatric assessment",
                "If intentional self-harm: psychiatric assessment when medically stable. "
                + "1:1 observation. Remove potential means of harm from environment. "
                + "Assess suicide risk.",
                null, false, "Assessment");
        addStep(pathway, 8, "Disposition",
                "ICU if: hemodynamic instability, need for intubation, continuous antidote infusion, "
                + "arrhythmias. Ward if stable. Psychiatric admission if intentional and ongoing risk. "
                + "Minimum 6h observation for all poisoning.",
                null, true, "Disposition");

        log.info("Seeded pathway: TOX-POIS (Poisoning and Overdose Management)");
    }

    // ====================================================================
    // HELPER
    // ====================================================================

    private void addStep(ClinicalPathway pathway, int order, String title, String description,
                         Integer timeframeMinutes, boolean mandatory, String category) {
        stepRepository.save(PathwayStep.builder()
                .pathway(pathway)
                .stepOrder(order)
                .stepTitle(title)
                .stepDescription(description)
                .timeframeMinutes(timeframeMinutes)
                .isMandatory(mandatory)
                .category(category)
                .build());
    }
}
