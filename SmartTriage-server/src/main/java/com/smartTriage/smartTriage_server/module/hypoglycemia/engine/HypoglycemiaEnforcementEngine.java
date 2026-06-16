package com.smartTriage.smartTriage_server.module.hypoglycemia.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.HypoglycemiaSeverity;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * HypoglycemiaEnforcementEngine — enforces mandatory glucose checks and
 * classifies hypoglycemia severity per ADA/WHO bands (Rwanda-adapted).
 *
 * Mandatory glucose-check triggers: AVPU != ALERT, convulsions, coma, altered
 * mental status. Recommended: known diabetic (chronicConditions).
 *
 * Severity (mmol/L), adult/child:
 *   NORMAL   >= 3.9   |   MILD < 3.9   |   MODERATE < 3.0
 *   SEVERE   < 2.2  OR  any hypoglycemia with neuroglycopenia (altered
 *            consciousness / convulsions / coma)
 * Neonatal (< 28 days): treat below 2.6 mmol/L; SEVERE < 2.0 or neuroglycopenic.
 */
@Slf4j
@Component
public class HypoglycemiaEnforcementEngine {

    // Adult/child cut-offs (mmol/L)
    static final double MILD_THRESHOLD = 3.9;       // < 3.9 = hypoglycemic (alert level)
    static final double MODERATE_THRESHOLD = 3.0;   // < 3.0 = clinically significant
    static final double SEVERE_THRESHOLD = 2.2;     // < 2.2 = severe
    // Neonatal cut-offs (mmol/L)
    static final double NEONATAL_NORMAL_THRESHOLD = 2.6;
    static final double NEONATAL_SEVERE_THRESHOLD = 2.0;
    static final long NEONATE_MAX_AGE_DAYS = 28;

    /**
     * Enforce glucose-check requirements from triage findings and classify the
     * triage glucose value if present.
     */
    public HypoglycemiaCheckResult enforceGlucoseCheck(Visit visit, TriageRecord triage) {
        List<String> triggerReasons = new ArrayList<>();
        boolean checkMandatory = false;
        boolean neuroglycopenia = false;

        if (triage != null) {
            if (triage.getAvpu() != null && triage.getAvpu() != AvpuScore.ALERT) {
                triggerReasons.add("altered_consciousness (AVPU: " + triage.getAvpu().getDescription() + ")");
                checkMandatory = true;
                neuroglycopenia = true;
            }
            if (triage.isHasConvulsions()) {
                triggerReasons.add("convulsions");
                checkMandatory = true;
                neuroglycopenia = true;
            }
            if (triage.isHasComa()) {
                triggerReasons.add("coma");
                checkMandatory = true;
                neuroglycopenia = true;
            }
            if (triage.isVuAlteredMentalStatus()) {
                triggerReasons.add("altered_mental_status");
                checkMandatory = true;
                neuroglycopenia = true;
            }
        }

        boolean isKnownDiabetic = false;
        if (visit.getPatient() != null && visit.getPatient().getChronicConditions() != null) {
            String conditions = visit.getPatient().getChronicConditions().toLowerCase();
            if (conditions.contains("diabetes") || conditions.contains("diabetic") || conditions.contains("dm")) {
                triggerReasons.add("known_diabetic");
                isKnownDiabetic = true;
            }
        }

        boolean requiresCheck = checkMandatory || isKnownDiabetic;
        Double glucoseValue = triage != null ? resolveGlucoseValue(triage) : null;
        return interpret(visit, glucoseValue, neuroglycopenia, requiresCheck, checkMandatory, triggerReasons);
    }

