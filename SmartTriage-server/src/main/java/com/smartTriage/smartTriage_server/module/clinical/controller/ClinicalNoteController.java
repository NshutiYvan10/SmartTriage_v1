package com.smartTriage.smartTriage_server.module.clinical.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.NoteType;
import com.smartTriage.smartTriage_server.module.clinical.dto.ClinicalNoteResponse;
import com.smartTriage.smartTriage_server.module.clinical.dto.CreateClinicalNoteRequest;
import com.smartTriage.smartTriage_server.module.clinical.service.ClinicalNoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Clinical note management endpoints.
 *
 * <p>Notes are append-only. There is no in-place update endpoint — to correct
 * an earlier note, callers POST to {@code /{id}/supersede}, which writes a new
 * row referencing the original via {@code supersedesId}. The original row is
 * never modified.</p>
 *
 * <p>Hard delete is reserved for system administrators; clinical staff should
 * use supersede for routine corrections so the original record is preserved.</p>
 *
 * <pre>
 *   POST   /api/v1/clinical-notes                         → Create note
 *   POST   /api/v1/clinical-notes/{id}/supersede          → Correct an existing note
 *   DELETE /api/v1/clinical-notes/{id}                    → Soft-delete (admin only)
 *   GET    /api/v1/clinical-notes/{id}                    → Single record
 *   GET    /api/v1/clinical-notes/visit/{visitId}         → Paginated list
 *   GET    /api/v1/clinical-notes/visit/{visitId}/all     → Full list
 *   GET    /api/v1/clinical-notes/visit/{visitId}/type/{type} → By note type
 *   GET    /api/v1/clinical-notes/visit/{visitId}/type/{type}/latest → Latest of type
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/clinical-notes")
@RequiredArgsConstructor
public class ClinicalNoteController {

    private final ClinicalNoteService clinicalNoteService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<ClinicalNoteResponse>> createNote(
            @Valid @RequestBody CreateClinicalNoteRequest request) {
        ClinicalNoteResponse response = clinicalNoteService.createNote(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Clinical note created", response));
    }

    /**
     * Correct an existing clinical note. Writes a new row that references the
     * original via {@code supersedesId}; the original is never modified. Both
     * rows remain visible to readers so the correction trail is auditable.
     */
    @PostMapping("/{id}/supersede")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<ClinicalNoteResponse>> supersedeNote(
            @PathVariable UUID id,
            @Valid @RequestBody CreateClinicalNoteRequest request) {
        ClinicalNoteResponse response = clinicalNoteService.supersedeNote(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Clinical note superseded", response));
    }

    /**
     * Soft-delete a note. Restricted to system/hospital administrators —
     * routine clinical corrections must use the supersede endpoint so the
     * original record is preserved.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteNote(@PathVariable UUID id) {
        clinicalNoteService.deleteNote(id);
        return ResponseEntity.ok(ApiResponse.success("Clinical note deleted", null));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ClinicalNoteResponse>> getNote(@PathVariable UUID id) {
        ClinicalNoteResponse response = clinicalNoteService.getNote(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<Page<ClinicalNoteResponse>>> getNotesByVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<ClinicalNoteResponse> response = clinicalNoteService.getNotesByVisit(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/all")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<ClinicalNoteResponse>>> getAllNotesForVisit(
            @PathVariable UUID visitId) {
        List<ClinicalNoteResponse> response = clinicalNoteService.getAllNotesForVisit(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/type/{type}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<ClinicalNoteResponse>>> getNotesByType(
            @PathVariable UUID visitId,
            @PathVariable NoteType type) {
        List<ClinicalNoteResponse> response = clinicalNoteService.getNotesByType(visitId, type);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/type/{type}/latest")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<ClinicalNoteResponse>> getLatestNoteByType(
            @PathVariable UUID visitId,
            @PathVariable NoteType type) {
        return clinicalNoteService.getLatestNoteByType(visitId, type)
                .map(note -> ResponseEntity.ok(ApiResponse.success(note)))
                .orElse(ResponseEntity.ok(ApiResponse.success("No notes found", null)));
    }
}
