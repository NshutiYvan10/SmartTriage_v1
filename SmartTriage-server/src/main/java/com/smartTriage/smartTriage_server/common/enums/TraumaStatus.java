package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Whether the patient presentation is trauma-related.
 */
@Getter
@RequiredArgsConstructor
public enum TraumaStatus {

    NO_TRAUMA("No Trauma", 0),
    TRAUMA("Trauma", 1);

    private final String description;
    private final int tewsPoints;
}