    /**
     * Interpret a glucose reading from ANY source (manual/POC vitals, IoT stream,
     * or triage) — the shared classification path. {@code neuroglycopenia} is true
     * when the patient also has altered consciousness / convulsions / coma.
     */
    public HypoglycemiaCheckResult interpret(Visit visit, Double glucose, boolean neuroglycopenia,
                                             boolean requiresCheck, boolean checkMandatory,
                                             List<String> triggerReasons) {
        boolean neonate = isNeonate(visit);
        HypoglycemiaSeverity severity = classify(glucose, neonate, neuroglycopenia);
        String treatmentProtocol = severity.isHypoglycemic() ? treatmentProtocol(visit, neonate) : null;

        if (severity == HypoglycemiaSeverity.SEVERE) {
            log.warn("SEVERE hypoglycemia: glucose={} mmol/L, neonate={}, visit={}", glucose, neonate, visit.getId());
        } else if (severity.isHypoglycemic()) {
            log.info("{} hypoglycemia: glucose={} mmol/L, visit={}", severity, glucose, visit.getId());
        }

        return new HypoglycemiaCheckResult(requiresCheck, checkMandatory, glucose,
                severity, neonate, treatmentProtocol,
                triggerReasons == null ? List.of() : triggerReasons);
    }

    /** Classify a glucose value into a severity band, age- and symptom-aware. */
    public HypoglycemiaSeverity classify(Double glucose, boolean neonate, boolean neuroglycopenia) {
        if (glucose == null) return HypoglycemiaSeverity.PENDING_CHECK;

        if (neonate) {
            if (glucose >= NEONATAL_NORMAL_THRESHOLD) return HypoglycemiaSeverity.NORMAL;
            // Any neonatal hypoglycemia is treated; very low or symptomatic is severe.
            if (glucose < NEONATAL_SEVERE_THRESHOLD || neuroglycopenia) return HypoglycemiaSeverity.SEVERE;
            return HypoglycemiaSeverity.MODERATE;
        }

        if (glucose >= MILD_THRESHOLD) return HypoglycemiaSeverity.NORMAL;
        // Hypoglycemic. Severe = profoundly low OR clinically-significant-low WITH neuroglycopenia.
        if (glucose < SEVERE_THRESHOLD || (glucose < MODERATE_THRESHOLD && neuroglycopenia)) {
            return HypoglycemiaSeverity.SEVERE;
        }
        if (glucose < MODERATE_THRESHOLD) return HypoglycemiaSeverity.MODERATE;
        return HypoglycemiaSeverity.MILD;
    }

    private boolean isNeonate(Visit visit) {
        try {
            if (visit.getPatient() != null && visit.getPatient().getDateOfBirth() != null) {
                long days = ChronoUnit.DAYS.between(visit.getPatient().getDateOfBirth(), LocalDate.now());
                return days >= 0 && days < NEONATE_MAX_AGE_DAYS;
            }
        } catch (Exception ignored) {
            // unknown age → not treated as neonate
        }
        return false;
    }

    private Double resolveGlucoseValue(TriageRecord triage) {
        if (triage.getBloodGlucose() != null) return triage.getBloodGlucose();
        if (triage.getConvulsionGlucose() != null) return triage.getConvulsionGlucose();
        if (triage.getComaGlucose() != null) return triage.getComaGlucose();
        if (triage.getVuNeurologicalGlucose() != null) return triage.getVuNeurologicalGlucose();
        return null;
    }

    private String treatmentProtocol(Visit visit, boolean neonate) {
        if (neonate) {
            return "NEONATAL: 2 mL/kg of 10% dextrose IV/IO bolus, then a 10% dextrose infusion. "
                    + "Recheck glucose in 15–30 minutes; involve pediatrics/neonatology.";
        }
        if (visit.isPediatric()) {
            return "PEDIATRIC: 5 mL/kg of 10% dextrose IV. Recheck glucose in 15 minutes. "
                    + "Repeat dextrose if still < 3.0 mmol/L.";
        }
        return "ADULT: 50 mL of 50% dextrose IV (or 200 mL of 10%). If conscious and able to swallow, "
                + "15–20 g oral fast-acting carbohydrate. Recheck glucose in 15 minutes. Repeat if still "
                + "< 3.0 mmol/L; consider a dextrose infusion.";
    }

    /**
     * Result of a glucose-check enforcement / interpretation.
     */
    public record HypoglycemiaCheckResult(
            boolean requiresCheck,
            boolean checkMandatory,
            Double glucoseValue,
            HypoglycemiaSeverity severity,
            boolean neonatal,
            String treatmentProtocol,
            List<String> triggerReasons
    ) {
        public boolean isHypoglycemic() {
            return severity != null && severity.isHypoglycemic();
        }
    }
}
