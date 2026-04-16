package com.smartTriage.smartTriage_server.module.icu.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.IcuTriggerType;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * IcuEscalationEngine — clinical decision support for ICU escalation.
 *
 * Evaluates the latest vital signs against ICU admission thresholds.
 * Thresholds are based on standard critical care criteria adapted for
 * Rwanda hospital capacity and resource availability.
 *
 * This engine runs both on-demand (manual trigger) and automatically
 * via the scheduled IcuAutoDetectionService for RED/ORANGE triage patients.
 */
@Slf4j
@Component
public class IcuEscalationEngine {

    private static final double MAP_CRITICAL_THRESHOLD = 65.0;
    private static final int SPO2_CRITICAL_THRESHOLD = 90;
    private static final int RR_CRITICAL_THRESHOLD = 35;
    private static final int GCS_CRITICAL_THRESHOLD = 8;
    private static final int SEPTIC_HR_THRESHOLD = 120;
    private static final int SEPTIC_SBP_THRESHOLD = 90;
    private static final double SEPTIC_TEMP_THRESHOLD = 38.3;
    private static final int CARDIAC_ARREST_HR_THRESHOLD = 30;

    /**
     * Evaluate whether the given vital signs indicate the need for ICU admission.
     *
     * @param vitals the most recent vital signs for the patient
     * @return recommendation with trigger type, reasoning, and stabilization steps
     */
    public IcuEscalationRecommendation evaluate(VitalSigns vitals) {
        if (vitals == null) {
            return new IcuEscalationRecommendation(false, null, "No vital signs available for evaluation", List.of());
        }

        // Check each trigger type in order of clinical severity

        // 1. Post Cardiac Arrest — heart rate < 30 (profound bradycardia / near-arrest)
        if (vitals.getHeartRate() != null && vitals.getHeartRate() < CARDIAC_ARREST_HR_THRESHOLD) {
            return new IcuEscalationRecommendation(
                    true,
                    IcuTriggerType.POST_CARDIAC_ARREST,
                    String.format("Profound bradycardia detected: HR %d bpm (threshold < %d). " +
                            "Suggestive of peri-arrest state requiring immediate ICU intervention.",
                            vitals.getHeartRate(), CARDIAC_ARREST_HR_THRESHOLD),
                    buildStabilizationSteps(IcuTriggerType.POST_CARDIAC_ARREST)
            );
        }

        // 2. Decreased Consciousness — GCS <= 8 or AVPU == UNRESPONSIVE
        if (isDecreasedConsciousness(vitals)) {
            String reasoning = buildConsciousnessReasoning(vitals);
            return new IcuEscalationRecommendation(
                    true,
                    IcuTriggerType.DECREASED_CONSCIOUSNESS,
                    reasoning,
                    buildStabilizationSteps(IcuTriggerType.DECREASED_CONSCIOUSNESS)
            );
        }

        // 3. Hemodynamic Instability — MAP < 65 mmHg
        if (isHemodynamicallyUnstable(vitals)) {
            double map = calculateMAP(vitals.getSystolicBp(), vitals.getDiastolicBp());
            return new IcuEscalationRecommendation(
                    true,
                    IcuTriggerType.HEMODYNAMIC_INSTABILITY,
                    String.format("Hemodynamic instability: MAP %.1f mmHg (threshold < %.1f). " +
                            "BP %d/%d mmHg. Requires vasopressor support and continuous monitoring.",
                            map, MAP_CRITICAL_THRESHOLD,
                            vitals.getSystolicBp(), vitals.getDiastolicBp()),
                    buildStabilizationSteps(IcuTriggerType.HEMODYNAMIC_INSTABILITY)
            );
        }

        // 4. Respiratory Failure — SpO2 < 90% OR RR > 35
        if (isRespiratoryFailure(vitals)) {
            String reasoning = buildRespiratoryReasoning(vitals);
            return new IcuEscalationRecommendation(
                    true,
                    IcuTriggerType.RESPIRATORY_FAILURE,
                    reasoning,
                    buildStabilizationSteps(IcuTriggerType.RESPIRATORY_FAILURE)
            );
        }

        // 5. Septic Shock — HR > 120 AND SBP < 90 AND Temp > 38.3 (simplified qSOFA+)
        if (isSepticShock(vitals)) {
            return new IcuEscalationRecommendation(
                    true,
                    IcuTriggerType.SEPTIC_SHOCK,
                    String.format("Septic shock criteria met: HR %d bpm, SBP %d mmHg, Temp %.1f°C. " +
                            "Tachycardia with hypotension and fever suggestive of septic shock.",
                            vitals.getHeartRate(), vitals.getSystolicBp(), vitals.getTemperature()),
                    buildStabilizationSteps(IcuTriggerType.SEPTIC_SHOCK)
            );
        }

        // No ICU triggers detected
        return new IcuEscalationRecommendation(false, null,
                "No automatic ICU escalation triggers detected in current vital signs.", List.of());
    }

    // --- Threshold checks ---

