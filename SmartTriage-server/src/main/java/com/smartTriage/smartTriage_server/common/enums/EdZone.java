package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Emergency Department functional zones — per KFH ED structure.
 * Triage category maps directly to zone:
 * RED → RESUS
 * ORANGE → ACUTE
 * YELLOW → GENERAL
 * GREEN → GENERAL
 */
@Getter
@RequiredArgsConstructor
public enum EdZone {
    RESUS("Resuscitation", "RED patients — immediate life-saving interventions"),
    ACUTE("Acute Treatment", "ORANGE patients — urgent, must be seen within 10 minutes"),
    GENERAL("General / Sub-Acute", "YELLOW & GREEN patients — assessment and treatment"),
    TRIAGE("Triage Station", "Triage nurse station — initial assessment"),
    OBSERVATION("Observation Unit", "Short-stay monitoring post-treatment"),
    ISOLATION("Isolation", "Infectious disease isolation area"),
    PEDIATRIC("Pediatric", "Dedicated pediatric treatment area");

    private final String label;
    private final String description;

    /** Map triage category to the zone the patient should be routed to. */
    public static EdZone fromTriageCategory(TriageCategory category) {
        return switch (category) {
            case RED -> RESUS;
            case ORANGE -> ACUTE;
            case YELLOW, GREEN -> GENERAL;
            case BLUE -> GENERAL; // DOA — handled separately
        };
    }
}
