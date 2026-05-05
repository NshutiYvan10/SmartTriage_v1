package com.smartTriage.smartTriage_server.module.clinical.dto;

import com.smartTriage.smartTriage_server.common.enums.NoteType;
import com.smartTriage.smartTriage_server.common.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a clinical note.
 *
 * authorUserId / authorRole are server-derived audit attribution. supersedesId
 * is non-null when this note corrects an earlier one (the original is never
 * modified — see {@link com.smartTriage.smartTriage_server.module.clinical.service.ClinicalNoteService#supersedeNote}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicalNoteResponse {

    private UUID id;
    private UUID visitId;
    private NoteType noteType;
    private String content;
    private String recordedByName;
    private UUID authorUserId;
    private Role authorRole;
    private UUID supersedesId;
    private Instant recordedAt;
    private String section;
    private Instant createdAt;
    private Instant updatedAt;
}
