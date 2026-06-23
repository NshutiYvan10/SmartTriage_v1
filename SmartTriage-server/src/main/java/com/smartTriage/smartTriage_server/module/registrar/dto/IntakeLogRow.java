package com.smartTriage.smartTriage_server.module.registrar.dto;

import lombok.Builder;

import java.time.Instant;

/**
 * One registration in the registrar intake log (R11). An admissions record keyed on arrival time —
 * NOT a per-registrar attribution (Visit has no registrar-actor FK; only timestamps are stored).
 */
@Builder
public record IntakeLogRow(
        String visitNumber,
        Instant arrivalTime,
        String arrivalMode,
        String status,
        String patientName,
        Integer ageYears,
        String sex,
        String zone,
        boolean unidentified) {
}