    private boolean isHemodynamicallyUnstable(VitalSigns vitals) {
        if (vitals.getSystolicBp() == null || vitals.getDiastolicBp() == null) {
            return false;
        }
        double map = calculateMAP(vitals.getSystolicBp(), vitals.getDiastolicBp());
        return map < MAP_CRITICAL_THRESHOLD;
    }

    private boolean isRespiratoryFailure(VitalSigns vitals) {
        boolean spo2Critical = vitals.getSpo2() != null && vitals.getSpo2() < SPO2_CRITICAL_THRESHOLD;
        boolean rrCritical = vitals.getRespiratoryRate() != null && vitals.getRespiratoryRate() > RR_CRITICAL_THRESHOLD;
        return spo2Critical || rrCritical;
    }

    private boolean isDecreasedConsciousness(VitalSigns vitals) {
        boolean gcsCritical = vitals.getGcsScore() != null && vitals.getGcsScore() <= GCS_CRITICAL_THRESHOLD;
        boolean avpuCritical = vitals.getAvpu() == AvpuScore.UNRESPONSIVE;
        return gcsCritical || avpuCritical;
    }

    private boolean isSepticShock(VitalSigns vitals) {
        if (vitals.getHeartRate() == null || vitals.getSystolicBp() == null || vitals.getTemperature() == null) {
            return false;
        }
        return vitals.getHeartRate() > SEPTIC_HR_THRESHOLD
                && vitals.getSystolicBp() < SEPTIC_SBP_THRESHOLD
                && vitals.getTemperature() > SEPTIC_TEMP_THRESHOLD;
    }

    /**
     * Calculate Mean Arterial Pressure.
     * MAP = diastolicBp + (systolicBp - diastolicBp) / 3
     */
    private double calculateMAP(int systolicBp, int diastolicBp) {
        return diastolicBp + (systolicBp - diastolicBp) / 3.0;
    }

    // --- Reasoning builders ---

    private String buildConsciousnessReasoning(VitalSigns vitals) {
        StringBuilder sb = new StringBuilder("Decreased consciousness detected: ");
        if (vitals.getGcsScore() != null && vitals.getGcsScore() <= GCS_CRITICAL_THRESHOLD) {
            sb.append(String.format("GCS %d (threshold <= %d). ", vitals.getGcsScore(), GCS_CRITICAL_THRESHOLD));
        }
        if (vitals.getAvpu() == AvpuScore.UNRESPONSIVE) {
            sb.append("AVPU = UNRESPONSIVE. ");
        }
        sb.append("Airway protection at risk — ICU admission required for monitoring and possible intubation.");
        return sb.toString();
    }

    private String buildRespiratoryReasoning(VitalSigns vitals) {
        StringBuilder sb = new StringBuilder("Respiratory failure detected: ");
        if (vitals.getSpo2() != null && vitals.getSpo2() < SPO2_CRITICAL_THRESHOLD) {
            sb.append(String.format("SpO2 %d%% (threshold < %d%%). ", vitals.getSpo2(), SPO2_CRITICAL_THRESHOLD));
        }
        if (vitals.getRespiratoryRate() != null && vitals.getRespiratoryRate() > RR_CRITICAL_THRESHOLD) {
            sb.append(String.format("RR %d breaths/min (threshold > %d). ",
                    vitals.getRespiratoryRate(), RR_CRITICAL_THRESHOLD));
        }
        sb.append("Patient may require advanced airway management and mechanical ventilation.");
        return sb.toString();
    }

    // --- Stabilization steps adapted for Rwanda hospital capacity ---

