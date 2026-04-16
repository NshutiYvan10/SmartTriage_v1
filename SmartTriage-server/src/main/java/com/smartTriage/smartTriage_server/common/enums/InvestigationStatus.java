package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Status of a clinical investigation (lab, imaging, etc.).
 */
@Getter
@RequiredArgsConstructor
public enum InvestigationStatus {

    ORDERED("Ordered"),
    SPECIMEN_COLLECTED("Specimen Collected"),
    IN_PROGRESS("In Progress"),
    RESULTED("Resulted"),
    CANCELLED("Cancelled");

    private final String description;
}
