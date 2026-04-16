package com.smartTriage.smartTriage_server.module.bed.dto;

import com.smartTriage.smartTriage_server.common.enums.BedStatus;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a Bed. Includes occupant summary and assigned-device
 * summary so the bed-grid UI can render a tile without extra round-trips.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BedResponse {

    private UUID id;
    private UUID hospitalId;
    private EdZone zone;
    private String code;
    private String label;
    private BedStatus status;
    private boolean hasMonitor;
    private int displayOrder;
    private String notes;

    // ── Occupant (null if AVAILABLE / CLEANING / OUT_OF_SERVICE) ──
    private UUID currentVisitId;
    private String currentVisitNumber;
    private String currentPatientName;
    private String currentTriageCategory; // enum name as string (RED/ORANGE/…)
    private Integer currentTewsScore;
    private Instant currentPlacedAt;      // when the visit was placed in this bed

    // ── Assigned monitor (null if portable / unassigned) ──
    private UUID assignedDeviceId;
    private String assignedDeviceName;
    private String assignedDeviceStatus;  // DeviceStatus enum name

    // ── Active monitoring session (if any) ──
    private UUID activeSessionId;

    private Instant createdAt;
    private Instant updatedAt;
}
