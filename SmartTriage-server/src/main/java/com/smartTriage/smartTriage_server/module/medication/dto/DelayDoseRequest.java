package com.smartTriage.smartTriage_server.module.medication.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nurse delays a DUE dose (V67). The dose stays DUE with its due time
 * pushed forward and the reason appended to the audit trail, so the
 * overdue/missed monitoring keeps watching it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DelayDoseRequest {

    /** Minutes to push the due time forward (15 min – 12 h). */
    @NotNull(message = "Delay minutes is required")
    @Min(value = 15, message = "Minimum delay is 15 minutes")
    @Max(value = 720, message = "Maximum delay is 12 hours — discontinue or hold the order instead")
    private Integer delayMinutes;

    @NotBlank(message = "A delay reason is required")
    @Size(max = 500)
    private String reason;
}
