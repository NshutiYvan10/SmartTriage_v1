package com.smartTriage.smartTriage_server.module.fasttrack.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.FastTrackType;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * StrokeMIDetectionEngine — screens triage data for stroke and MI indicators.
 *
 * Stroke indicators (BE-FAST):
 * - Balance problems, Eyes (vision changes), Face drooping, Arm weakness, Speech difficulty, Time
 * - Focal neurologic deficit, altered mental status, AVPU != ALERT
 *
 * MI indicators:
 * - Chest pain (from triage discriminators)
 * - Age and risk factor profile
 *
 * Returns a FastTrackRecommendation with type, confidence, and clinical reasoning.
 */
@Slf4j
@Component
public class StrokeMIDetectionEngine {

    /**
     * Screen for stroke indicators from triage data.
     *
     * @param visit  the current visit
     * @param triage the most recent triage record
     * @return recommendation, or null if no stroke indicators detected
     */
    public FastTrackRecommendation screenForStroke(Visit visit, TriageRecord triage) {
        List<String> findings = new ArrayList<>();
        int indicatorCount = 0;

        // Focal neurologic deficit — strongest stroke indicator
        if (triage.isVuFocalNeurologicDeficit()) {
            findings.add("Focal neurologic deficit present");
            indicatorCount += 2;
        }

        // Altered mental status
        if (triage.isVuAlteredMentalStatus()) {
            findings.add("Altered mental status");
            indicatorCount++;
        }

        // AVPU not ALERT — altered consciousness
        if (triage.getAvpu() != null && triage.getAvpu() != AvpuScore.ALERT) {
            findings.add("AVPU: " + triage.getAvpu().getDescription() + " (not Alert)");
            indicatorCount++;
        }

        // Coma — can be stroke-related
        if (triage.isHasComa()) {
            findings.add("Coma present — possible hemorrhagic stroke");
            indicatorCount++;
        }

        // Convulsions — can be stroke-related, especially hemorrhagic
        if (triage.isHasConvulsions()) {
            findings.add("Convulsions present — possible stroke complication");
            indicatorCount++;
        }

        // Check chief complaint for stroke-related keywords
        String complaint = visit.getChiefComplaint();
        if (complaint != null) {
            String lowerComplaint = complaint.toLowerCase();
            if (lowerComplaint.contains("facial droop") || lowerComplaint.contains("face droop")) {
                findings.add("Chief complaint: facial droop");
                indicatorCount++;
            }
            if (lowerComplaint.contains("arm weakness") || lowerComplaint.contains("leg weakness")
                    || lowerComplaint.contains("hemiparesis") || lowerComplaint.contains("hemiplegia")) {
                findings.add("Chief complaint: limb weakness");
                indicatorCount++;
            }
            if (lowerComplaint.contains("speech difficulty") || lowerComplaint.contains("slurred speech")
                    || lowerComplaint.contains("aphasia") || lowerComplaint.contains("dysphasia")) {
                findings.add("Chief complaint: speech difficulty");
                indicatorCount++;
            }
            if (lowerComplaint.contains("sudden onset") || lowerComplaint.contains("sudden headache")) {
                findings.add("Chief complaint: sudden onset symptoms");
                indicatorCount++;
            }
            if (lowerComplaint.contains("vision loss") || lowerComplaint.contains("double vision")) {
                findings.add("Chief complaint: vision changes");
                indicatorCount++;
            }
        }

        if (indicatorCount == 0) {
            return null;
        }

        // Determine type — TIA vs stroke based on severity indicators
        FastTrackType type = indicatorCount >= 3 ? FastTrackType.STROKE_SUSPECTED : FastTrackType.TIA_SUSPECTED;
        double confidence = Math.min(1.0, indicatorCount * 0.2);

        // If focal neurologic deficit is present, always suspect stroke
        if (triage.isVuFocalNeurologicDeficit()) {
            type = FastTrackType.STROKE_SUSPECTED;
            confidence = Math.max(confidence, 0.7);
        }

        String reasoning = String.format("Stroke screening: %d indicator(s) detected. %s",
                indicatorCount, String.join("; ", findings));

        log.info("Stroke screening for visit {}: {} indicators detected, type={}, confidence={}",
                visit.getId(), indicatorCount, type, confidence);

        return new FastTrackRecommendation(type, confidence, reasoning, findings);
    }

