package com.smartTriage.smartTriage_server.module.clinicalsigns.service;

import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.clinicalsigns.dto.ClinicalSignEventResponse;
import com.smartTriage.smartTriage_server.module.clinicalsigns.dto.RecordClinicalSignRequest;
import com.smartTriage.smartTriage_server.module.clinicalsigns.dto.RecordClinicalSignsBatchRequest;
import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignCategory;
import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignEvent;
import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignStatus;
import com.smartTriage.smartTriage_server.module.clinicalsigns.mapper.ClinicalSignMapper;
import com.smartTriage.smartTriage_server.module.clinicalsigns.repository.ClinicalSignEventRepository;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ClinicalSignService — read and write the clinical-sign event log.
 *
 * Three responsibilities:
 *   1. Bootstrap from triage: when a triage is performed, write one
 *      isBaseline=true PRESENT event per positive triage flag. The
 *      timeline starts populated.
 *   2. Record updates: doctor / nurse posts one or more sign updates;
 *      each becomes its own event row keyed to the same recorded_at.
 *   3. Read views: full history (chronological) and current state
 *      (latest event per sign_code).
 *
 * Failures during baseline recording are logged but never propagated up
 * the triage save — a triage submission succeeding with an empty
 * clinical-signs timeline is recoverable; a triage submission FAILING
 * because the clinical-signs write hiccupped is not. The doctor's chart
 * already shows the triage signs separately, so a missed baseline
 * doesn't lose data, only the in-time timeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClinicalSignService {

    private final ClinicalSignEventRepository repository;
    private final VisitRepository visitRepository;

    /**
     * Auto-record baseline events from a freshly-saved triage record. Each
     * positive triage flag becomes a PRESENT event with isBaseline=true,
     * tagged with the triage's recorded_at time. Numeric values (glucose
     * for convulsions / coma / DKA discriminators) ride along.
     *
     * Called from TriageService.performTriage AFTER the TriageRecord is
     * persisted. Any failure here is logged and swallowed — see class
     * docs for rationale.
     */
    @Transactional
    public void recordBaselineFromTriage(TriageRecord triage) {
        if (triage == null || triage.getVisit() == null) return;
        try {
            Visit visit = triage.getVisit();
            // Resolve the patient from the visit. The Visit entity's patient
            // is lazy; we need it eagerly to set the denormalized fk.
            // visitRepository.findByIdAndIsActiveTrue eagerly initializes
            // (or we can rely on the same-transaction Hibernate session).
            UUID patientId = visit.getPatient() != null ? visit.getPatient().getId() : null;
            if (patientId == null) {
                log.warn("[clinicalsigns] Cannot baseline triage {}: visit has no patient", triage.getId());
                return;
            }

            String recorderName = composeUserName(triage.getTriagedBy());
            if (recorderName == null || recorderName.isBlank()) {
                recorderName = triage.getTriageNurseName();
            }
            User recorder = triage.getTriagedBy();
            Instant when = triage.getTriageTime() != null ? triage.getTriageTime() : Instant.now();

            List<ClinicalSignEvent> baselineEvents = new ArrayList<>();
            for (ClinicalSignDefinitions.SignMapping mapping : ClinicalSignDefinitions.ALL) {
                if (!mapping.isPositive().test(triage)) continue;
                Double numeric = mapping.numericValue().apply(triage);
                ClinicalSignEvent event = ClinicalSignEvent.builder()
                        .visit(visit)
                        .patient(visit.getPatient())
                        .signCode(mapping.code())
                        .signCategory(mapping.category())
                        .status(ClinicalSignStatus.PRESENT)
                        .numericValue(numeric)
                        .notes("Baseline recorded automatically from triage")
                        .recordedAt(when)
                        .recordedBy(recorder)
                        .recordedByName(recorderName)
                        .isBaseline(true)
                        .build();
                baselineEvents.add(event);
            }

            if (!baselineEvents.isEmpty()) {
                repository.saveAll(baselineEvents);
                log.info("[clinicalsigns] Recorded {} baseline events for triage {} on visit {}",
                        baselineEvents.size(), triage.getId(), visit.getId());
            }
        } catch (Exception ex) {
            // Swallow — triage submission must not fail because of clinical-
            // sign bookkeeping. The doctor still sees triage signs on the chart.
            log.error("[clinicalsigns] Baseline recording failed for triage {}: {}",
                    triage.getId(), ex.getMessage(), ex);
        }
    }

    /**
     * Record one or more sign updates as a batch — all events share the
     * recordedAt timestamp (or NOW() if not supplied). Each entry becomes
     * its own ClinicalSignEvent row. Unknown sign codes are rejected up
     * front so the audit log doesn't accumulate typos.
     */
    @Transactional
    public List<ClinicalSignEventResponse> recordBatch(RecordClinicalSignsBatchRequest request) {
        User recorder = resolveCurrentUser();
        Visit visit = visitRepository.findByIdAndIsActiveTrue(request.getVisitId())
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", request.getVisitId()));
        if (visit.getPatient() == null) {
            throw new IllegalStateException("Visit " + visit.getId() + " has no patient");
        }
        Instant when = request.getRecordedAt() != null ? request.getRecordedAt() : Instant.now();
        String recorderName = request.getRecordedByName();
        if ((recorderName == null || recorderName.isBlank()) && recorder != null) {
            recorderName = composeUserName(recorder);
        }

        // Validate all codes up front so we don't half-write a batch with
        // a typo in the middle.
        for (RecordClinicalSignRequest entry : request.getEvents()) {
            if (!ClinicalSignDefinitions.isKnownCode(entry.getSignCode())) {
                throw new IllegalArgumentException("Unknown clinical sign code: " + entry.getSignCode());
            }
        }

        List<ClinicalSignEvent> toSave = new ArrayList<>();
        for (RecordClinicalSignRequest entry : request.getEvents()) {
            ClinicalSignCategory category = ClinicalSignDefinitions.CATEGORY_BY_CODE.get(entry.getSignCode());
            ClinicalSignEvent event = ClinicalSignEvent.builder()
                    .visit(visit)
                    .patient(visit.getPatient())
                    .signCode(entry.getSignCode())
                    .signCategory(category)
                    .status(entry.getStatus())
                    .numericValue(entry.getNumericValue())
                    .notes(entry.getNotes())
                    .recordedAt(when)
                    .recordedBy(recorder)
                    .recordedByName(recorderName)
                    .isBaseline(false)
                    .build();
            toSave.add(event);
        }

        List<ClinicalSignEvent> saved = repository.saveAll(toSave);
        log.info("[clinicalsigns] Recorded {} sign updates for visit {} at {}",
                saved.size(), visit.getId(), when);
        return saved.stream().map(ClinicalSignMapper::toResponse).collect(Collectors.toList());
    }

    /** Full event history for a visit, oldest-first. */
    public List<ClinicalSignEventResponse> getHistoryForVisit(UUID visitId) {
        return repository.findByVisitIdAndIsActiveTrueOrderByRecordedAtAsc(visitId)
                .stream().map(ClinicalSignMapper::toResponse).collect(Collectors.toList());
    }

    /** Latest event per sign_code — drives the "Current State" UI panel. */
    public List<ClinicalSignEventResponse> getCurrentStateForVisit(UUID visitId) {
        return repository.findCurrentStateForVisit(visitId)
                .stream().map(ClinicalSignMapper::toResponse).collect(Collectors.toList());
    }

    /** History for a single sign code on this visit — per-sign mini-timeline. */
    public List<ClinicalSignEventResponse> getSignHistory(UUID visitId, String signCode) {
        return repository.findByVisitIdAndSignCodeAndIsActiveTrueOrderByRecordedAtAsc(visitId, signCode)
                .stream().map(ClinicalSignMapper::toResponse).collect(Collectors.toList());
    }

    /**
     * Composes a display name from a User. The User entity stores
     * firstName + lastName separately (no fullName getter), so we
     * compose them here. Returns null when both are absent so callers
     * can fall back to other sources.
     */
    /** Resolves the authenticated User from the Spring security context, or null. */
    private User resolveCurrentUser() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof User u) return u;
        } catch (Exception e) {
            log.debug("[clinicalsigns] could not resolve current user from security context");
        }
        return null;
    }

    private static String composeUserName(User user) {
        if (user == null) return null;
        String first = user.getFirstName();
        String last = user.getLastName();
        boolean hasFirst = first != null && !first.isBlank();
        boolean hasLast = last != null && !last.isBlank();
        if (hasFirst && hasLast) return first + " " + last;
        if (hasFirst) return first;
        if (hasLast) return last;
        return null;
    }
}
