package com.smartTriage.smartTriage_server.module.documentation.dto;

import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Request to create a new clinical document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateDocumentRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @NotNull(message = "Document type is required")
    private ClinicalDocumentType documentType;

    @NotBlank(message = "Title is required")
    private String title;

    @NotBlank(message = "Content is required")
    private String content;

    // Author identity (name/role/license) is NOT accepted from the client — it is
    // derived server-side from the authenticated user. Any value sent is ignored.

    private String templateUsed;
    private String notes;

    // Type-specific structured fields. Populate for PROCEDURE_NOTE / OPERATIVE_NOTE
    // (procedure*) or DEATH_CERTIFICATE (*death*); ignored for other types.
    private String procedurePerformed;
    private String procedureIndication;
    private String procedureFindings;
    private String procedureComplications;
    private String procedureOutcome;
    private String procedurePerformedBy;
    private String anaesthesiaType;

    private Instant timeOfDeath;
    private String causeOfDeath;
    private String antecedentCauses;
    private String mannerOfDeath;
}
