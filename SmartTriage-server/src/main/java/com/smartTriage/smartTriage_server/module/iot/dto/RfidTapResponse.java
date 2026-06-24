package com.smartTriage.smartTriage_server.module.iot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The reader's synchronous response to a tap (V95) — drives the device's screen + buzzer:
 * <ul>
 *   <li>{@code FOUND} — patient identified system-wide; show {@code patientName}, positive tone.</li>
 *   <li>{@code NOT_FOUND} — no patient for this card; "register manually", distinct tone.</li>
 *   <li>{@code CARD_CAPTURED} — reader was in registration bind mode; UID captured, positive tone.</li>
 * </ul>
 * (A device that cannot reach the server renders its own offline state — no response is produced.)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RfidTapResponse {
    private String result;       // FOUND | NOT_FOUND | CARD_CAPTURED
    private String patientName;  // FOUND only
    private String dateOfBirth;  // FOUND only (ISO date or null)
    private String gender;       // FOUND only
}
