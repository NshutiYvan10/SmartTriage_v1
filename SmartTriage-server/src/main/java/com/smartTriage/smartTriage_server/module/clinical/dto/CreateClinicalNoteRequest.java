package com.smartTriage.smartTriage_server.module.clinical.dto;

import com.smartTriage.smartTriage_server.common.enums.NoteType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to create a clinical note.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateClinicalNoteRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @NotNull(message = "Note type is required")
    private NoteType noteType;

    @NotBlank(message = "Content is required")
    private String content;

    /** Name of recording clinician */
    private String recordedByName;

    /** Optional section or heading */
    private String section;
}
