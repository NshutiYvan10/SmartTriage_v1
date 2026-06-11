package com.smartTriage.smartTriage_server.module.medication.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prescription modification (V67). Orders are never edited in place —
 * the old order is DISCONTINUED ("Modified: &lt;reason&gt;") and a new
 * one is created, linked via supersedes/superseded-by. The chain IS
 * the modification history: who changed what, when, and why, with the
 * full dose trail of every version preserved.
 *
 * <p>The replacement goes through the full prescribe path, so every
 * safety check (allergy block, high-alert approval gate, …) re-runs
 * against the new parameters.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModifyOrderRequest {

    @NotBlank(message = "A modification reason is required")
    @Size(max = 500)
    private String reason;

    /** The replacement order. Must target the same visit as the original. */
    @NotNull(message = "The replacement order is required")
    @Valid
    private PrescribeMedicationRequest newOrder;
}
