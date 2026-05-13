package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.AllergySeverity;
import com.smartTriage.smartTriage_server.common.enums.AllergyVerificationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request to record a new structured allergy for a patient.
 *
 * <p>The frontend allergen-picker emits either:
 * <ul>
 *   <li>{@code allergenFormularyId} when the clinician picked from
 *       the drug catalog (preferred — gives the safety engine a
 *       reliable FK match), or</li>
 *   <li>{@code allergenName} only when the allergen is free-text
 *       (non-drug like shellfish/latex, or a drug not yet in the
 *       catalog).</li>
 * </ul>
 * Either way {@code allergenName} is always present so the display
 * label is stored verbatim.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordAllergyRequest {

    @NotBlank(message = "Allergen name is required")
    @Size(max = 200, message = "Allergen name must not exceed 200 characters")
    private String allergenName;

    /** Optional FK — when picked from the drug catalog. */
    private UUID allergenFormularyId;

    @NotNull(message = "Severity is required")
    private AllergySeverity severity;

    @Size(max = 500, message = "Reaction description must not exceed 500 characters")
    private String reaction;

    private LocalDate onsetDate;

    /** Defaults to PATIENT_REPORTED if omitted. */
    private AllergyVerificationStatus verificationStatus;

    /** Display name of the clinician recording the allergy. */
    @Size(max = 200)
    private String recordedByName;
}
