package com.smartTriage.smartTriage_server.module.iot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response sent back to the device after each vital payload ingestion.
 * Contains acknowledgment and optional commands for the device.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceAckResponse {

    /** Whether the payload was accepted */
    private boolean accepted;

    /** Server-assigned ID for the reading (for device-side correlation) */
    private String readingId;

    /** Reason for rejection (if not accepted) */
    private String rejectionReason;

    /** Server's current timestamp (for device clock sync) */
    private long serverTimestamp;

    /** Requested data interval change (seconds, null = no change) */
    private Integer requestedIntervalSeconds;

    /** Command for the device (e.g., "INCREASE_RATE", "RESET", "SHUTDOWN") */
    private String command;
}
