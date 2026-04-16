package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Lab order priority levels with target turnaround times.
 */
@Getter
@RequiredArgsConstructor
public enum LabPriority {
    STAT("Results needed within 30 minutes", 30),
    URGENT("Results needed within 2 hours", 120),
    ROUTINE("Results needed within 24 hours", 1440);

    private final String description;
    private final int targetMinutes;
}
