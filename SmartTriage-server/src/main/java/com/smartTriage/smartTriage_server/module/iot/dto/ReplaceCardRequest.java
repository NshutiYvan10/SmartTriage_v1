package com.smartTriage.smartTriage_server.module.iot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Replace a patient's RFID card (V95) — the lost/damaged-card workflow. The new card is set on the
 * shared cross-hospital identity, so the OLD card immediately stops resolving anywhere. Reassigning
 * a card already held by another patient is rejected; the change is audited (old → new).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplaceCardRequest {
    @NotNull
    private UUID patientId;
    @NotBlank
    private String newCardId;
}
