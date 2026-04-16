package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * mSAT Triage Categories — core clinical classification.
 * RED = Immediate / Resuscitation (0 min)
 * ORANGE = Very Urgent (10 min)
 * YELLOW = Urgent (30 min)
 * GREEN = Routine (60 min)
 * BLUE = Dead on Arrival
 */
@Getter
@RequiredArgsConstructor
public enum TriageCategory {

    RED("Immediate", 0, 4),
    ORANGE("Very Urgent", 10, 3),
    YELLOW("Urgent", 30, 2),
    GREEN("Routine", 60, 1),
    BLUE("Dead on Arrival", -1, 0);

    private final String description;
    private final int maxWaitMinutes;
    private final int severity;
}
