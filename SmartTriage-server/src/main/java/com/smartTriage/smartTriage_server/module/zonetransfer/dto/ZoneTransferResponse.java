package com.smartTriage.smartTriage_server.module.zonetransfer.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.zonetransfer.entity.ZoneTransferStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZoneTransferResponse {

    private UUID id;
    private UUID visitId;
    private String visitNumber;
    private String patientName;
    private boolean isPediatric;

    private EdZone fromZone;
    /**
     * Human bed label the patient is physically in at the time of the
     * (pending) transfer — denormalised from {@code visit.currentBed.code}
     * so a charge nurse can locate an overdue-transfer patient without a
     * second lookup. Null when the patient hasn't been placed in a bed.
     */
    private String fromBedCode;
    private EdZone toZone;
    private ZoneTransferStatus status;
    private String reason;

    private Instant initiatedAt;
    private UUID initiatedById;
    private String initiatedByName;
    private UUID proposedClinicianId;
    private String proposedClinicianName;

    private Instant acceptedAt;
    private UUID acceptedById;
    private String acceptedByName;

    private Instant declinedAt;
    private UUID declinedById;
    private String declinedByName;
    private String declinedReason;

    private String handoverNote;
    private UUID triggeringAlertId;
    private UUID triggeringSignEventId;

    private Instant createdAt;
}
