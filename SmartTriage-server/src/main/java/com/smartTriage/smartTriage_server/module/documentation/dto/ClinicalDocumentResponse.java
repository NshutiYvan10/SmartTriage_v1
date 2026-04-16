package com.smartTriage.smartTriage_server.module.documentation.dto;

import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Full response DTO for a clinical document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicalDocumentResponse {

    private UUID id;
    private UUID visitId;
    private String visitNumber;

    // Document content
    private ClinicalDocumentType documentType;
    private String title;
    private String content;

    // Legal compliance
    private String authorName;
    private String authorRole;
    private String authorLicenseNumber;
    private Instant signedAt;
    private boolean isSigned;
    private String coSignedByName;
    private Instant coSignedAt;

    // Vitals snapshot
    private UUID vitalSignsId;

    // Amendment tracking
    private boolean isAmendment;
    private String amendmentReason;
    private UUID originalDocumentId;
    private Instant amendedAt;

    // Template
    private String templateUsed;
    private String notes;

    // Audit
    private Instant createdAt;
    private Instant updatedAt;
}
