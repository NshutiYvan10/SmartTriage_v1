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
        if (visit == null || triage == null) return null;
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

        // Hard findings force STROKE (never TIA): a focal neurologic deficit,
        // coma, convulsions, or an UNRESPONSIVE AVPU are persistent/severe and
        // by definition NOT a (transient, fully-resolved) TIA. Judging severity
        // by raw indicator count alone would under-triage e.g. a comatose
        // patient (count 1) to the lower-urgency TIA pathway.
        boolean hardFinding = triage.isVuFocalNeurologicDeficit()
                || triage.isHasComa()
                || triage.isHasConvulsions()
                || triage.getAvpu() == AvpuScore.UNRESPONSIVE;
        if (hardFinding) {
            type = FastTrackType.STROKE_SUSPECTED;
            confidence = Math.max(confidence, 0.7);
        }

        if (visit.isPediatric()) {
            findings.add("PEDIATRIC: pediatric stroke presents differently than adult and is rare; "
                    + "BE-FAST/keyword screening is adult-oriented — apply pediatric judgment.");
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
        if (visit == null || triage == null) return null;
        List<String> findings = new ArrayList<>();
        int indicatorCount = 0;
        boolean hasChestPain = false;
        boolean hasAnginalEquivalent = false;   // SOB / radiation / autonomic — atypical ACS
        boolean hasRiskFactor = false;
        boolean pediatric = visit.isPediatric();

        // Chest pain — primary (typical) MI discriminator from triage form
        if (triage.isVuChestPain()) {
            findings.add("Chest pain present (triage discriminator)");
            indicatorCount += 2;
            hasChestPain = true;
        }

        // Shortness of breath — a recognised anginal equivalent
        if (triage.isVuShortnessOfBreath()) {
            findings.add("Shortness of breath (possible anginal equivalent)");
            indicatorCount++;
            hasAnginalEquivalent = true;
        }

        // Check chief complaint for MI-related keywords
        String complaint = visit.getChiefComplaint();
        if (complaint != null) {
            String lowerComplaint = complaint.toLowerCase();
            if (lowerComplaint.contains("chest pain") || lowerComplaint.contains("chest tightness")
                    || lowerComplaint.contains("chest pressure")) {
                findings.add("Chief complaint: chest pain/tightness/pressure");
                indicatorCount++;
                hasChestPain = true;
            }
            if (lowerComplaint.contains("radiating") || lowerComplaint.contains("jaw pain")
                    || lowerComplaint.contains("left arm")) {
                findings.add("Chief complaint: radiating pain pattern");
                indicatorCount++;
                hasAnginalEquivalent = true;
            }
            if (lowerComplaint.contains("diaphoresis") || lowerComplaint.contains("sweating")
                    || lowerComplaint.contains("nausea") || lowerComplaint.contains("syncope")
                    || lowerComplaint.contains("collapse")) {
                findings.add("Chief complaint: associated autonomic symptoms");
                indicatorCount++;
                hasAnginalEquivalent = true;
            }
        }

        // Age as risk factor (> 40 years) — adults only; the adult-MI age
        // heuristic is not applicable to children.
        if (!pediatric && visit.getPatient() != null && visit.getPatient().getAgeInYears() > 40) {
            findings.add("Age > 40 years (increased MI risk)");
            indicatorCount++;
            hasRiskFactor = true;
        }

        // Comorbidity risk factors.
        if (visit.getPatient() != null && visit.getPatient().getChronicConditions() != null) {
            String conditions = visit.getPatient().getChronicConditions().toLowerCase();
            if (conditions.contains("diabetes")) {
                findings.add("Known diabetic — atypical MI presentation common");
                indicatorCount++;
                hasRiskFactor = true;
            }
            if (conditions.contains("hypertension") || conditions.contains("htn")) {
                findings.add("Known hypertension — MI risk factor");
                indicatorCount++;
                hasRiskFactor = true;
            }
            if (conditions.contains("cardiac") || conditions.contains("heart") || conditions.contains("ihd")
                    || conditions.contains("coronary")) {
                findings.add("Known cardiac history — increased MI risk");
                indicatorCount++;
                hasRiskFactor = true;
            }
        }

        // Gate: fire for a TYPICAL presentation (chest pain) OR ANY anginal-
        // equivalent (SOB, radiation/jaw/arm, autonomic symptoms). A documented
        // risk factor is a CONFIDENCE booster (it adds to indicatorCount), NOT a
        // precondition — requiring it would re-create the under-fire on atypical
        // ACS whose comorbidities simply weren't recorded at triage (the very
        // diabetic/elderly/female population the chest-pain gate used to miss).
        if (!hasChestPain && !hasAnginalEquivalent) {
            return null;
        }

        double confidence = Math.min(1.0, indicatorCount * 0.15);

        // Pre-ECG we cannot distinguish STEMI from NSTEMI, so we suspect ACS
        // (NSTEMI_SUSPECTED) and let the ECG ST-elevation reading upgrade to
        // STEMI. Always-emitting STEMI pre-ECG (the previous behaviour)
        // overstated the pathway.
        FastTrackType type = FastTrackType.NSTEMI_SUSPECTED;

        if (pediatric) {
            findings.add("PEDIATRIC: adult ACS criteria are not validated in children — "
                    + "interpret with caution and involve pediatric cardiology.");
        }

        String reasoning = String.format("MI/ACS screening: %d indicator(s) detected%s. %s",
                indicatorCount, hasChestPain ? "" : " (ATYPICAL — no chest pain)",
                String.join("; ", findings));

        log.info("MI screening for visit {}: {} indicators, type={}, confidence={}, atypical={}",
                visit.getId(), indicatorCount, type, confidence, !hasChestPain);

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
