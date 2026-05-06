package com.smartTriage.smartTriage_server.module.clinicalsigns.dto;

import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * One sign update inside a recording action. Used both as the body of a
 * single-sign POST and as an entry inside a batch update — a single ward
 * round commonly touches several signs at the same recorded time, and
 * sending them as one batch keeps them on the same timestamp without the
 * client having to coordinate.
 *
 * sign_category is intentionally NOT in the request — it's derived from
 * the sign_code on the server using the canonical sign-code → category
 * mapping. Letting clients supply category opens the door to mismatches
 * where a code lands in the wrong group.
 */
@Data
public class RecordClinicalSignRequest {

    @NotBlank(message = "signCode is required")
    private String signCode;

    @NotNull(message = "status is required")
    private ClinicalSignStatus status;

    /** Optional, only for glucose-carrying signs (convulsions/coma/DKA). */
    private Double numericValue;

    private String notes;
}
