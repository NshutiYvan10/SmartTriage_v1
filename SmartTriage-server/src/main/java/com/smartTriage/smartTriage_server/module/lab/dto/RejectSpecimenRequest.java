package com.smartTriage.smartTriage_server.module.lab.dto;

import com.smartTriage.smartTriage_server.common.enums.SpecimenRejectionReason;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reject a specimen on receipt (haemolysed / clotted / mislabelled).
 * Closing this loop tells the ordering doctor and triggers a re-draw.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RejectSpecimenRequest {

    @NotNull(message = "Rejection reason is required")
    private SpecimenRejectionReason reason;

    /** Free-text detail (which tube, why mislabelled, etc.). */
    private String notes;

    private String rejectedByName;
}
