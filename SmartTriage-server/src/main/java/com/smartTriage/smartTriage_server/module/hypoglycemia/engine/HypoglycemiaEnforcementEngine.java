package com.smartTriage.smartTriage_server.module.hypoglycemia.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * HypoglycemiaEnforcementEngine — enforces mandatory glucose checks per Rwanda protocol.
 *
 * Mandatory glucose check triggers:
 * - AVPU != ALERT (altered consciousness)
 * - Convulsions
 * - Coma
 * - Altered mental status
 *
 * Recommended glucose check:
 * - Known diabetic (from patient.chronicConditions)
 *
 * Glucose interpretation:
 * - < 3.0 mmol/L → CRITICAL → immediate treatment (50mL 50% dextrose IV adults, 5mL/kg 10% dextrose children)
 * - 3.0-3.9 mmol/L → MILD → close monitoring
 * - >= 4.0 mmol/L → NORMAL
 */
@Slf4j
@Component
public class HypoglycemiaEnforcementEngine {

    private static final double CRITICAL_THRESHOLD = 3.0;
    private static final double MILD_THRESHOLD = 4.0;

    /**
     * Enforce glucose check requirements based on triage findings.
     *
     * @param visit  the current visit
     * @param triage the most recent triage record
     * @return HypoglycemiaCheckResult with check requirements and glucose interpretation
     */
    public HypoglycemiaCheckResult enforceGlucoseCheck(Visit visit, TriageRecord triage) {
        List<String> triggerReasons = new ArrayList<>();
        boolean checkMandatory = false;

        // AVPU != ALERT → MANDATORY
        if (triage.getAvpu() != null && triage.getAvpu() != AvpuScore.ALERT) {
            triggerReasons.add("altered_consciousness (AVPU: " + triage.getAvpu().getDescription() + ")");
            checkMandatory = true;
        }

        // Convulsions → MANDATORY
        if (triage.isHasConvulsions()) {
            triggerReasons.add("convulsions");
            checkMandatory = true;
        }

        // Coma → MANDATORY
        if (triage.isHasComa()) {
            triggerReasons.add("coma");
            checkMandatory = true;
        }

        // Altered mental status → MANDATORY
        if (triage.isVuAlteredMentalStatus()) {
            triggerReasons.add("altered_mental_status");
            checkMandatory = true;
        }

        // Known diabetic → RECOMMENDED
        boolean isKnownDiabetic = false;
        if (visit.getPatient() != null && visit.getPatient().getChronicConditions() != null) {
            String conditions = visit.getPatient().getChronicConditions().toLowerCase();
            if (conditions.contains("diabetes") || conditions.contains("diabetic") || conditions.contains("dm")) {
                triggerReasons.add("known_diabetic");
                isKnownDiabetic = true;
            }
        }

        boolean requiresCheck = checkMandatory || isKnownDiabetic;

        if (!requiresCheck) {
            return new HypoglycemiaCheckResult(false, false, null, false, "NONE",
                    null, List.of());
        }

        // Interpret glucose if available
        Double glucoseValue = resolveGlucoseValue(triage);
        boolean isHypoglycemic = false;
        String severity = "PENDING_CHECK";
        String treatmentProtocol = null;

        if (glucoseValue != null) {
            if (glucoseValue < CRITICAL_THRESHOLD) {
                isHypoglycemic = true;
                severity = "CRITICAL";
                treatmentProtocol = determineTreatmentProtocol(visit);
                log.warn("CRITICAL hypoglycemia detected: glucose={} mmol/L, visit={}",
                        glucoseValue, visit.getId());
            } else if (glucoseValue < MILD_THRESHOLD) {
                isHypoglycemic = true;
                severity = "MILD";
                treatmentProtocol = "Oral glucose if conscious. Monitor closely. Repeat glucose in 15 minutes.";
                log.info("Mild hypoglycemia detected: glucose={} mmol/L, visit={}",
                        glucoseValue, visit.getId());
            } else {
                severity = "NORMAL";
            }
        }

        log.info("Glucose check enforcement for visit {}: mandatory={}, reasons={}, glucose={}, severity={}",
                visit.getId(), checkMandatory, triggerReasons, glucoseValue, severity);

        return new HypoglycemiaCheckResult(requiresCheck, checkMandatory, glucoseValue,
                isHypoglycemic, severity, treatmentProtocol, triggerReasons);
    }

    /**
     * Resolve the glucose value from triage record.
     * Checks bloodGlucose, convulsionGlucose, comaGlucose, and neurological glucose fields.
     */
    private Double resolveGlucoseValue(TriageRecord triage) {
        if (triage.getBloodGlucose() != null) {
            return triage.getBloodGlucose();
        }
        if (triage.getConvulsionGlucose() != null) {
            return triage.getConvulsionGlucose();
        }
        if (triage.getComaGlucose() != null) {
            return triage.getComaGlucose();
        }
        if (triage.getVuNeurologicalGlucose() != null) {
            return triage.getVuNeurologicalGlucose();
        }
        return null;
    }

    /**
     * Determine treatment protocol based on patient age per Rwanda guidelines.
     */
    private String determineTreatmentProtocol(Visit visit) {
        if (visit.isPediatric()) {
            return "PEDIATRIC: 5mL/kg of 10% dextrose IV. Recheck glucose in 15 minutes. " +
                    "If still <3.0 mmol/L, repeat dextrose bolus.";
        }
        return "ADULT: 50mL of 50% dextrose IV. Recheck glucose in 15 minutes. " +
                "If still <3.0 mmol/L, repeat dextrose bolus. Consider dextrose infusion.";
    }

    /**
     * Result record for glucose check enforcement.
     */
    public record HypoglycemiaCheckResult(
            boolean requiresCheck,
            boolean checkMandatory,
            Double glucoseValue,
            boolean isHypoglycemic,
            String severity,
            String treatmentProtocol,
            List<String> triggerReasons
    ) {
    }
}
