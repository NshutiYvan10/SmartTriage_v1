package com.smartTriage.smartTriage_server.module.medication.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Charge-nurse approval of a high-alert order (V67 approval gate).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApproveOrderRequest {

    /** Display-name fallback for the approver. */
    @Size(max = 255)
    private String approvedByName;

    @Size(max = 500)
    private String note;
}
