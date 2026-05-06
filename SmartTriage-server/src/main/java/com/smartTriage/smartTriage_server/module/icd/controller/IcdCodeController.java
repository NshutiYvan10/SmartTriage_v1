package com.smartTriage.smartTriage_server.module.icd.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.icd.dto.IcdCodeResponse;
import com.smartTriage.smartTriage_server.module.icd.service.IcdCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ICD-10 code reference endpoints.
 *
 *   GET /api/v1/icd-codes/search?query=...   — autocomplete (empty query
 *                                              returns common-in-Rwanda)
 *   GET /api/v1/icd-codes                    — paginated browse
 *   GET /api/v1/icd-codes/common             — curated common-in-Rwanda list
 *
 * Read-only for all clinical roles. Catalog is curated; no clinician-facing
 * write endpoints — new entries go through migrations or admin tooling.
 */
@RestController
@RequestMapping("/api/v1/icd-codes")
@RequiredArgsConstructor
public class IcdCodeController {

    private final IcdCodeService icdCodeService;

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<List<IcdCodeResponse>>> search(
            @RequestParam(required = false) String query) {
        return ResponseEntity.ok(ApiResponse.success(icdCodeService.search(query)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<Page<IcdCodeResponse>>> browse(
            @PageableDefault(size = 100) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(icdCodeService.browse(pageable)));
    }

    @GetMapping("/common")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<List<IcdCodeResponse>>> common() {
        return ResponseEntity.ok(ApiResponse.success(icdCodeService.getCommonInRwanda()));
    }
}
