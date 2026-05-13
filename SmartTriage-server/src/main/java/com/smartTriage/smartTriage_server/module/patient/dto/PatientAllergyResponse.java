package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.AllergySeverity;
import com.smartTriage.smartTriage_server.common.enums.AllergyVerificationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read DTO for a recorded patient allergy. Surfaces the structured
 * fields the prescribe-time safety dialog and the patient profile
 * panel need; never carries the JPA proxy.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientAllergyResponse {

    private UUID id;
    private UUID patientId;

    /** FK to the drug formulary entry — null for free-text allergens. */
    private UUID allergenFormularyId;

    /** Display name (always present). */
    private String allergenName;

    private AllergySeverity severity;
    /** Human-friendly severity label, e.g. "Anaphylaxis". */
    private String severityLabel;

    private String reaction;
    private LocalDate onsetDate;

    private AllergyVerificationStatus verificationStatus;
    private String verificationStatusLabel;

    private String recordedByName;
    private Instant recordedAt;

    private String refutedByName;
    private Instant refutedAt;
    private String refuteReason;
}
