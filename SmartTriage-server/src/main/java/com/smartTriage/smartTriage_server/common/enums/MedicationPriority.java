package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Structured urgency tier for a medication order. Drives:
 *
 * <ul>
 *   <li>the nurse medication queue's sort + visual treatment,</li>
 *   <li>the STAT / URGENT SLA monitor that escalates overdue
 *       administrations,</li>
 *   <li>real-time push prioritisation (STAT meds toast on arrival
 *       even when the nurse is on another patient's chart).</li>
 * </ul>
 *
 * <p>Replaces the free-text {@code frequency} string as the source of
 * truth for "is this STAT?" — the old approach forced every consumer
 * to substring-match on user-entered text, which is fine for display
 * but unsafe for SLAs.
 */
@Getter
@RequiredArgsConstructor
public enum MedicationPriority {

    STAT("STAT", "Give immediately — within 10 minutes", 10),
    URGENT("Urgent", "Give within 30 minutes", 30),
    ROUTINE("Routine", "Give per scheduled frequency", 240);

    private final String label;
    private final String description;
    /** Administration SLA in minutes from prescribedAt. */
    private final int slaMinutes;
}
