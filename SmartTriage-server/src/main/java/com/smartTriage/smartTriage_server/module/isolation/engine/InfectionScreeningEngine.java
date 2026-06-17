package com.smartTriage.smartTriage_server.module.isolation.engine;

import com.smartTriage.smartTriage_server.common.enums.InfectionRiskLevel;
import com.smartTriage.smartTriage_server.common.enums.IsolationType;
import com.smartTriage.smartTriage_server.common.enums.NotifiableDisease;
import com.smartTriage.smartTriage_server.module.isolation.dto.InfectionScreeningRequest;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * InfectionScreeningEngine — screens patients for infectious disease risk.
 *
 * SAFETY INVARIANT: a screening block may only ever <b>raise</b> the precaution,
 * never lower it. The classification is accumulated through {@link #strictest},
 * {@link #maxRisk} and {@link #moreUrgent} so that block evaluation ORDER cannot
 * downgrade a more dangerous result. (Previously each block reassigned the
 * isolation type unconditionally, so a confirmed viral-hemorrhagic-fever patient
 * who also had a purpuric rash was silently dropped from STRICT to DROPLET — a
 * staff-exposure hazard. That can no longer happen.)
 *
 * Disease-specific screening protocols (Rwanda IDSR context):
 * - Ebola/Marburg (VHF): fever + bleeding (+ contact/travel) — STRICT, top priority
 * - TB (high-burden): chronic cough + constitutional symptoms — AIRBORNE
 * - Measles: fever + rash — AIRBORNE
 * - Meningococcal: fever + purpuric rash (± neck stiffness) — DROPLET
 * - Cholera: acute watery diarrhea (afebrile is typical) — CONTACT
 * - COVID-19: fever + cough + travel/contact — DROPLET
 * - Immunocompromised: PROTECTIVE (reverse) isolation
 */
@Slf4j
@Component
public class InfectionScreeningEngine {

    public InfectionScreeningResult screenPatient(Visit visit, TriageRecord triage,
                                                   InfectionScreeningRequest request) {
        List<String> findings = new ArrayList<>();
        InfectionRiskLevel riskLevel = InfectionRiskLevel.LOW_RISK;
        IsolationType isolationType = null;
        NotifiableDisease notifiableDisease = null;
        PpeRequirements ppe = new PpeRequirements();

        // ====================================================================
        // EBOLA / MARBURG (viral hemorrhagic fever) — strictest precaution.
        // Evaluated first, but the merge helpers below guarantee no later block
        // can lower STRICT or relabel the EBOLA/MARBURG notification.
        // ====================================================================
        if (request.isHasFever() && request.isHasBleedingSymptoms()) {
            isolationType = strictest(isolationType, IsolationType.STRICT);
            notifiableDisease = moreUrgent(notifiableDisease, NotifiableDisease.EBOLA);
            ppe.setAll(true);
            if (request.isHasContactWithInfectious() || request.isHasRecentTravel()) {
                riskLevel = maxRisk(riskLevel, InfectionRiskLevel.CONFIRMED);
                findings.add("CRITICAL: Fever + bleeding symptoms + contact/travel history — suspect viral "
                        + "hemorrhagic fever (Ebola/Marburg). STRICT isolation. Notify Rwanda RBC immediately.");
                log.warn("CRITICAL: Viral hemorrhagic fever suspected for visit {}", visit.getId());
            } else {
                riskLevel = maxRisk(riskLevel, InfectionRiskLevel.HIGH_RISK);
                findings.add("HIGH RISK: Fever + bleeding symptoms without confirmed contact — VHF cannot be "
                        + "excluded. STRICT isolation pending exclusion.");
            }
        }

        // ====================================================================
        // TB (Rwanda is high-burden). Anchor on chronic cough + a constitutional
        // symptom (night sweats / weight loss), not merely "any 3 indicators".
        // ====================================================================
        boolean coughGE2wk = request.isHasCough() && request.getHasCoughDurationWeeks() != null
                && request.getHasCoughDurationWeeks() >= 2;
        boolean constitutional = request.isHasNightSweats() || request.isHasWeightLoss();
        if (coughGE2wk) findings.add("Cough > 2 weeks (TB indicator)");
        if (request.isHasNightSweats()) findings.add("Night sweats present");
        if (request.isHasWeightLoss()) findings.add("Weight loss present");

        if (coughGE2wk && constitutional) {
            riskLevel = maxRisk(riskLevel, InfectionRiskLevel.HIGH_RISK);
            isolationType = strictest(isolationType, IsolationType.AIRBORNE);
            notifiableDisease = moreUrgent(notifiableDisease, NotifiableDisease.TUBERCULOSIS);
            findings.add("HIGH RISK: TB suspected — chronic cough + constitutional symptoms. Airborne isolation required.");
            ppe.requiresN95 = true;
            ppe.requiresGloves = true;
            ppe.requiresGown = true;
        } else if (request.isHasCough() && request.isHasFever()) {
            riskLevel = maxRisk(riskLevel, InfectionRiskLevel.MODERATE_RISK);
            findings.add("MODERATE: Cough + fever — mask patient, further TB evaluation recommended.");
            ppe.requiresN95 = true;
            ppe.requiresGloves = true;
        }

        // ====================================================================
        // MEASLES — fever + rash (highly transmissible, airborne)
        // ====================================================================
        if (request.isHasFever() && request.isHasRash()) {
            riskLevel = maxRisk(riskLevel, InfectionRiskLevel.HIGH_RISK);
            isolationType = strictest(isolationType, IsolationType.AIRBORNE);
            notifiableDisease = moreUrgent(notifiableDisease, NotifiableDisease.MEASLES);
            findings.add("Fever + rash — suspect measles. Airborne isolation required.");
            ppe.requiresN95 = true;
            ppe.requiresGloves = true;
            ppe.requiresGown = true;
        }

        // ====================================================================
        // MENINGOCOCCAL — a non-blanching purpuric/petechial rash is the cardinal
        // red flag and is frequently AFEBRILE (or hypothermic) in fulminant/infant
        // disease, so it is NOT fever-gated; neck stiffness/meningism also triggers.
        // ====================================================================
        boolean purpura = triage.isHasPurpuricRash();
        if (purpura || request.isHasNeckStiffness()) {
            riskLevel = maxRisk(riskLevel, purpura ? InfectionRiskLevel.HIGH_RISK : InfectionRiskLevel.MODERATE_RISK);
            isolationType = strictest(isolationType, IsolationType.DROPLET);
            notifiableDisease = moreUrgent(notifiableDisease, NotifiableDisease.MENINGOCOCCAL);
            findings.add(purpura
                    ? "Purpuric/petechial rash — suspect meningococcal disease (a non-blanching rash is a red flag even "
                      + "WITHOUT fever). Droplet isolation required."
                    : "Neck stiffness/meningism — suspect meningococcal/meningitis. Droplet isolation; look for a purpuric rash.");
            ppe.requiresN95 = true;
            ppe.requiresGloves = true;
            ppe.requiresGown = true;
            ppe.requiresFaceShield = true;
        }

        // ====================================================================
        // CHOLERA — acute watery diarrhea. Real cholera is typically AFEBRILE,
        // so we do NOT require fever. Diarrhea alone → contact precautions; with
        // fever / travel / known contact → flag as a notifiable cholera alert.
        // ====================================================================
        if (request.isHasDiarrhea()) {
            isolationType = strictest(isolationType, IsolationType.CONTACT);
            ppe.requiresGloves = true;
            ppe.requiresGown = true;
            ppe.requiresApron = true;
            boolean choleraAlert = request.isHasFever() || request.isHasRecentTravel()
                    || request.isHasContactWithInfectious();
            if (choleraAlert) {
                riskLevel = maxRisk(riskLevel, InfectionRiskLevel.MODERATE_RISK);
                notifiableDisease = moreUrgent(notifiableDisease, NotifiableDisease.CHOLERA);
                findings.add("Acute diarrhea with fever/travel/contact — suspect cholera. Contact isolation; "
                        + "notify per IDSR acute-watery-diarrhea surveillance.");
            } else {
                findings.add("Acute diarrhea — contact precautions; consider cholera if watery/high-volume or in an outbreak.");
            }
        }

        // ====================================================================
        // COVID-19 — fever + cough + travel/contact
        // ====================================================================
        if (request.isHasFever() && request.isHasCough()
                && (request.isHasRecentTravel() || request.isHasContactWithInfectious())) {
            riskLevel = maxRisk(riskLevel, InfectionRiskLevel.MODERATE_RISK);
            isolationType = strictest(isolationType, IsolationType.DROPLET);
            notifiableDisease = moreUrgent(notifiableDisease, NotifiableDisease.COVID_19);
            findings.add("Fever + cough + travel/contact history — COVID-19 screening recommended. Droplet isolation.");
            ppe.requiresN95 = true;
            ppe.requiresGloves = true;
            ppe.requiresGown = true;
            ppe.requiresFaceShield = true;
        }

        // ====================================================================
        // PROTECTIVE (reverse) ISOLATION — immunocompromised patient protection.
        // strictest() ensures an infectious precaution (which protects others)
        // still wins the single isolation slot if the patient is also infectious.
        // ====================================================================
        if (request.isImmunocompromised()) {
            riskLevel = maxRisk(riskLevel, InfectionRiskLevel.MODERATE_RISK);
            boolean alsoInfectious = isolationType != null; // an infectious precaution was already set
            isolationType = strictest(isolationType, IsolationType.PROTECTIVE);
            ppe.requiresGown = true;
            ppe.requiresGloves = true;
            if (alsoInfectious && isolationType != IsolationType.PROTECTIVE) {
                // Single isolation slot can't hold both; surface the COMBINED need so the room
                // chosen satisfies the infectious precaution AND protects the patient.
                findings.add("Immunocompromised AND infectious — needs COMBINED precautions: a single room (ideally with an "
                        + "anteroom) that satisfies BOTH the infectious precaution above AND protective (reverse) isolation. "
                        + "Staff entering: gown + gloves.");
            } else {
                findings.add("Immunocompromised — protective (reverse) isolation; staff entering must wear gown + gloves "
                        + "(+ mask if any respiratory symptoms). Place in a single room away from infectious patients.");
            }
        }

        // Healthcare worker — increased exposure risk (advisory only)
        if (request.isHealthcareWorker() && request.isHasFever()) {
            findings.add("Healthcare worker with fever — consider occupational exposure risk.");
        }

        // No indicators → cleared
        if (findings.isEmpty()) {
            riskLevel = InfectionRiskLevel.CLEARED;
            findings.add("No significant infection risk indicators identified.");
        }

        log.info("Infection screening for visit {}: riskLevel={}, isolationType={}, notifiable={}",
                visit.getId(), riskLevel, isolationType, notifiableDisease);

        return new InfectionScreeningResult(riskLevel, isolationType, notifiableDisease,
                ppe, findings, determineSuspectedCondition(notifiableDisease, findings));
    }

    // ====================================================================
    // MERGE HELPERS — accumulate the SAFEST classification regardless of block order
    // ====================================================================

    /** Precaution strength rank (higher = stricter). null = none. */
    private static int rank(IsolationType t) {
        if (t == null) return 0;
        return switch (t) {
            case STRICT -> 5;
            case AIRBORNE -> 4;
            case DROPLET -> 3;
            case CONTACT -> 2;
            case PROTECTIVE -> 1;
        };
    }

    /** Returns the stricter (more protective) of two isolation types. */
    public static IsolationType strictest(IsolationType a, IsolationType b) {
        return rank(a) >= rank(b) ? a : b;
    }

    /** Returns the higher risk level (lower ordinal = more severe; null = none). */
    public static InfectionRiskLevel maxRisk(InfectionRiskLevel a, InfectionRiskLevel b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    /**
     * Notifiable-disease urgency order (most urgent first). Lists EVERY
     * {@link NotifiableDisease} so {@link #notifRank} always resolves a real rank —
     * an unlisted value would otherwise lose every {@link #moreUrgent} comparison
     * and be silently relabelled away.
     */
    private static final List<NotifiableDisease> NOTIFIABLE_PRIORITY = List.of(
            NotifiableDisease.EBOLA, NotifiableDisease.MARBURG, NotifiableDisease.MPOX,
            NotifiableDisease.PLAGUE, NotifiableDisease.ANTHRAX, NotifiableDisease.AVIAN_INFLUENZA,
            NotifiableDisease.YELLOW_FEVER, NotifiableDisease.MEASLES, NotifiableDisease.MENINGOCOCCAL,
            NotifiableDisease.CHOLERA, NotifiableDisease.DENGUE, NotifiableDisease.RABIES,
            NotifiableDisease.TUBERCULOSIS, NotifiableDisease.COVID_19, NotifiableDisease.TYPHOID,
            NotifiableDisease.MALARIA_SEVERE, NotifiableDisease.HEPATITIS_A, NotifiableDisease.HEPATITIS_E,
            NotifiableDisease.HEPATITIS_B, NotifiableDisease.HIV_NEW_DIAGNOSIS, NotifiableDisease.OTHER_NOTIFIABLE);

    /** Unlisted → fail SAFE by treating as highly urgent so it can never be silently dropped. */
    private static int notifRank(NotifiableDisease d) {
        int idx = NOTIFIABLE_PRIORITY.indexOf(d);
        return idx < 0 ? -1 : idx;
    }

    /** Returns the more urgent of two notifiable diseases (never relabels VHF away). */
    static NotifiableDisease moreUrgent(NotifiableDisease a, NotifiableDisease b) {
        if (a == null) return b;
        if (b == null) return a;
        return notifRank(a) <= notifRank(b) ? a : b;
    }

    private String determineSuspectedCondition(NotifiableDisease disease, List<String> findings) {
        if (disease != null) {
            return disease.name().replace("_", " ");
        }
        if (!findings.isEmpty() && !findings.get(0).contains("No significant")) {
            return "Infectious disease screening — further evaluation required";
        }
        return null;
    }

    /**
     * Result record for infection screening.
     */
    public record InfectionScreeningResult(
            InfectionRiskLevel riskLevel,
            IsolationType isolationType,
            NotifiableDisease notifiableDisease,
            PpeRequirements ppeRequirements,
            List<String> findings,
            String suspectedCondition
    ) {
    }

    /**
     * PPE requirements determined by the screening engine. Flags are only ever
     * raised (OR-accumulated) across blocks, never cleared.
     */
    public static class PpeRequirements {
        public boolean requiresN95 = false;
        public boolean requiresGown = false;
        public boolean requiresGloves = false;
        public boolean requiresFaceShield = false;
        public boolean requiresApron = false;
        public boolean requiresBootCovers = false;

        public void setAll(boolean value) {
            this.requiresN95 = value;
            this.requiresGown = value;
            this.requiresGloves = value;
            this.requiresFaceShield = value;
            this.requiresApron = value;
            this.requiresBootCovers = value;
        }
    }
}
