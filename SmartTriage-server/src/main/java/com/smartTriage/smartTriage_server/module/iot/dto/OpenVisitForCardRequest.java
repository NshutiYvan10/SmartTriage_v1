package com.smartTriage.smartTriage_server.module.iot.dto;

import com.smartTriage.smartTriage_server.common.enums.ArrivalMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Registrar confirms an RFID-found patient and opens a fresh visit at THIS hospital (V95). If the
 * person has no local record at this hospital yet (first visit here, identity known cross-hospital),
 * one is created from the shared identity's demographics and linked — never re-registered blank.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenVisitForCardRequest {
    @NotBlank
    private String cardId;
    @NotNull
    private UUID hospitalId;
    private ArrivalMode arrivalMode;
    private String chiefComplaint;
}
