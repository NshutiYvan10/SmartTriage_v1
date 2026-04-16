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
 * Disease-specific screening protocols:
 * - TB: Rwanda is a high-burden country; cough > 2 weeks + fever + night sweats + weight loss
 * - Ebola/Marburg: fever + bleeding + contact history (critical for Rwanda — Marburg outbreak 2023)
 * - COVID-19: fever + cough + recent travel/contact
 * - Measles: fever + rash + unvaccinated
 * - Cholera: acute watery diarrhea
 * - Meningococcal: fever + neck stiffness + purpuric rash
 */
@Slf4j
@Component
public class InfectionScreeningEngine {

    /**
     * Screen a patient for infection risk based on screening criteria and triage data.
     *
     * @param visit   the current visit
     * @param triage  the most recent triage record
     * @param request the screening request with symptom data
     * @return screening result with risk level, isolation type, PPE requirements, and findings
     */
    public InfectionScreeningResult screenPatient(Visit visit, TriageRecord triage,
                                                   InfectionScreeningRequest request) {
        List<String> findings = new ArrayList<>();
        InfectionRiskLevel riskLevel = InfectionRiskLevel.LOW_RISK;
        IsolationType isolationType = null;
        NotifiableDisease notifiableDisease = null;
        PpeRequirements ppe = new PpeRequirements();

        // ====================================================================
        // EBOLA / MARBURG SCREENING (highest priority — immediately life-threatening)
        // ====================================================================
        if (request.isHasFever() && request.isHasBleedingSymptoms()) {
            if (request.isHasContactWithInfectious() || request.isHasRecentTravel()) {
                riskLevel = InfectionRiskLevel.CONFIRMED;
                isolationType = IsolationType.STRICT;
                notifiableDisease = NotifiableDisease.EBOLA;
                findings.add("CRITICAL: Fever + bleeding symptoms + contact/travel history — suspect viral hemorrhagic fever (Ebola/Marburg)");
                findings.add("IMMEDIATE: Strict isolation required. Notify Rwanda RBC immediately.");
                ppe.setAll(true);
                log.warn("CRITICAL: Viral hemorrhagic fever suspected for visit {}", visit.getId());
            } else {
                riskLevel = InfectionRiskLevel.HIGH_RISK;
                isolationType = IsolationType.STRICT;
                notifiableDisease = NotifiableDisease.EBOLA;
                findings.add("HIGH RISK: Fever + bleeding symptoms without confirmed contact — VHF cannot be excluded");
                ppe.setAll(true);
            }
        }

        // ====================================================================
        // TB SCREENING (Rwanda is high-burden for TB)
        // ====================================================================
        if (riskLevel != InfectionRiskLevel.CONFIRMED) {
            boolean tbSuspected = false;
            int tbIndicators = 0;

            if (request.isHasCough() && request.getHasCoughDurationWeeks() != null
                    && request.getHasCoughDurationWeeks() >= 2) {
                tbIndicators++;
                findings.add("Cough > 2 weeks (TB indicator)");
            }
            if (request.isHasFever()) {
                tbIndicators++;
            }
            if (request.isHasNightSweats()) {
                tbIndicators++;
                findings.add("Night sweats present");
            }
            if (request.isHasWeightLoss()) {
                tbIndicators++;
                findings.add("Weight loss present");
            }

            if (tbIndicators >= 3 && request.isHasCough()) {
                riskLevel = InfectionRiskLevel.HIGH_RISK;
                isolationType = IsolationType.AIRBORNE;
                notifiableDisease = NotifiableDisease.TUBERCULOSIS;
                tbSuspected = true;
                findings.add("HIGH RISK: TB suspected — cough + fever + constitutional symptoms. Airborne isolation required.");
                ppe.requiresN95 = true;
                ppe.requiresGloves = true;
                ppe.requiresGown = true;
            } else if (request.isHasCough() && request.isHasFever() && !tbSuspected) {
                if (riskLevel.ordinal() > InfectionRiskLevel.MODERATE_RISK.ordinal()) {
                    riskLevel = InfectionRiskLevel.MODERATE_RISK;
                }
                findings.add("MODERATE: Cough + fever — mask patient, further TB evaluation recommended");
                ppe.requiresN95 = true;
                ppe.requiresGloves = true;
            }
        }

        // ====================================================================
        // MEASLES SCREENING
        // ====================================================================
        if (request.isHasFever() && request.isHasRash()
                && riskLevel != InfectionRiskLevel.CONFIRMED) {
            if (riskLevel.ordinal() > InfectionRiskLevel.HIGH_RISK.ordinal()) {
                riskLevel = InfectionRiskLevel.HIGH_RISK;
            }
            isolationType = IsolationType.AIRBORNE;
            notifiableDisease = NotifiableDisease.MEASLES;
            findings.add("Fever + rash — suspect measles. Airborne isolation required.");
            ppe.requiresN95 = true;
            ppe.requiresGloves = true;
            ppe.requiresGown = true;
        }

        // ====================================================================
        // MENINGOCOCCAL SCREENING
        // ====================================================================
        if (request.isHasFever() && triage.isHasPurpuricRash()) {
            if (riskLevel.ordinal() > InfectionRiskLevel.HIGH_RISK.ordinal()) {
                riskLevel = InfectionRiskLevel.HIGH_RISK;
            }
            isolationType = IsolationType.DROPLET;
            notifiableDisease = NotifiableDisease.MENINGOCOCCAL;
            findings.add("Fever + purpuric rash — suspect meningococcal disease. Droplet isolation required.");
            ppe.requiresN95 = true;
            ppe.requiresGloves = true;
            ppe.requiresGown = true;
            ppe.requiresFaceShield = true;
        }

        // ====================================================================
        // CHOLERA SCREENING
        // ====================================================================
        if (request.isHasDiarrhea() && request.isHasFever()) {
            if (riskLevel.ordinal() > InfectionRiskLevel.MODERATE_RISK.ordinal()) {
                riskLevel = InfectionRiskLevel.MODERATE_RISK;
            }
            if (isolationType == null) {
                isolationType = IsolationType.CONTACT;
            }
            notifiableDisease = NotifiableDisease.CHOLERA;
            findings.add("Acute diarrhea + fever — suspect cholera. Contact isolation required.");
            ppe.requiresGloves = true;
            ppe.requiresGown = true;
            ppe.requiresApron = true;
        } else if (request.isHasDiarrhea()) {
            findings.add("Diarrhea present — contact precautions recommended");
            if (isolationType == null) {
                isolationType = IsolationType.CONTACT;
            }
            ppe.requiresGloves = true;
            ppe.requiresGown = true;
        }

        // ====================================================================
        // COVID-19 SCREENING
        // ====================================================================
        if (request.isHasFever() && request.isHasCough()
                && (request.isHasRecentTravel() || request.isHasContactWithInfectious())) {
            if (riskLevel.ordinal() > InfectionRiskLevel.MODERATE_RISK.ordinal()) {
                riskLevel = InfectionRiskLevel.MODERATE_RISK;
            }
            if (isolationType == null || isolationType == IsolationType.CONTACT) {
                isolationType = IsolationType.DROPLET;
            }
            if (notifiableDisease == null) {
                notifiableDisease = NotifiableDisease.COVID_19;
            }
            findings.add("Fever + cough + travel/contact history — COVID-19 screening recommended. Droplet isolation.");
            ppe.requiresN95 = true;
            ppe.requiresGloves = true;
            ppe.requiresGown = true;
            ppe.requiresFaceShield = true;
        }

        // Healthcare worker — increased exposure risk
        if (request.isHealthcareWorker() && request.isHasFever()) {
            findings.add("Healthcare worker with fever — consider occupational exposure risk");
        }

        // If no findings, patient is cleared
        if (findings.isEmpty()) {
            riskLevel = InfectionRiskLevel.CLEARED;
            findings.add("No significant infection risk indicators identified");
        }

        log.info("Infection screening for visit {}: riskLevel={}, isolationType={}, notifiable={}",
                visit.getId(), riskLevel, isolationType, notifiableDisease);

        return new InfectionScreeningResult(riskLevel, isolationType, notifiableDisease,
                ppe, findings, determineSuspectedCondition(notifiableDisease, findings));
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
     * PPE requirements determined by the screening engine.
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
