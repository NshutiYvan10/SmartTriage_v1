package com.smartTriage.smartTriage_server.module.clinical.service;

import com.smartTriage.smartTriage_server.common.enums.NoteType;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.clinical.dto.ClinicalNoteResponse;
import com.smartTriage.smartTriage_server.module.clinical.dto.CreateClinicalNoteRequest;
import com.smartTriage.smartTriage_server.module.clinical.entity.ClinicalNote;
import com.smartTriage.smartTriage_server.module.clinical.mapper.ClinicalMapper;
import com.smartTriage.smartTriage_server.module.clinical.repository.ClinicalNoteRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
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
 *
 * <h3>Immutability and corrections</h3>
 * Once a note is saved, the row is never modified — clinical notes are
 * legal-grade records and must be tamper-evident. Corrections are handled via
 * {@link #supersedeNote(UUID, CreateClinicalNoteRequest)}, which writes a new
 * row pointing back to the original via {@code supersedes_id}. The original
 * row remains visible in the timeline; UIs render the chain so a reader can
 * see "Note A (corrected at T2) → Note B (corrects A)".
 *
 * <h3>Attribution</h3>
 * The author identity ({@code authorUserId}, {@code authorRole},
 * {@code recordedByName}) is always derived from the security context — it is
 * never trusted from the client request body. A client-supplied
 * {@code recordedByName} is ignored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClinicalNoteService {

    private final ClinicalNoteRepository clinicalNoteRepository;
    private final VisitService visitService;
    private final RealTimeEventPublisher realTimeEventPublisher;

    @Transactional
    public ClinicalNoteResponse createNote(CreateClinicalNoteRequest request) {
        Visit visit = visitService.findVisitOrThrow(request.getVisitId());
        User author = resolveCurrentUserOrThrow();

        ClinicalNote note = ClinicalNote.builder()
                .visit(visit)
                .noteType(request.getNoteType())
                .content(request.getContent())
                // recordedByName, authorUserId, authorRole are server-derived;
                // any value supplied by the client request is intentionally ignored.
                .recordedByName(displayNameOf(author))
                .authorUserId(author.getId())
                .authorRole(author.getRole())
                .recordedAt(Instant.now())
                .section(request.getSection())
                .build();

        note = clinicalNoteRepository.save(note);

        log.info("Clinical note created for visit {} — type:{} section:'{}' authorUserId:{} role:{}",
                visit.getVisitNumber(), note.getNoteType(), note.getSection(),
                note.getAuthorUserId(), note.getAuthorRole());

        ClinicalNoteResponse response = ClinicalMapper.toResponse(note);
        realTimeEventPublisher.publishClinicalNote(visit.getId(), response);
        return response;
    }

    /**
     * Create a correction that supersedes an existing note.
     *
     * The original row is never modified — it remains in the timeline as a
     * matter of record. A new row is created with {@code supersedesId} set to
     * the original's id; readers walk that chain to render the correction
     * trail.
     *
     * The {@code visitId} on the new row is inherited from the original — the
     * client cannot move a correction onto a different visit.
     */
    @Transactional
    public ClinicalNoteResponse supersedeNote(UUID originalNoteId, CreateClinicalNoteRequest request) {
        ClinicalNote original = findNoteOrThrow(originalNoteId);
        User author = resolveCurrentUserOrThrow();

        ClinicalNote correction = ClinicalNote.builder()
                .visit(original.getVisit())
                .noteType(request.getNoteType() != null ? request.getNoteType() : original.getNoteType())
                .content(request.getContent())
                .recordedByName(displayNameOf(author))
                .authorUserId(author.getId())
                .authorRole(author.getRole())
                .supersedesId(original.getId())
                .recordedAt(Instant.now())
                .section(request.getSection() != null ? request.getSection() : original.getSection())
                .build();

        correction = clinicalNoteRepository.save(correction);

        log.info("Clinical note superseded — originalId:{} correctionId:{} authorUserId:{} role:{}",
                original.getId(), correction.getId(), correction.getAuthorUserId(), correction.getAuthorRole());

        ClinicalNoteResponse response = ClinicalMapper.toResponse(correction);
        realTimeEventPublisher.publishClinicalNote(original.getVisit().getId(), response);
        return response;
    }

    /**
     * Soft-delete a note. Restricted at the controller layer to admin roles —
     * for routine clinical corrections, callers must use
     * {@link #supersedeNote(UUID, CreateClinicalNoteRequest)} instead so the
     * original record is preserved.
     */
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

    /**
     * Resolve the authenticated User from the security context. Throws if the
     * principal is missing or not a {@link User} — clinical notes must always
     * be attributable, so an anonymous write is a hard failure rather than a
     * silently-NULL author.
     */
    private User resolveCurrentUserOrThrow() {
        Object principal = null;
        try {
            principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        } catch (Exception e) {
            log.warn("No authentication present when writing clinical note");
        }
        if (principal instanceof User) {
            return (User) principal;
        }
        throw new AccessDeniedException(
                "Clinical notes require an authenticated user; principal=" + principal);
    }

    private static String displayNameOf(User user) {
        String first = user.getFirstName();
        String last = user.getLastName();
        if ((first == null || first.isBlank()) && (last == null || last.isBlank())) {
            return user.getEmail();
        }
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isBlank()) sb.append(first.trim());
        if (last != null && !last.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(last.trim());
        }
        return sb.toString();
    }
}
