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
 *   POST   /api/v1/clinical-notes                         → Create note
 *   PUT    /api/v1/clinical-notes/{id}                     → Update note
 *   DELETE /api/v1/clinical-notes/{id}                     → Soft-delete note
 *   GET    /api/v1/clinical-notes/{id}                     → Single record
 *   GET    /api/v1/clinical-notes/visit/{visitId}          → Paginated list
 *   GET    /api/v1/clinical-notes/visit/{visitId}/all      → Full list
 *   GET    /api/v1/clinical-notes/visit/{visitId}/type/{type} → By note type
 *   GET    /api/v1/clinical-notes/visit/{visitId}/type/{type}/latest → Latest of type
 */
@RestController
@RequestMapping("/api/v1/clinical-notes")
@RequiredArgsConstructor
public class ClinicalNoteController {

    private final ClinicalNoteService clinicalNoteService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<ClinicalNoteResponse>> createNote(
            @Valid @RequestBody CreateClinicalNoteRequest request) {
        ClinicalNoteResponse response = clinicalNoteService.createNote(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Clinical note created", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<ClinicalNoteResponse>> updateNote(
            @PathVariable UUID id,
            @Valid @RequestBody CreateClinicalNoteRequest request) {
        ClinicalNoteResponse response = clinicalNoteService.updateNote(id, request);
        return ResponseEntity.ok(ApiResponse.success("Clinical note updated", response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR')")
    public ResponseEntity<ApiResponse<Void>> deleteNote(@PathVariable UUID id) {
        clinicalNoteService.deleteNote(id);
        return ResponseEntity.ok(ApiResponse.success("Clinical note deleted", null));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ClinicalNoteResponse>> getNote(@PathVariable UUID id) {
        ClinicalNoteResponse response = clinicalNoteService.getNote(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}")
    public ResponseEntity<ApiResponse<Page<ClinicalNoteResponse>>> getNotesByVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<ClinicalNoteResponse> response = clinicalNoteService.getNotesByVisit(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/all")
    public ResponseEntity<ApiResponse<List<ClinicalNoteResponse>>> getAllNotesForVisit(
            @PathVariable UUID visitId) {
        List<ClinicalNoteResponse> response = clinicalNoteService.getAllNotesForVisit(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/type/{type}")
    public ResponseEntity<ApiResponse<List<ClinicalNoteResponse>>> getNotesByType(
            @PathVariable UUID visitId,
            @PathVariable NoteType type) {
        List<ClinicalNoteResponse> response = clinicalNoteService.getNotesByType(visitId, type);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/type/{type}/latest")
    public ResponseEntity<ApiResponse<ClinicalNoteResponse>> getLatestNoteByType(
            @PathVariable UUID visitId,
            @PathVariable NoteType type) {
        return clinicalNoteService.getLatestNoteByType(visitId, type)
                .map(note -> ResponseEntity.ok(ApiResponse.success(note)))
                .orElse(ResponseEntity.ok(ApiResponse.success("No notes found", null)));
    }
}
