package com.smartTriage.smartTriage_server.module.medication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Patient refused a specific dose (V67). The dose is recorded REFUSED
 * with the reason; the ORDER stays live — the patient may accept the
 * next dose, and the schedule rolls forward.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefuseDoseRequest {

    @NotBlank(message = "A refusal reason is required")
    @Size(max = 500)
    private String reason;

    /** Display-name fallback for the recording nurse. */
    @Size(max = 255)
    private String recordedByName;
}
