package com.smartTriage.smartTriage_server.module.clinical.repository;

import com.smartTriage.smartTriage_server.common.enums.NoteType;
import com.smartTriage.smartTriage_server.module.clinical.entity.ClinicalNote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClinicalNoteRepository extends JpaRepository<ClinicalNote, UUID> {

    Page<ClinicalNote> findByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(
            UUID visitId, Pageable pageable);

    List<ClinicalNote> findByVisitIdAndIsActiveTrueOrderByRecordedAtAsc(UUID visitId);

    List<ClinicalNote> findByVisitIdAndNoteTypeAndIsActiveTrueOrderByRecordedAtDesc(
            UUID visitId, NoteType noteType);

    Optional<ClinicalNote> findByIdAndIsActiveTrue(UUID id);

    /** Get the latest note of a given type for a visit */
    Optional<ClinicalNote> findFirstByVisitIdAndNoteTypeAndIsActiveTrueOrderByRecordedAtDesc(
            UUID visitId, NoteType noteType);
}
