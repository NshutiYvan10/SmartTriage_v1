package com.smartTriage.smartTriage_server.module.pathway.engine;

import com.smartTriage.smartTriage_server.module.pathway.dto.PathwayRecommendation;
import com.smartTriage.smartTriage_server.module.pathway.entity.ClinicalPathway;
import com.smartTriage.smartTriage_server.module.pathway.repository.ClinicalPathwayRepository;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PathwayRecommendationEngine — recommends clinical pathways based on
 * triage findings, chief complaint, and clinical indicators.
 *
 * Logic is based on common Rwandan emergency presentations and maps
 * clinical findings to evidence-based pathways.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PathwayRecommendationEngine {

    private final ClinicalPathwayRepository pathwayRepository;

    /**
     * Recommend pathways for a visit based on triage findings and chief complaint.
     */
    public List<PathwayRecommendation> recommendPathways(Visit visit, TriageRecord triage) {
        List<PathwayRecommendation> recommendations = new ArrayList<>();

        String complaint = normalizeText(visit.getChiefComplaint());
        String presenting = normalizeText(triage != null ? triage.getPresentingComplaints() : null);
        String combined = complaint + " " + presenting;

        // Severe Malaria pathway. Malaria-specific signals → HIGH; a bare "fever" is
        // far too non-specific in an endemic, high-fever-prevalence setting, so it only
        // yields a low-confidence "screen for malaria" prompt (not a HIGH activation).
        if (containsAny(combined, "malaria", "rdt positive", "parasitemia")) {
            addRecommendation(recommendations, "MAL-SEV",
                    "Malaria-specific indicators (diagnosis / positive RDT / parasitemia) — severe-malaria pathway indicated",
                    "HIGH", 0.85);
        } else if (containsAny(combined, "fever", "rigors")) {
            addRecommendation(recommendations, "MAL-SEV",
                    "Febrile illness — screen for malaria (RDT) and confirm severity before activating the severe-malaria pathway",
                    "MEDIUM", 0.45);
        }

        // Head Trauma pathway. Expanded mechanism/head-injury keyword set; a free-text
        // head-injury complaint can recommend (at reduced confidence) even without a triage trauma flag.
        boolean headKeywords = containsAny(combined, "head", "skull", "brain", "concussion", "fall on head",
                "hit on head", "scalp", "facial", "unconscious", "loss of consciousness",
                "rta", "road traffic", "motorcycle", "accident", "assault");
        if (triage != null && (triage.getTraumaStatus() != null || triage.isSpecialAcuteTrauma())) {
            if (headKeywords) {
                addRecommendation(recommendations, "TRA-HEAD",
                        "Trauma with head-injury indicators", "HIGH", 0.90);
            } else {
                addRecommendation(recommendations, "TRA-HEAD",
                        "Acute trauma flagged — assess for head-injury component", "MEDIUM", 0.60);
            }
        } else if (headKeywords) {
            addRecommendation(recommendations, "TRA-HEAD",
                    "Chief complaint suggests a head injury — assess for the head-trauma pathway", "MEDIUM", 0.55);
        }

        // Acute Coronary Syndrome — OWNED by the dedicated Fast Track tool (which fires on
        // atypical ACS too). Steer the clinician there; CARD-ACS is a reference checklist only.
        if (triage != null && triage.isVuChestPain()) {
            addRecommendation(recommendations, "CARD-ACS",
                    "Chest pain flagged — ACTIVATE FAST TRACK for the owned, SLA-tracked ACS pathway; this checklist is a reference only",
                    "HIGH", 0.70);
        } else if (containsAny(combined, "chest pain", "cardiac", "heart attack", "myocardial")) {
            addRecommendation(recommendations, "CARD-ACS",
                    "Cardiac symptoms — use the FAST TRACK tool for ACS (covers atypical presentations); this pathway is a reference checklist",
                    "HIGH", 0.65);
        }

        // Acute Asthma pathway
        if (triage != null && triage.isVuShortnessOfBreath()) {
            if (containsAny(combined, "asthma", "wheez", "bronchospasm")) {
                addRecommendation(recommendations, "RESP-ASTHMA",
                        "Shortness of breath with asthma/wheezing indicators",
                        "HIGH", 0.90);
            } else {
                addRecommendation(recommendations, "RESP-ASTHMA",
                        "Shortness of breath flagged — assess for asthma",
                        "MEDIUM", 0.60);
            }
        }

        // Status Epilepticus pathway
        if (triage != null && triage.isHasConvulsions()) {
            addRecommendation(recommendations, "NEURO-SEIZ",
                    "Active convulsions — Status Epilepticus pathway indicated",
                    "HIGH", 0.90);
        } else if (containsAny(combined, "convulsion", "seizure", "epilepsy", "fitting")) {
            addRecommendation(recommendations, "NEURO-SEIZ",
                    "Chief complaint suggests seizure activity",
                    "HIGH", 0.80);
        }

        // Obstetric Emergency pathway
        if (triage != null && (triage.isUrgPregnantVaginalBleeding() || triage.isVuPregnantAbdominalPain()
                || triage.isVuPregnantAbdominalTrauma())) {
            addRecommendation(recommendations, "OBS-EMERG",
                    "Pregnant patient with emergency signs (vaginal bleeding, abdominal pain, or trauma)",
                    "HIGH", 0.90);
        } else if (containsAny(combined, "pregnant", "vaginal bleeding", "obstetric", "eclampsia",
                "in labour", "contractions", "antepartum", "postpartum")) {
            // Dropped bare "labour"/"labor" (matches "laboratory") and "placenta" (low specificity).
            addRecommendation(recommendations, "OBS-EMERG",
                    "Chief complaint suggests obstetric emergency",
                    "HIGH", 0.80);
        }

        // Sepsis — OWNED by the dedicated Sepsis screening tool (qSOFA/SIRS + 1-hr bundle
        // monitor). Steer there; INF-SEPSIS is a reference checklist only. (Dropped the bare
        // "infection"/"high fever" triggers — too broad and they double-fired with malaria.)
        if (containsAny(combined, "sepsis", "septic")) {
            addRecommendation(recommendations, "INF-SEPSIS",
                    "Possible sepsis — use the dedicated SEPSIS screening tool (qSOFA/SIRS + bundle monitor); this pathway is a reference checklist only",
                    "HIGH", 0.65);
        }

        // Snakebite Management pathway (common in rural Rwanda)
        if (containsAny(combined, "snake", "snakebite", "snake bite", "envenomation", "viper")) {
            addRecommendation(recommendations, "BITE-SNAKE",
                    "Snakebite presentation — common in rural Rwanda",
                    "HIGH", 0.90);
        }

        // Burns Management pathway
        if (triage != null && (triage.isHasBurnFaceInhalation() || triage.isVuBurnOver20Percent()
                || triage.isUrgBurnWithoutUrgentSigns())) {
            addRecommendation(recommendations, "BURN-MGMT",
                    "Burn injury flagged on triage assessment",
                    "HIGH", 0.90);
        } else if (containsAny(combined, "burn", "scald", "thermal injury", "flame")) {
            addRecommendation(recommendations, "BURN-MGMT",
                    "Chief complaint suggests burn injury",
                    "HIGH", 0.80);
        }

        // Poisoning Management pathway
        if (triage != null && triage.isVuPoisoningOverdose()) {
            addRecommendation(recommendations, "TOX-POIS",
                    "Poisoning/overdose flagged as very urgent sign",
                    "HIGH", 0.90);
        } else if (containsAny(combined, "poison", "overdose", "ingestion", "toxic", "chemical")) {
            addRecommendation(recommendations, "TOX-POIS",
                    "Chief complaint suggests poisoning or overdose",
                    "HIGH", 0.85);
        }

        log.info("Pathway recommendations for visit {} — {} pathways recommended",
                visit.getVisitNumber(), recommendations.size());

        return recommendations;
    }

    // ====================================================================
    // INTERNAL HELPERS
    // ====================================================================

    private void addRecommendation(List<PathwayRecommendation> list, String pathwayCode,
                                   String reason, String urgency, double confidence) {
        Optional<ClinicalPathway> pathway = pathwayRepository.findByPathwayCodeAndIsActiveTrue(pathwayCode);
        pathway.ifPresent(p -> list.add(PathwayRecommendation.builder()
                .pathwayId(p.getId())
                .pathwayCode(p.getPathwayCode())
                .pathwayName(p.getPathwayName())
                .reason(reason)
                .urgency(urgency)
                .confidence(confidence)
                .build()));
    }

    private String normalizeText(String text) {
        return text != null ? text.toLowerCase().trim() : "";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
