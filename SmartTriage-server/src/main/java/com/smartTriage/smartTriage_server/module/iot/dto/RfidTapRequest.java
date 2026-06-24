package com.smartTriage.smartTriage_server.module.iot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload an ESP32 RFID reader POSTs to {@code /api/v1/iot/rfid/tap} on every card tap (V95).
 * Device authenticates via the {@code X-Device-API-Key} header (not JWT), like the vital monitors.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RfidTapRequest {
    /** The card's factory UID as read by the reader. */
    private String cardId;
    /** Device-side capture timestamp (ISO-8601), informational. */
    private String tappedAt;
}
