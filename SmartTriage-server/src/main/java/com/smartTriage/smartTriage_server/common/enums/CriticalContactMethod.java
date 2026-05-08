package com.smartTriage.smartTriage_server.common.enums;

/**
 * How the lab notified the ordering clinician about a critical value.
 * JCI NPSG.02.03.01 requires the method be documented alongside a
 * read-back of the value.
 */
public enum CriticalContactMethod {
    PHONE,
    IN_PERSON,
    IN_APP
}
