package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.PregnancyStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for the structured pregnancy-status update endpoint
 * (Phase 13b — feeds the teratogen safety check at prescribe time).
 *
 * Why a dedicated request type instead of folding into a generic
 * patient-update endpoint: the column has clinical-safety
 * implications (a wrong PREGNANT → NOT_PREGNANT change suppresses
 * teratogen warnings) and warrants its own audit-traceable surface.
 * Keeping it separate also keeps role-gating tight — only nurses
 * and clinicians should touch this, not registrars.
 *
 * `pregnancyStatus` is required. To clear a previously-set value,
 * pass `UNKNOWN` rather than null — that way the column is always
 * non-null after the first write, which makes the structured-first
 * read path (PregnancyStatus → suppress | fire | fall through) easier
 * to reason about. The `pregnancy_status_recorded_at` audit timestamp
 * is set by the service, not the client.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePregnancyStatusRequest {

    @NotNull(message = "pregnancyStatus is required (use UNKNOWN to clear)")
    private PregnancyStatus pregnancyStatus;
}
