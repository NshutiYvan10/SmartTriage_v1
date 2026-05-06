package com.smartTriage.smartTriage_server.module.labcatalog.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.InvestigationType;
import com.smartTriage.smartTriage_server.module.labcatalog.dto.LabTestCatalogResponse;
import com.smartTriage.smartTriage_server.module.labcatalog.service.LabTestCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Lab / diagnostic test reference endpoints.
 *
 *   GET /api/v1/lab-catalog/search?query=...   — autocomplete
 *   GET /api/v1/lab-catalog/by-type/{type}     — list by InvestigationType
 *   GET /api/v1/lab-catalog/common             — Rwanda-common quick list
 */
@RestController
@RequestMapping("/api/v1/lab-catalog")
@RequiredArgsConstructor
public class LabTestCatalogController {

    private final LabTestCatalogService service;

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'TRIAGE_NURSE', 'LAB_TECHNICIAN')")
    public ResponseEntity<ApiResponse<List<LabTestCatalogResponse>>> search(
            @RequestParam(required = false) String query) {
        return ResponseEntity.ok(ApiResponse.success(service.search(query)));
    }

    @GetMapping("/by-type/{type}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'TRIAGE_NURSE', 'LAB_TECHNICIAN')")
    public ResponseEntity<ApiResponse<List<LabTestCatalogResponse>>> byType(
            @PathVariable InvestigationType type) {
        return ResponseEntity.ok(ApiResponse.success(service.findByType(type)));
    }

    @GetMapping("/common")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'TRIAGE_NURSE', 'LAB_TECHNICIAN')")
    public ResponseEntity<ApiResponse<List<LabTestCatalogResponse>>> common() {
        return ResponseEntity.ok(ApiResponse.success(service.getCommonInRwanda()));
    }
}
