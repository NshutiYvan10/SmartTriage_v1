package com.smartTriage.smartTriage_server.module.documentation.dto;

import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @NotBlank(message = "Author name is required")
    private String authorName;

    private String authorRole;
    private String authorLicenseNumber;
    private String templateUsed;
    private String notes;
}
