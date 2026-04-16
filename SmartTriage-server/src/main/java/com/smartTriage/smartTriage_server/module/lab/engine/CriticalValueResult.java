package com.smartTriage.smartTriage_server.module.lab.engine;

import com.smartTriage.smartTriage_server.common.enums.CriticalValueType;

/**
 * Result of a critical value evaluation for a lab result.
 *
 * @param isCritical       whether the value is critical
 * @param criticalValueType the type of critical value (null if not critical)
 * @param description      human-readable description of the critical finding
 */
public record CriticalValueResult(
        boolean isCritical,
        CriticalValueType criticalValueType,
        String description
) {

    public static CriticalValueResult normal() {
        return new CriticalValueResult(false, null, null);
    }

    public static CriticalValueResult critical(CriticalValueType type, String description) {
        return new CriticalValueResult(true, type, description);
    }
}
