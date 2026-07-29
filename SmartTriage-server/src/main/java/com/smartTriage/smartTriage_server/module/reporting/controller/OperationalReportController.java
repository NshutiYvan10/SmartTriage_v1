package com.smartTriage.smartTriage_server.module.reporting.controller;

import com.smartTriage.smartTriage_server.module.reporting.service.OperationalReportService;
import com.smartTriage.smartTriage_server.module.reporting.service.OperationalReportService.RenderedCsv;
import com.smartTriage.smartTriage_server.module.reporting.service.OperationalReportService.RenderedPdf;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * OperationalReportController — the report-catalog endpoints. Every download is an
 * authz-gated GET, so the V107 audit interceptor records WHO generated WHICH report
 * WHEN with what parameters (the query string is part of the audited path).
 *
 * Authorization tiers:
 * <ul>
 *   <li>Daily activity + shift handover — operational leadership
 *       ({@code canSeeAllZonesAtHospital}: hospital admin, charge nurse, shift lead,
 *       super admin). These contain hospital-wide patient-flow data.</li>
 *   <li>Period activity + quality metrics — the governance/reporting tier
 *       ({@code canViewHospitalReports} OR operational leadership, so
 *       auditors keep their reporting access).</li>
 *   <li>My activity — ANY clinician, but self-scoped BY CONSTRUCTION: the subject
 *       is always the authenticated principal; there is deliberately no userId
 *       parameter to enumerate.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports/operational")
@RequiredArgsConstructor
public class OperationalReportController {

    private final OperationalReportService reportService;

    @GetMapping("/daily")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<byte[]> dailyActivity(
            @RequestParam UUID hospitalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication) {
        return pdf(reportService.dailyActivity(hospitalId, date, actor(authentication)));
    }

    @GetMapping("/shift-summary")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<byte[]> shiftHandoverSummary(
            @RequestParam UUID hospitalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam String period,
            Authentication authentication) {
        return pdf(reportService.shiftHandoverSummary(hospitalId, date, period, actor(authentication)));
    }

    @GetMapping("/period")
    @PreAuthorize("@clinicalAuthz.canViewHospitalReports(authentication, #hospitalId) "
            + "or @clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<byte[]> periodActivity(
            @RequestParam UUID hospitalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {
        return pdf(reportService.periodActivity(hospitalId, from, to, actor(authentication)));
    }

    @GetMapping("/period/csv")
    @PreAuthorize("@clinicalAuthz.canViewHospitalReports(authentication, #hospitalId) "
            + "or @clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<String> periodActivityCsv(
            @RequestParam UUID hospitalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return csv(reportService.periodActivityCsv(hospitalId, from, to));
    }

    @GetMapping("/my-activity")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> myActivity(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {
        User caller = authentication != null && authentication.getPrincipal() instanceof User u ? u : null;
        if (caller == null) throw new IllegalArgumentException("No authenticated clinician.");
        return pdf(reportService.myActivity(caller, from, to));
    }

    @GetMapping("/quality")
    @PreAuthorize("@clinicalAuthz.canViewHospitalReports(authentication, #hospitalId) "
            + "or @clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<byte[]> qualityMetrics(
            @RequestParam UUID hospitalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {
        return pdf(reportService.qualityMetrics(hospitalId, from, to, actor(authentication)));
    }

    @GetMapping("/quality/csv")
    @PreAuthorize("@clinicalAuthz.canViewHospitalReports(authentication, #hospitalId) "
            + "or @clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<String> qualityMetricsCsv(
            @RequestParam UUID hospitalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return csv(reportService.qualityMetricsCsv(hospitalId, from, to));
    }

    private static String actor(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof User u) {
            String name = (u.getFirstName() + " " + u.getLastName()).trim();
            return name.isBlank() ? u.getEmail() : name;
        }
        return "SmartTriage user";
    }

    private static ResponseEntity<byte[]> pdf(RenderedPdf pdf) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdf.filename() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.bytes());
    }

    private static ResponseEntity<String> csv(RenderedCsv csv) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + csv.filename() + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.csv());
    }
}
