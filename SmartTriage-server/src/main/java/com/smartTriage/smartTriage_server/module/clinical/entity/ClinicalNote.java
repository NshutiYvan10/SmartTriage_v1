package com.smartTriage.smartTriage_server.module.clinical.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.NoteType;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

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

    /** Name of the clinician who recorded this note (display value) */
    @Column(name = "recorded_by_name", length = 255)
    private String recordedByName;

    /**
     * UUID of the User who wrote the note. Server-derived from the security
     * context — never trusted from the client request body. Null only for
     * legacy rows created before V21.
     */
    @Column(name = "author_user_id")
    private UUID authorUserId;

    /**
     * Role of the author at time of write (DOCTOR, NURSE, ...). Captured at
     * write time so the timeline still renders correctly even if the user's
     * role changes later or the user is deactivated.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "author_role", length = 30)
    private Role authorRole;

    /**
     * If this note corrects/replaces an earlier one, this is the original's
     * id. The original row is never modified — corrections create a new row
     * pointing back via this FK. Audit traversal walks the chain to render
     * "Note A (corrected at T2)" → "Note B (corrects A)".
     */
    @Column(name = "supersedes_id")
    private UUID supersedesId;

    /** Time the note was recorded */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** Optional: section or heading within the note */
    @Column(name = "section", length = 100)
    private String section;
}
