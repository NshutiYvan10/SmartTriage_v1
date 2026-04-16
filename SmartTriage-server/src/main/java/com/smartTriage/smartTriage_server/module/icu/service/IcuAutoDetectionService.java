package com.smartTriage.smartTriage_server.module.icu.service;

import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.icu.engine.IcuEscalationEngine;
import com.smartTriage.smartTriage_server.module.icu.engine.IcuEscalationEngine.IcuEscalationRecommendation;
import com.smartTriage.smartTriage_server.module.icu.entity.IcuEscalation;
import com.smartTriage.smartTriage_server.module.icu.repository.IcuEscalationRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * IcuAutoDetectionService — scheduled component that continuously monitors
 * high-acuity patients (RED and ORANGE triage categories) for ICU escalation triggers.
 *
 * Runs every 2 minutes. For each qualifying visit without an existing active
 * escalation, it fetches the latest vital signs and runs the IcuEscalationEngine.
 * If ICU is recommended, an automatic escalation is created.
 *
 * This ensures that even when clinical staff are overwhelmed, critical
 * deterioration is caught and escalated in a timely manner.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IcuAutoDetectionService {

    private final VisitRepository visitRepository;
    private final VitalSignsRepository vitalSignsRepository;
    private final IcuEscalationRepository icuEscalationRepository;
    private final IcuEscalationEngine icuEscalationEngine;
    private final IcuEscalationService icuEscalationService;

    /**
     * Active visit statuses that should be monitored for ICU triggers.
     * Excludes terminal states and already-admitted ICU patients.
     */
    private static final List<VisitStatus> MONITORED_STATUSES = List.of(
            VisitStatus.TRIAGED,
            VisitStatus.AWAITING_ASSESSMENT,
            VisitStatus.UNDER_ASSESSMENT,
            VisitStatus.UNDER_TREATMENT,
            VisitStatus.UNDER_OBSERVATION,
            VisitStatus.PENDING_DISPOSITION
    );

    /**
     * Scheduled task: scan RED and ORANGE patients every 2 minutes for ICU triggers.
     */
    @Scheduled(fixedRate = 120_000)
    @Transactional
    public void detectIcuCandidates() {
        // Get all active visits in monitored statuses
        List<Visit> activeVisits = visitRepository.findAllActiveVisitsByStatuses(MONITORED_STATUSES);

        int evaluated = 0;
        int escalated = 0;

        for (Visit visit : activeVisits) {
            // Only evaluate RED and ORANGE triage category patients
            if (visit.getCurrentTriageCategory() != TriageCategory.RED
                    && visit.getCurrentTriageCategory() != TriageCategory.ORANGE) {
                continue;
            }

            // Skip if already has an active ICU escalation
            if (icuEscalationRepository.existsActiveEscalationForVisit(visit.getId())) {
                continue;
            }

            // Get latest vitals
            Optional<VitalSigns> latestVitals = vitalSignsRepository
                    .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visit.getId());

            if (latestVitals.isEmpty()) {
                continue;
            }

            // Run ICU escalation engine
            IcuEscalationRecommendation recommendation = icuEscalationEngine.evaluate(latestVitals.get());
            evaluated++;

            if (recommendation.icuRecommended()) {
                try {
                    icuEscalationService.autoEvaluate(visit.getId());
                    escalated++;
                } catch (Exception e) {
                    log.error("Failed to auto-create ICU escalation for visit {}: {}",
                            visit.getVisitNumber(), e.getMessage());
                }
            }
        }

        if (evaluated > 0) {
            log.info("ICU auto-detection: evaluated {} RED/ORANGE visits, escalated {} to ICU",
                    evaluated, escalated);
        }
    }
}
