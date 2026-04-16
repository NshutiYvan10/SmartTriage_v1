package com.smartTriage.smartTriage_server.common.enums;

/**
 * Shift periods — 2-shift model:
 * DAY: 07:00 – 19:00
 * NIGHT: 19:00 – 07:00
 * When the clock turns to the next shift period, assignments
 * from the previous period are no longer considered "current".
 */
public enum ShiftPeriod {
    DAY, // 07:00 – 19:00
    NIGHT // 19:00 – 07:00
}
