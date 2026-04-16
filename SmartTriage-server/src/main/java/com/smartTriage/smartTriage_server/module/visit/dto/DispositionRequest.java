package com.smartTriage.smartTriage_server.module.visit.dto;

import com.smartTriage.smartTriage_server.common.enums.DispositionType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to record a patient's final ED disposition.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispositionRequest {

    @NotNull(message = "Disposition type is required")
    private DispositionType dispositionType;

    /** Optional clinical notes about the disposition decision */
    private String notes;

    /** For ADMITTED_TO_WARD: the destination ward name */
    private String destinationWard;

    /** For TRANSFERRED: the receiving facility name */
    private String receivingFacility;
}
