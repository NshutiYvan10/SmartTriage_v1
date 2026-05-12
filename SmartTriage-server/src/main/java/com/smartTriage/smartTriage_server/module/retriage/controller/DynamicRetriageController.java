package com.smartTriage.smartTriage_server.module.retriage.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.retriage.dto.OverduePatientResponse;
import com.smartTriage.smartTriage_server.module.retriage.dto.RetriageStatusResponse;
import com.smartTriage.smartTriage_server.module.retriage.service.ReassessmentSchedulerService;
import com.smartTriage.smartTriage_server.module.retriage.service.WaitingTimeMonitorService;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * DynamicRetriageController — exposes endpoints for the retriage dashboard,
 * providing real-time visibility into overdue reassessments and wait time violations.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/retriage")
@RequiredArgsConstructor
public class DynamicRetriageController {

    private final WaitingTimeMonitorService waitingTimeMonitorService;
    private final ReassessmentSchedulerService reassessmentSchedulerService;
    private final VisitRepository visitRepository;
    private final TriageRecordRepository triageRecordRepository;

    /**
     * Get all patients overdue for reassessment at a specific hospital.
     */
    @GetMapping("/overdue/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<OverduePatientResponse>>> getOverduePatients(
            @PathVariable UUID hospitalId) {
        List<OverduePatientResponse> overduePatients = reassessmentSchedulerService
                .getOverdueReassessments(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(
                "Found " + overduePatients.size() + " patients overdue for reassessment",
                overduePatients));
    }

    /**
     * Get all patients who have exceeded their SATS wait time target.
     */
    @GetMapping("/waiting-exceeded/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<OverduePatientResponse>>> getWaitTimeExceededPatients(
            @PathVariable UUID hospitalId) {
        List<OverduePatientResponse> exceededPatients = waitingTimeMonitorService
                .getWaitTimeExceededPatients(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(
                "Found " + exceededPatients.size() + " patients who exceeded wait time",
                exceededPatients));
    }

    /**
     * Get retriage status for a specific visit — includes time since last triage,
     * next reassessment due, and wait time status.
     */
    @GetMapping("/status/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<RetriageStatusResponse>> getRetriageStatus(
            @PathVariable UUID visitId) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", visitId));

        TriageCategory category = visit.getCurrentTriageCategory();
        int maxWaitMinutes = category != null ? category.getMaxWaitMinutes() : 0;

        // Get last triage time
        TriageRecord lastTriage = triageRecordRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visitId)
                .orElse(null);

        Instant lastTriageTime = lastTriage != null ? lastTriage.getTriageTime() : visit.getTriageTime();

        // Compute wait time from arrival or triage
        Instant waitStartTime = visit.getTriageTime() != null
                ? visit.getTriageTime()
                : visit.getArrivalTime();
        long waitTimeMinutes = Duration.between(waitStartTime, Instant.now()).toMinutes();

        // Compute next reassessment due
        Instant nextReassessmentDue = null;
        boolean isOverdue = false;
        if (lastTriageTime != null && category != null && category != TriageCategory.BLUE) {
            int reassessmentInterval = category.getMaxWaitMinutes();
            nextReassessmentDue = lastTriageTime.plus(reassessmentInterval, ChronoUnit.MINUTES);
            isOverdue = Instant.now().isAfter(nextReassessmentDue);
        }

        boolean isWaitTimeExceeded = category != null
                && category != TriageCategory.BLUE
                && waitTimeMinutes > maxWaitMinutes;

        String patientName = visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName();

        RetriageStatusResponse response = RetriageStatusResponse.builder()
                .visitId(visit.getId())
                .visitNumber(visit.getVisitNumber())
                .patientName(patientName)
                .currentCategory(category)
                .tewsScore(visit.getCurrentTewsScore())
                .lastTriageTime(lastTriageTime)
                .nextReassessmentDue(nextReassessmentDue)
                .waitTimeMinutes(waitTimeMinutes)
                .maxWaitMinutes(maxWaitMinutes)
                .isOverdue(isOverdue)
                .isWaitTimeExceeded(isWaitTimeExceeded)
                .build();

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
