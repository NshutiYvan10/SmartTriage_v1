package com.smartTriage.smartTriage_server.module.consent.dto;

import com.smartTriage.smartTriage_server.common.enums.ConsentGrantor;
import com.smartTriage.smartTriage_server.common.enums.ConsentStatus;
import com.smartTriage.smartTriage_server.common.enums.ConsentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to record an informed-consent event. The OBTAINING clinician is NOT in
 * this payload — it is derived server-side from the authenticated user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordConsentRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @NotNull(message = "Consent type is required")
    private ConsentType consentType;

    @NotBlank(message = "Procedure / intervention name is required")
    private String procedureName;

    private String description;

    // Disclosure
    private String risksExplained;
    private String benefitsExplained;
    private String alternativesExplained;
    private boolean questionsAnswered;
    private boolean interpreterUsed;
    private String interpreterName;
    private String language;

    // Who consented
    @NotNull(message = "Consent grantor is required (patient or proxy)")
    private ConsentGrantor consentGrantor;
    private String grantorName;
    private String grantorRelationship;
    private String witnessName;

    /** GIVEN or REFUSED at record time. Defaults to GIVEN when omitted. */
    private ConsentStatus status;

    private String notes;
}
