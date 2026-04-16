package com.smartTriage.smartTriage_server.module.clinical.dto;

import com.smartTriage.smartTriage_server.common.enums.NoteType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a clinical note.
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
    private Instant recordedAt;
    private String section;
    private Instant createdAt;
    private Instant updatedAt;
}
