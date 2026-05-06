package com.smartTriage.smartTriage_server.module.triage.service;

import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignEvent;
import com.smartTriage.smartTriage_server.module.clinicalsigns.repository.ClinicalSignEventRepository;
import com.smartTriage.smartTriage_server.module.clinicalsigns.service.ClinicalSignDefinitions;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Round 4c — defensive backfill of the re-triage engine.
 *
 * <p>The inline hook in {@code ClinicalSignService.recordBatch} fires on
 * every batch save and is the primary path. This scheduler runs every
 * five minutes and looks for non-baseline clinical-sign events from the
 * last 30 minutes that have no corresponding triage_record and no
 * clinical_alert pointing back to them — i.e. events the inline
 * evaluator either missed (transient backend failure, container
 * restart mid-request) or correctly decided NoAction on.
 *
 * <p>For each candidate the scheduler re-runs {@link RetriageEvaluator}
 * with the visit's current category. The result is one of:
 * <ul>
 *   <li>NoAction — common; the inline path correctly decided nothing
 *       was warranted, the scheduler agrees, no work done.</li>
 *   <li>AutoBump — calls {@code TriageService.systemTriggeredRetriage};
 *       its 60-second idempotency guard makes a duplicate impossible
 *       even if the inline path actually fired.</li>
 *   <li>Suggest — calls {@code TriageService.createRetriageSuggestionAlert};
 *       its unacknowledged-alert idempotency guard prevents duplicate
 *       alerts.</li>
 * </ul>
 *
 * <p>The 30-minute window is the explicit error budget for "we'd like
 * to never miss a re-triage signal by more than 30 minutes." Tighter
 * windows query more often for less benefit; wider ones risk firing
 * stale signals on patients whose state has since moved on. 30 minutes
 * matches the SATS YELLOW max-wait, which is the slowest category we
 * care about catching.
 *
 * <p>Failures inside the scheduler loop are logged per-event and never
 * propagate — one event failing should not stop the rest of the batch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetriageBackfillScheduler {

    private static final long WINDOW_MINUTES = 30;
    /**
     * Run every five minutes. Initial delay 60s so we don't try to
     * backfill before the database is fully up on a fresh container.
     */
    private static final long FIXED_RATE_MS = 5L * 60L * 1000L;
    private static final long INITIAL_DELAY_MS = 60L * 1000L;

    private final ClinicalSignEventRepository signEventRepository;
    private final TriageService triageService;

    @Scheduled(fixedRate = FIXED_RATE_MS, initialDelay = INITIAL_DELAY_MS)
    @Transactional
    public void backfillReevaluate() {
        Instant since = Instant.now().minus(WINDOW_MINUTES, ChronoUnit.MINUTES);
        List<ClinicalSignEvent> candidates;
        try {
            candidates = signEventRepository.findUnprocessedRecentEvents(since);
        } catch (Exception ex) {
            log.error("[retriage-backfill] Repository query failed: {}", ex.getMessage(), ex);
            return;
        }
        if (candidates.isEmpty()) return;

        log.info("[retriage-backfill] Re-evaluating {} unprocessed clinical-sign event(s) from the last {} minutes",
                candidates.size(), WINDOW_MINUTES);

        int autoBumps = 0, suggestions = 0, noActions = 0, errors = 0;

        for (ClinicalSignEvent event : candidates) {
            try {
                Visit visit = event.getVisit();
                if (visit == null) {
                    // Dangling event — should not happen given the FK on
                    // visit_id. Defensive skip.
                    continue;
                }

                // Round 4b — fetch the previous status for down-bump
                // evaluation. The "previous" is the most recent event
                // for this sign code that's strictly older than the
                // candidate. The repo helper returns oldest-first, so
                // we filter then take the last entry.
                List<ClinicalSignEvent> sameCodeHistory = signEventRepository
                        .findByVisitIdAndSignCodeAndIsActiveTrueOrderByRecordedAtAsc(
                                visit.getId(), event.getSignCode());
                ClinicalSignEvent prior = null;
                for (ClinicalSignEvent h : sameCodeHistory) {
                    if (h.getRecordedAt().isBefore(event.getRecordedAt())) {
                        prior = h;
                    }
                }

                String label = ClinicalSignDefinitions.labelOrCode(event.getSignCode());
                RetriageEvaluator.RetriageDecision decision = RetriageEvaluator.evaluate(
                        event.getSignCategory(),
                        event.getStatus(),
                        prior == null ? null : prior.getStatus(),
                        event.isBaseline(),
                        visit.isPediatric(),
                        visit.getCurrentTriageCategory(),
                        label);

                if (decision instanceof RetriageEvaluator.AutoBump bump) {
                    triageService.systemTriggeredRetriage(visit, event, bump.targetCategory(), bump.reason());
                    autoBumps++;
                } else if (decision instanceof RetriageEvaluator.Suggest s) {
                    triageService.createRetriageSuggestionAlert(visit, event, s.severity(), s.message());
                    suggestions++;
                } else {
                    noActions++;
                }
            } catch (Exception ex) {
                errors++;
                log.error("[retriage-backfill] Re-evaluation failed for event {}: {}",
                        event.getId(), ex.getMessage(), ex);
            }
        }

        log.info("[retriage-backfill] Done: {} auto-bumps, {} suggestions, {} no-action, {} errors",
                autoBumps, suggestions, noActions, errors);
    }
}
