package com.smartTriage.smartTriage_server.module.consent.dto;

import com.smartTriage.smartTriage_server.common.enums.ConsentGrantor;
import com.smartTriage.smartTriage_server.common.enums.DataSharingConsentStatus;
import com.smartTriage.smartTriage_server.common.enums.DataSharingScope;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to record a cross-hospital data-sharing consent decision. The obtaining clinician is
 * NEVER taken from the request — it is the authenticated principal, snapshotted server-side.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordDataSharingConsentRequest {

    /** GRANTED (opt-in) or DENIED. Recording as WITHDRAWN is rejected — use the withdraw endpoint. */
    private DataSharingConsentStatus status;

    private DataSharingScope scope;

    @NotNull(message = "Consent grantor is required")
    private ConsentGrantor consentGrantor;

    private String grantorName;
    private String grantorRelationship;
    private String notes;
}
