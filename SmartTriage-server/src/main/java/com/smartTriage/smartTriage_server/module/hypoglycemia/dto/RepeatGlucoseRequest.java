package com.smartTriage.smartTriage_server.module.hypoglycemia.dto;

import com.smartTriage.smartTriage_server.common.enums.GlucoseUnit;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for recording a repeat glucose check.
 *
 * The value carries its {@link #unit} (default mmol/L) so a reading from a
 * mg/dL glucometer is CONVERTED rather than misclassified. The raw bound is
 * deliberately wide enough to admit either scale; the service converts to
 * mmol/L and then applies a physiologic-plausibility check. Critically, the
 * service never <i>silently auto-resolves</i> an event on an implausibly-high
 * repeat (a likely unit/data error) — it keeps the event open for an explicit
 * clinician resolve, so a still-hypoglycemic patient entered in the wrong unit
 * is never marked "recovered" by accident.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RepeatGlucoseRequest {

    /** Glucose value in {@link #unit}. Converted to mmol/L by the service. */
    @NotNull(message = "Glucose level is required")
    @DecimalMin(value = "0.3", message = "Glucose reading is too low to be valid")
    @DecimalMax(value = "999.0", message = "Glucose reading is out of range")
    private Double glucoseLevel;

    /** Unit the value was entered in. Defaults to mmol/L when omitted. */
    @Builder.Default
    private GlucoseUnit unit = GlucoseUnit.MMOL_L;
}
