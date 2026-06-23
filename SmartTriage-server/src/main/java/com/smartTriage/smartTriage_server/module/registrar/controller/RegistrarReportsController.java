package com.smartTriage.smartTriage_server.module.registrar.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.registrar.dto.CensusResponse;
import com.smartTriage.smartTriage_server.module.registrar.dto.IntakeLogRow;
import com.smartTriage.smartTriage_server.module.registrar.dto.UnidentifiedPatientRow;
import com.smartTriage.smartTriage_server.module.registrar.service.RegistrarReportsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Registrar reporting (R11) — operational front-desk reports for the registration desk: the intake
 * log, the unidentified-patient reconciliation queue (a safety follow-up surface), and a live
 * census. All gated to the registrar/admin audience at their own hospital
 * ({@code canAccessRegistrarReports}); CSV exports mirror the R7/R9 pattern.
 */
@RestController
@RequestMapping("/api/v1/registrar-reports")
@RequiredArgsConstructor
public class RegistrarReportsController {

    private final RegistrarReportsService registrarReportsService;

    @GetMapping("/hospital/{hospitalId}/intake-log")
    @PreAuthorize("@clinicalAuthz.canAccessRegistrarReports(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<IntakeLogRow>>> intakeLog(
            @PathVariable UUID hospitalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(registrarReportsService.getIntakeLog(hospitalId, from, to)));
    }

    @GetMapping("/hospital/{hospitalId}/intake-log/csv")
    @PreAuthorize("@clinicalAuthz.canAccessRegistrarReports(authentication, #hospitalId)")
    public ResponseEntity<String> intakeLogCsv(
            @PathVariable UUID hospitalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        StringBuilder sb = new StringBuilder("VisitNumber,ArrivalTime,ArrivalMode,Status,Patient,Age,Sex,Zone,Unidentified\n");
        for (IntakeLogRow r : registrarReportsService.getIntakeLog(hospitalId, from, to)) {
            sb.append(csv(r.visitNumber())).append(',').append(csv(r.arrivalTime())).append(',')
              .append(csv(r.arrivalMode())).append(',').append(csv(r.status())).append(',')
              .append(csv(r.patientName())).append(',').append(csv(r.ageYears())).append(',')
              .append(csv(r.sex())).append(',').append(csv(r.zone())).append(',')
              .append(r.unidentified()).append('\n');
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"intake-log_" + from + "_" + to + ".csv\"")
                .body(sb.toString());
    }

    @GetMapping("/hospital/{hospitalId}/unidentified")
    @PreAuthorize("@clinicalAuthz.canAccessRegistrarReports(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<UnidentifiedPatientRow>>> unidentified(@PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(registrarReportsService.getUnidentifiedQueue(hospitalId)));
    }

    @GetMapping("/hospital/{hospitalId}/unidentified/csv")
    @PreAuthorize("@clinicalAuthz.canAccessRegistrarReports(authentication, #hospitalId)")
    public ResponseEntity<String> unidentifiedCsv(@PathVariable UUID hospitalId) {
        StringBuilder sb = new StringBuilder("PatientId,PlaceholderLabel,PlaceholderAssignedAt,HoursWaiting\n");
        for (UnidentifiedPatientRow r : registrarReportsService.getUnidentifiedQueue(hospitalId)) {
            sb.append(csv(r.patientId())).append(',').append(csv(r.placeholderLabel())).append(',')
              .append(csv(r.placeholderAssignedAt())).append(',').append(csv(r.hoursWaiting())).append('\n');
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"unidentified-patients.csv\"")
                .body(sb.toString());
    }

    @GetMapping("/hospital/{hospitalId}/census")
    @PreAuthorize("@clinicalAuthz.canAccessRegistrarReports(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<CensusResponse>> census(@PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(registrarReportsService.getCensus(hospitalId)));
    }

    private static String csv(Object value) {
        if (value == null) return "";
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
