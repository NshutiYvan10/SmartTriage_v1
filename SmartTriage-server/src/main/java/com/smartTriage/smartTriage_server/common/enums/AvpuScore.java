package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * AVPU consciousness scale — Rwanda National Standard Adult Triage Form.
 *
 * Per the standard triage form, the TEWS scoring is:
 *   CONFUSED           = 1 point
 *   ALERT              = 0 points (baseline)
 *   REACTS_TO_VOICE    = 1 point
 *   REACTS_TO_PAIN     = 2 points
 *   UNRESPONSIVE       = 3 points
 *
 * Note: CONFUSED is an addition to the standard AVPU scale, specific to the
 * South African / Rwanda mSAT protocol. It sits between Alert and Voice response
 * on the scoring grid, both scoring 1 point but with different clinical meaning.
 */
@Getter
@RequiredArgsConstructor
public enum AvpuScore {

    ALERT("Alert", 0),
    CONFUSED("Confused", 1),
    VERBAL("Reacts to Voice", 1),
    PAIN("Reacts to Pain", 2),
    UNRESPONSIVE("Unresponsive", 3);

    private final String description;
    private final int tewsPoints;
}
