package com.smartTriage.smartTriage_server.module.clinical.service;

import com.smartTriage.smartTriage_server.common.enums.NoteType;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.clinical.dto.ClinicalNoteResponse;
import com.smartTriage.smartTriage_server.module.clinical.dto.CreateClinicalNoteRequest;
import com.smartTriage.smartTriage_server.module.clinical.entity.ClinicalNote;
import com.smartTriage.smartTriage_server.module.clinical.mapper.ClinicalMapper;
import com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Clinical note service — manages structured clinical documentation for ED visits.
 *
 * Captures the various documentation sections from the Rwanda triage forms:
 *   - Physical findings / examination
 *   - History of presenting complaint
 *   - Past medical history, allergies, current medications
 *   - Nursing notes, doctor's notes, progress notes
 *   - Treatment plan, discharge summary, handover notes
 *
 * Multiple notes of any type can exist per visit, creating a chronological
 * clinical narrative of the ED encounter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClinicalNoteService {

    private final ClinicalNoteRepository clinicalNoteRepository;
    private final VisitService visitService;

    @Transactional
    public ClinicalNoteResponse createNote(CreateClinicalNoteRequest request) {
        Visit visit = visitService.findVisitOrThrow(request.getVisitId());

        ClinicalNote note = ClinicalNote.builder()
                .visit(visit)
                .noteType(request.getNoteType())
                .content(request.getContent())
                .recordedByName(request.getRecordedByName())
                .recordedAt(Instant.now())
                .section(request.getSection())
                .build();

        note = clinicalNoteRepository.save(note);

        log.info("Clinical note created for visit {} — type:{} section:'{}'",
                visit.getVisitNumber(), note.getNoteType(), note.getSection());

        return ClinicalMapper.toResponse(note);
    }

    @Transactional
    public ClinicalNoteResponse updateNote(UUID noteId, CreateClinicalNoteRequest request) {
        ClinicalNote note = findNoteOrThrow(noteId);

        note.setNoteType(request.getNoteType());
        note.setContent(request.getContent());
        note.setRecordedByName(request.getRecordedByName());
        note.setSection(request.getSection());

        note = clinicalNoteRepository.save(note);

        log.info("Clinical note updated — id:{} type:{}", note.getId(), note.getNoteType());

        return ClinicalMapper.toResponse(note);
    }

    @Transactional
    public void deleteNote(UUID noteId) {
        ClinicalNote note = findNoteOrThrow(noteId);
        note.softDelete();
        clinicalNoteRepository.save(note);
        log.info("Clinical note soft-deleted — id:{}", noteId);
    }

    public Page<ClinicalNoteResponse> getNotesByVisit(UUID visitId, Pageable pageable) {
        return clinicalNoteRepository
                .findByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visitId, pageable)
                .map(ClinicalMapper::toResponse);
    }

    public List<ClinicalNoteResponse> getAllNotesForVisit(UUID visitId) {
        return clinicalNoteRepository
                .findByVisitIdAndIsActiveTrueOrderByRecordedAtAsc(visitId)
                .stream()
                .map(ClinicalMapper::toResponse)
                .collect(Collectors.toList());
    }

    public List<ClinicalNoteResponse> getNotesByType(UUID visitId, NoteType noteType) {
        return clinicalNoteRepository
                .findByVisitIdAndNoteTypeAndIsActiveTrueOrderByRecordedAtDesc(visitId, noteType)
                .stream()
                .map(ClinicalMapper::toResponse)
                .collect(Collectors.toList());
    }

    public Optional<ClinicalNoteResponse> getLatestNoteByType(UUID visitId, NoteType noteType) {
        return clinicalNoteRepository
                .findFirstByVisitIdAndNoteTypeAndIsActiveTrueOrderByRecordedAtDesc(visitId, noteType)
                .map(ClinicalMapper::toResponse);
    }

    public ClinicalNoteResponse getNote(UUID noteId) {
        return ClinicalMapper.toResponse(findNoteOrThrow(noteId));
    }

    public ClinicalNote findNoteOrThrow(UUID id) {
        return clinicalNoteRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("ClinicalNote", "id", id));
    }
}
