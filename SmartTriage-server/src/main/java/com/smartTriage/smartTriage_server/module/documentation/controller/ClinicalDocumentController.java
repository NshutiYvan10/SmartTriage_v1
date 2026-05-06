package com.smartTriage.smartTriage_server.module.documentation.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.documentation.dto.*;
import com.smartTriage.smartTriage_server.module.documentation.service.ClinicalDocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Clinical Document management endpoints — legally compliant documentation system.
 *
 *   POST   /api/v1/documents/create                              → Create document
 *   PUT    /api/v1/documents/{id}/sign                            → Sign document
 *   PUT    /api/v1/documents/{id}/co-sign                         → Co-sign document
 *   POST   /api/v1/documents/{id}/amend                           → Amend (new linked document)
 *   GET    /api/v1/documents/visit/{visitId}                      → Documents for visit
 *   GET    /api/v1/documents/{id}                                 → Single document
 *   POST   /api/v1/documents/visit/{visitId}/discharge-summary    → Generate discharge summary
 *   POST   /api/v1/documents/visit/{visitId}/handover             → Generate handover document
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class ClinicalDocumentController {

    private final ClinicalDocumentService documentService;

    // ====================================================================
    // CREATE
    // ====================================================================

    @PostMapping("/create")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<ClinicalDocumentResponse>> createDocument(
            @Valid @RequestBody CreateDocumentRequest request) {
        ClinicalDocumentResponse response = documentService.createDocument(request.getVisitId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Clinical document created", response));
    }

    // ====================================================================
    // SIGN
    // ====================================================================

    @PutMapping("/{id}/sign")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<ClinicalDocumentResponse>> signDocument(
            @PathVariable UUID id,
            @Valid @RequestBody SignDocumentRequest request) {
        ClinicalDocumentResponse response = documentService.signDocument(
                id, request.getSignerName(), request.getLicenseNumber());
        return ResponseEntity.ok(ApiResponse.success("Document electronically signed", response));
    }

    // ====================================================================
    // CO-SIGN
    // ====================================================================

    @PutMapping("/{id}/co-sign")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR')")
    public ResponseEntity<ApiResponse<ClinicalDocumentResponse>> coSignDocument(
            @PathVariable UUID id,
            @Valid @RequestBody CoSignDocumentRequest request) {
        ClinicalDocumentResponse response = documentService.coSignDocument(id, request.getCoSignerName());
        return ResponseEntity.ok(ApiResponse.success("Document co-signed", response));
    }

    // ====================================================================
    // AMEND
    // ====================================================================

    @PostMapping("/{id}/amend")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<ClinicalDocumentResponse>> amendDocument(
            @PathVariable UUID id,
            @Valid @RequestBody AmendDocumentRequest request) {
        ClinicalDocumentResponse response = documentService.amendDocument(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Document amended — new amendment document created", response));
    }

    // ====================================================================
    // QUERIES
    // ====================================================================

    @GetMapping("/visit/{visitId}")
    public ResponseEntity<ApiResponse<Page<ClinicalDocumentResponse>>> getDocumentsForVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<ClinicalDocumentResponse> response = documentService.getDocumentsForVisit(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ClinicalDocumentResponse>> getDocument(@PathVariable UUID id) {
        ClinicalDocumentResponse response = documentService.getDocument(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ====================================================================
    // AUTO-GENERATION
    // ====================================================================

    @PostMapping("/visit/{visitId}/discharge-summary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR')")
    public ResponseEntity<ApiResponse<ClinicalDocumentResponse>> generateDischargeSummary(
            @PathVariable UUID visitId) {
        ClinicalDocumentResponse response = documentService.generateDischargeSummary(visitId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Discharge summary auto-generated", response));
    }

    @PostMapping("/visit/{visitId}/handover")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<ClinicalDocumentResponse>> generateHandoverDocument(
            @PathVariable UUID visitId) {
        ClinicalDocumentResponse response = documentService.generateHandoverDocument(visitId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Handover document auto-generated", response));
    }
}
