package com.smartTriage.smartTriage_server.module.clinical.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.NoteType;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * ClinicalNote — structured clinical documentation for an ED visit.
 *
 * Captures the various documentation sections from the Rwanda triage forms:
 *   - Physical findings / examination
 *   - History of presenting complaint
 *   - Past medical history
 *   - Allergies
 *   - Current medications (pre-visit)
 *   - Nursing notes
 *   - Doctor's notes
 *   - Progress notes
 *   - Treatment plan
 *   - Discharge summary
 *   - Handover notes
 *
 * Multiple notes can exist per visit, each with a specific type and timestamp.
 * This creates a chronological clinical narrative of the ED encounter.
 */
@Entity
@Table(name = "clinical_notes", indexes = {
        @Index(name = "idx_clinical_note_visit", columnList = "visit_id"),
        @Index(name = "idx_clinical_note_type", columnList = "note_type"),
        @Index(name = "idx_clinical_note_recorded_at", columnList = "recorded_at"),
        @Index(name = "idx_clinical_note_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClinicalNote extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    /** Type of clinical note */
    @Enumerated(EnumType.STRING)
    @Column(name = "note_type", nullable = false, length = 40)
    private NoteType noteType;

    /** The clinical text content */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Name of the clinician who recorded this note */
    @Column(name = "recorded_by_name", length = 255)
    private String recordedByName;

    /** Time the note was recorded */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** Optional: section or heading within the note */
    @Column(name = "section", length = 100)
    private String section;
}