    private List<String> buildStabilizationSteps(IcuTriggerType triggerType) {
        List<String> steps = new ArrayList<>();

        switch (triggerType) {
            case HEMODYNAMIC_INSTABILITY -> {
                steps.add("Establish two large-bore IV lines (16G or 18G)");
                steps.add("Start IV Normal Saline 500ml bolus over 15 minutes");
                steps.add("Elevate legs to improve venous return");
                steps.add("Prepare noradrenaline infusion if available (start 0.05 mcg/kg/min)");
                steps.add("Insert urinary catheter — monitor urine output hourly");
                steps.add("Continuous vital signs monitoring every 5 minutes");
                steps.add("Draw blood for FBC, U&E, lactate, blood culture if febrile");
                steps.add("Contact ICU team immediately for bed availability");
            }
            case RESPIRATORY_FAILURE -> {
                steps.add("Position patient upright (45 degrees) to optimize ventilation");
                steps.add("Apply high-flow oxygen via non-rebreather mask at 15L/min");
                steps.add("Prepare bag-valve mask at bedside for emergency ventilation");
                steps.add("Suction airway if secretions present");
                steps.add("Obtain arterial blood gas if available");
                steps.add("Prepare for possible intubation — check laryngoscope and ETT availability");
                steps.add("Continuous SpO2 monitoring");
                steps.add("Contact ICU team for ventilator preparation");
            }
            case DECREASED_CONSCIOUSNESS -> {
                steps.add("Secure airway — jaw thrust maneuver, place in recovery position if no spinal injury");
                steps.add("Apply high-flow oxygen via non-rebreather mask");
                steps.add("Check blood glucose immediately — treat hypoglycemia with 50ml of 50% dextrose IV");
                steps.add("Establish IV access");
                steps.add("Prepare intubation equipment — RSI drugs if available (ketamine 1-2mg/kg)");
                steps.add("Insert nasogastric tube to prevent aspiration");
                steps.add("Neurological observations every 15 minutes (GCS, pupils)");
                steps.add("CT scan if available to rule out intracranial pathology");
            }
            case SEPTIC_SHOCK -> {
                steps.add("Start 1-hour sepsis bundle immediately");
                steps.add("Draw blood cultures before antibiotics (2 sets if possible)");
                steps.add("Administer broad-spectrum IV antibiotics within 1 hour (ceftriaxone 2g IV)");
                steps.add("IV Normal Saline 30ml/kg bolus within first 3 hours");
                steps.add("Measure serum lactate");
                steps.add("Insert urinary catheter — target urine output > 0.5ml/kg/hr");
                steps.add("If hypotension persists after fluid resuscitation, start vasopressors");
                steps.add("Continuous vital signs monitoring every 5 minutes");
                steps.add("Contact ICU team for hemodynamic monitoring setup");
            }
            case POST_CARDIAC_ARREST -> {
                steps.add("Confirm pulse and rhythm — attach cardiac monitor/defibrillator");
                steps.add("Start chest compressions if no pulse (CPR 30:2)");
                steps.add("Administer adrenaline 1mg IV every 3-5 minutes if in cardiac arrest");
                steps.add("Secure advanced airway (intubation) when feasible");
                steps.add("Establish IV/IO access");
                steps.add("Administer atropine 0.5mg IV for severe bradycardia (repeat up to 3mg)");
                steps.add("Prepare transcutaneous pacing if available");
                steps.add("Post-ROSC: targeted temperature management if feasible");
                steps.add("Continuous 12-lead ECG monitoring");
                steps.add("Contact ICU team immediately");
            }
            case STATUS_EPILEPTICUS -> {
                steps.add("Protect airway — position patient on side, suction if needed");
                steps.add("Administer diazepam 10mg IV slowly (or 10mg rectal if no IV access)");
                steps.add("If seizure persists after 5 minutes, repeat diazepam 10mg IV");
                steps.add("Start phenytoin loading dose 15-20mg/kg IV over 20 minutes if available");
                steps.add("Apply high-flow oxygen");
                steps.add("Check blood glucose — treat hypoglycemia");
                steps.add("Continuous monitoring of respiratory status");
                steps.add("Prepare intubation equipment in case of respiratory failure");
            }
            case MASSIVE_HEMORRHAGE -> {
                steps.add("Activate massive transfusion protocol if available");
                steps.add("Establish two large-bore IV lines (14G or 16G)");
                steps.add("Start rapid IV Normal Saline or Ringer's Lactate infusion");
                steps.add("Cross-match and request packed red blood cells urgently");
                steps.add("Apply direct pressure to visible bleeding source");
                steps.add("Elevate legs, keep patient warm (prevent hypothermia)");
                steps.add("Insert urinary catheter to monitor output");
                steps.add("Draw blood for FBC, crossmatch, coagulation screen");
                steps.add("Contact surgical team for possible operative intervention");
            }
            case MULTI_ORGAN_DYSFUNCTION -> {
                steps.add("Establish comprehensive monitoring — vitals every 5 minutes");
                steps.add("Secure two large-bore IV lines");
                steps.add("Insert urinary catheter — monitor hourly urine output");
                steps.add("Draw blood for FBC, U&E, LFTs, coagulation, lactate, blood gas");
                steps.add("Start IV fluid resuscitation cautiously (avoid fluid overload)");
                steps.add("Administer broad-spectrum antibiotics if infection suspected");
                steps.add("Assess each organ system and document findings");
                steps.add("Contact ICU team for multi-organ support planning");
            }
            case POST_OPERATIVE -> {
                steps.add("Ensure adequate pain management (morphine 2-5mg IV titrated)");
                steps.add("Monitor surgical site for active bleeding");
                steps.add("Continuous vital signs monitoring every 15 minutes");
                steps.add("Maintain IV fluid therapy as per surgical team orders");
                steps.add("Monitor urine output hourly");
                steps.add("Assess level of consciousness and compare to pre-operative baseline");
                steps.add("Review surgical notes for anticipated complications");
                steps.add("Contact ICU team for post-operative bed assignment");
            }
            case CLINICAL_JUDGEMENT -> {
                steps.add("Document clinical reasoning for ICU escalation decision");
                steps.add("Ensure continuous vital signs monitoring");
                steps.add("Establish or confirm IV access");
                steps.add("Prepare patient for possible ICU transfer");
                steps.add("Contact ICU team to discuss case");
            }
        }

        return steps;
    }

    /**
     * Immutable record holding the result of an ICU escalation evaluation.
     */
    public record IcuEscalationRecommendation(
            boolean icuRecommended,
            IcuTriggerType triggerType,
            String reasoning,
            List<String> stabilizationSteps
    ) {}
}
