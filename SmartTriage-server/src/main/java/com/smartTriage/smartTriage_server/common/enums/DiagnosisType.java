package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Diagnosis classification type — provisional vs. confirmed.
 */
@Getter
@RequiredArgsConstructor
public enum DiagnosisType {

    PROVISIONAL("Provisional Diagnosis"),
    CONFIRMED("Confirmed Diagnosis"),
    DIFFERENTIAL("Differential Diagnosis"),
    WORKING("Working Diagnosis");

    private final String description;
}
