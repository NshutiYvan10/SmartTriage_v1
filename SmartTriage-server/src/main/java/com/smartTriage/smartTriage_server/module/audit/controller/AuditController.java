package com.smartTriage.smartTriage_server.module.audit.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.audit.dto.AuditLogResponse;
import com.smartTriage.smartTriage_server.module.audit.entity.AuditLog;
import com.smartTriage.smartTriage_server.module.audit.mapper.AuditMapper;
import com.smartTriage.smartTriage_server.module.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Audit-log read + export endpoints. Restricted to the governance/audit readers
 * via {@code canViewHospitalReports} (SUPER_ADMIN, or same-hospital
 * HOSPITAL_ADMIN) and scoped to the requested hospital. Write is automatic
 * (AuditInterceptor) — there is no create endpoint.
 *
 *   GET /api/v1/audit/hospital/{hospitalId}         → paged audit entries (optional from/to)
 *   GET /api/v1/audit/hospital/{hospitalId}/export  → CSV download
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canViewHospitalReports(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<AuditLogResponse>>> list(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "true") boolean includeAuth,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<AuditLogResponse> page = auditService
                .search(hospitalId, from, to, actorUserId, outcome, q, includeAuth, pageable)
                .map(AuditMapper::toResponse);
        auditService.enrichWithVisitRefs(page.getContent());
        return ResponseEntity.ok(ApiResponse.success(page));
    }

    /**
     * V107 — the incident timeline: every audited action that touched this visit,
     * oldest first (who did what, when, outcome — including FAILED/denied attempts).
     * Gated to the governance/audit readers via the visit's own hospital.
     */
    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canViewAuditForVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<AuditLogResponse>>> visitTrail(@PathVariable UUID visitId) {
        List<AuditLogResponse> rows = auditService.getForVisit(visitId).stream()
                .map(AuditMapper::toResponse)
                .collect(java.util.stream.Collectors.toList());
        auditService.enrichWithVisitRefs(rows);
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /**
     * CSV export — honours the SAME filters as the list (what the auditor sees is
     * what they export), defaults to the last 30 days, and includes the V107/V108
     * forensic columns (Visit/Patient + SourceIP/UserAgent).
     */
    @GetMapping("/hospital/{hospitalId}/export")
    @PreAuthorize("@clinicalAuthz.canViewHospitalReports(authentication, #hospitalId)")
    public ResponseEntity<byte[]> exportCsv(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "true") boolean includeAuth) {
        Instant rangeFrom = from != null ? from : Instant.now().minus(30, ChronoUnit.DAYS);
        Instant rangeTo = to != null ? to : Instant.now();
        List<AuditLog> rows = auditService.searchAll(
                hospitalId, rangeFrom, rangeTo, actorUserId, outcome, q, includeAuth);
        // Resolve visit display refs once for the whole export (V107 patient columns).
        List<AuditLogResponse> enriched = rows.stream()
                .map(AuditMapper::toResponse)
                .collect(java.util.stream.Collectors.toList());
        auditService.enrichWithVisitRefs(enriched);

        StringBuilder sb = new StringBuilder(
                "Timestamp,Actor,Role,Action,Visit,Patient,Method,Path,Status,Outcome,SourceIP,UserAgent\n");
        for (AuditLogResponse a : enriched) {
            sb.append(csv(a.getTimestamp())).append(',')
              .append(csv(a.getActorName())).append(',')
              .append(csv(a.getActorRole())).append(',')
              .append(csv(a.getAction())).append(',')
              .append(csv(a.getVisitNumber())).append(',')
              .append(csv(a.getPatientName())).append(',')
              .append(csv(a.getHttpMethod())).append(',')
              .append(csv(a.getPath())).append(',')
              .append(csv(a.getStatusCode())).append(',')
              .append(csv(a.getOutcome())).append(',')
              .append(csv(a.getSourceIp())).append(',')
              .append(csv(a.getUserAgent())).append('\n');
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log.csv\"")
                .body(body);
    }

    /** CSV-escape a cell: wrap in quotes and double internal quotes when needed. */
    private static String csv(Object value) {
        if (value == null) return "";
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
