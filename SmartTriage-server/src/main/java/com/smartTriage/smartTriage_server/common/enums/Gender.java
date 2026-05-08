package com.smartTriage.smartTriage_server.common.enums;

public enum Gender {
    MALE,
    FEMALE,
    /** Sentinel for placeholder / unidentified patients (Direct Resus,
     *  EMS unknown arrivals). NOT exposed in any registration UI. */
    UNKNOWN
}
