package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Patient mobility classification — Rwanda Standard Adult Triage Form.
 *
 * Per national standard TEWS grid:
 *   Walking                = 0 points
 *   With Help / Wheelchair = 1 point
 *   Stretcher / Immobile   = 2 points
 */
@Getter
@RequiredArgsConstructor
public enum MobilityStatus {

    WALKING("Walking", 0),
    WITH_HELP("With Help/Wheelchair", 1),
    STRETCHER("Stretcher/Immobile", 2);

    private final String description;
    private final int tewsPoints;
}
