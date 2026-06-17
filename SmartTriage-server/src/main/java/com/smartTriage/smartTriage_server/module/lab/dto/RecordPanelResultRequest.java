package com.smartTriage.smartTriage_server.module.lab.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request to record a multi-analyte (panel) result — one row per analyte plus the
 * shared result metadata (who entered it, notes, specimen-quality flag). The order's
 * isCritical/isAbnormal roll up from the per-component evaluations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordPanelResultRequest {

    @NotEmpty(message = "At least one analyte result is required")
    @Valid
    private List<RecordComponentResultRequest> components;

    /** Tech who entered (and self-verified) the result. */
    private String enteredByName;

    /** True if the tech flagged the specimen as quality-suspect at result time. */
    private boolean specimenQualityConcern;

    private String notes;
}
