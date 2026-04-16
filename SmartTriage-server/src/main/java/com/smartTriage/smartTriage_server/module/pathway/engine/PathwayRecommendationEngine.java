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

        // Severe Malaria pathway
        if (containsAny(combined, "malaria", "fever", "rdt positive", "parasitemia", "rigors")) {
            addRecommendation(recommendations, "MAL-SEV",
                    "Chief complaint suggests malaria/febrile illness — common in Rwanda",
                    "HIGH", 0.85);
        }

        // Head Trauma pathway
        if (triage != null && (triage.getTraumaStatus() != null || triage.isSpecialAcuteTrauma())) {
            if (containsAny(combined, "head", "skull", "brain", "concussion", "fall on head", "hit on head")) {
                addRecommendation(recommendations, "TRA-HEAD",
                        "Trauma with head injury indicators",
                        "HIGH", 0.90);
            } else {
                // General trauma flagged
                addRecommendation(recommendations, "TRA-HEAD",
                        "Acute trauma flagged — assess for head injury component",
                        "MEDIUM", 0.60);
            }
        }

        // Acute Coronary Syndrome pathway
        if (triage != null && triage.isVuChestPain()) {
            addRecommendation(recommendations, "CARD-ACS",
                    "Chest pain flagged as very urgent sign",
                    "HIGH", 0.85);
        } else if (containsAny(combined, "chest pain", "cardiac", "heart attack", "myocardial")) {
            addRecommendation(recommendations, "CARD-ACS",
                    "Chief complaint includes chest pain or cardiac symptoms",
                    "HIGH", 0.80);
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
        } else if (containsAny(combined, "pregnant", "vaginal bleeding", "obstetric", "labour", "labor",
                "eclampsia", "placenta")) {
            addRecommendation(recommendations, "OBS-EMERG",
                    "Chief complaint suggests obstetric emergency",
                    "HIGH", 0.80);
        }

        // Sepsis Management pathway
        if (containsAny(combined, "sepsis", "septic", "infection", "high fever")) {
            addRecommendation(recommendations, "INF-SEPSIS",
                    "Clinical indicators suggest possible sepsis",
                    "HIGH", 0.80);
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
