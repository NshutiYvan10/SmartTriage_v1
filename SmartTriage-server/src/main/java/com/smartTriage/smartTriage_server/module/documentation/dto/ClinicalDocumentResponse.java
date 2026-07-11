package com.smartTriage.smartTriage_server.module.documentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
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

    // Legal compliance — author/signer identity is server-derived from the
    // authenticated user. authorUserId is the verifiable link; name/role/license
    // are the snapshot recorded at signing time.
    private UUID authorUserId;
    private String authorName;
    private String authorRole;
    private String authorLicenseNumber;
    private Instant signedAt;
    // Pin the wire name on the GETTER (not the field): Lombok's isSigned() getter would
    // otherwise serialize as "signed", and annotating the field instead emits BOTH keys.
    // The frontend reads `isSigned` — the whole sign/co-sign UI depends on this.
    @Getter(onMethod_ = {@JsonProperty("isSigned")})
    private boolean isSigned;
    private UUID coSignedByUserId;
    private String coSignedByName;
    private String coSignedByRole;
    private String coSignedByLicenseNumber;
    private Instant coSignedAt;

    // Vitals snapshot
    private UUID vitalSignsId;

    // Amendment tracking
    @Getter(onMethod_ = {@JsonProperty("isAmendment")})
    private boolean isAmendment;
    private String amendmentReason;
    private UUID originalDocumentId;
    private Instant amendedAt;

    // Template
    private String templateUsed;
    private String notes;

    // Type-specific structured fields (procedure / operative / death)
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

    // Audit
    private Instant createdAt;
    private Instant updatedAt;
}
