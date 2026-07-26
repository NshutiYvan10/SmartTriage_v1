package com.smartTriage.smartTriage_server.module.override.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.override.dto.OverrideRecordResponse;
import com.smartTriage.smartTriage_server.module.override.service.OverrideRegisterService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Unified Override Register — read-only, for the hospital admin / clinical-safety officer
 * doing incident investigation. One endpoint aggregates every safety-gate bypass in the
 * system with who / on whom / when / why.
 *
 *   GET /api/v1/overrides/hospital/{hospitalId}?from&to&patientId&type
 *
 * Gated by {@code canAuditSafetyOverrides} (SUPER_ADMIN, or same-hospital HOSPITAL_ADMIN /
 * safety officer) — the same governance tier that reviews the med-safety-override
 * alerts and the break-the-glass register.
 */
@RestController
@RequestMapping("/api/v1/overrides")
@RequiredArgsConstructor
public class OverrideRegisterController {

    private final OverrideRegisterService overrideRegisterService;

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canAuditSafetyOverrides(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<OverrideRecordResponse>>> list(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) String type) {
        List<OverrideRecordResponse> records =
                overrideRegisterService.getOverrides(hospitalId, from, to, patientId, type);
        return ResponseEntity.ok(ApiResponse.success(records));
    }
}