    /**
     * Screen for MI indicators from triage data.
     *
     * @param visit  the current visit
     * @param triage the most recent triage record
     * @return recommendation, or null if no MI indicators detected
     */
    public FastTrackRecommendation screenForMI(Visit visit, TriageRecord triage) {
        List<String> findings = new ArrayList<>();
        int indicatorCount = 0;

        // Chest pain — primary MI discriminator from triage form
        if (triage.isVuChestPain()) {
            findings.add("Chest pain present (triage discriminator)");
            indicatorCount += 2;
        }

        // Shortness of breath — associated MI symptom
        if (triage.isVuShortnessOfBreath()) {
            findings.add("Shortness of breath");
            indicatorCount++;
        }

        // Check chief complaint for MI-related keywords
        String complaint = visit.getChiefComplaint();
        if (complaint != null) {
            String lowerComplaint = complaint.toLowerCase();
            if (lowerComplaint.contains("chest pain") || lowerComplaint.contains("chest tightness")
                    || lowerComplaint.contains("chest pressure")) {
                findings.add("Chief complaint: chest pain/tightness/pressure");
                indicatorCount++;
            }
            if (lowerComplaint.contains("radiating") || lowerComplaint.contains("jaw pain")
                    || lowerComplaint.contains("left arm")) {
                findings.add("Chief complaint: radiating pain pattern");
                indicatorCount++;
            }
            if (lowerComplaint.contains("diaphoresis") || lowerComplaint.contains("sweating")
                    || lowerComplaint.contains("nausea")) {
                findings.add("Chief complaint: associated autonomic symptoms");
                indicatorCount++;
            }
        }

        // Age as risk factor (> 40 years increases suspicion)
        if (visit.getPatient() != null && visit.getPatient().getAgeInYears() > 40) {
            findings.add("Age > 40 years (increased MI risk)");
            indicatorCount++;
        }

        // Known diabetic — atypical MI presentations common
        if (visit.getPatient() != null && visit.getPatient().getChronicConditions() != null) {
            String conditions = visit.getPatient().getChronicConditions().toLowerCase();
            if (conditions.contains("diabetes")) {
                findings.add("Known diabetic — atypical MI presentation possible");
                indicatorCount++;
            }
            if (conditions.contains("hypertension") || conditions.contains("htn")) {
                findings.add("Known hypertension — MI risk factor");
                indicatorCount++;
            }
            if (conditions.contains("cardiac") || conditions.contains("heart") || conditions.contains("ihd")
                    || conditions.contains("coronary")) {
                findings.add("Known cardiac history — increased MI risk");
                indicatorCount++;
            }
        }

        if (indicatorCount == 0) {
            return null;
        }

        // Must have chest pain to trigger MI fast-track
        if (!triage.isVuChestPain() && (complaint == null ||
                (!complaint.toLowerCase().contains("chest pain") && !complaint.toLowerCase().contains("chest tightness")))) {
            return null;
        }

        double confidence = Math.min(1.0, indicatorCount * 0.15);
        FastTrackType type = FastTrackType.STEMI_SUSPECTED;

        String reasoning = String.format("MI screening: %d indicator(s) detected. %s",
                indicatorCount, String.join("; ", findings));

        log.info("MI screening for visit {}: {} indicators detected, confidence={}",
                visit.getId(), indicatorCount, confidence);

        return new FastTrackRecommendation(type, confidence, reasoning, findings);
    }

    /**
     * Recommendation record returned by screening methods.
     */
    public record FastTrackRecommendation(
            FastTrackType type,
            double confidence,
            String reasoning,
            List<String> findings
    ) {
    }
}
