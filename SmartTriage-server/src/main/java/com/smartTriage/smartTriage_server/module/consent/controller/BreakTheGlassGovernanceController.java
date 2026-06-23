package com.smartTriage.smartTriage_server.module.consent.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.consent.dto.BreakTheGlassEventResponse;
import com.smartTriage.smartTriage_server.module.consent.service.BreakTheGlassEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Governance surface for cross-hospital break-the-glass emergency overrides (Phase 3). A forensic
 * read for the same audience as the medication Override Audit ({@code canAuditSafetyOverrides}):
 * the actor's-hospital governance team sees every override its clinicians performed and signs off
 * on each. The feed is scoped to the actor's hospital, NOT the patient — break-the-glass is about
 * clinician accountability.
 */
@RestController
@RequestMapping("/api/v1/break-the-glass-events")
@RequiredArgsConstructor
public class BreakTheGlassGovernanceController {

    private final BreakTheGlassEventService breakTheGlassEventService;

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canAuditSafetyOverrides(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<BreakTheGlassEventResponse>>> getEvents(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false, defaultValue = "all") String range,
            @PageableDefault(size = 200) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                breakTheGlassEventService.getEventsForHospital(hospitalId, range, pageable)));
    }

    @PatchMapping("/hospital/{hospitalId}/{eventId}/acknowledge")
    @PreAuthorize("@clinicalAuthz.canAuditSafetyOverrides(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<BreakTheGlassEventResponse>> acknowledge(
            @PathVariable UUID hospitalId,
            @PathVariable UUID eventId,
            @RequestParam(required = false) String note) {
        return ResponseEntity.ok(ApiResponse.success(
                "Break-the-glass override acknowledged",
                breakTheGlassEventService.acknowledgeEvent(eventId, hospitalId, note)));
    }
}
