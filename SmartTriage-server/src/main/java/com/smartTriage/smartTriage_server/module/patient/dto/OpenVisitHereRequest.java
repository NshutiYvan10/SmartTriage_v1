package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.ArrivalMode;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Registrar "open a visit here" for a patient found in the global registry — the
 * manual-search twin of {@code OpenVisitForCardRequest} (the RFID path). The patient
 * is identified by the path variable; this body carries the target hospital + arrival
 * context for the fresh visit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenVisitHereRequest {

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    private ArrivalMode arrivalMode;
    private String chiefComplaint;
}
