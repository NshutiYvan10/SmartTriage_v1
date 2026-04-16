package com.smartTriage.smartTriage_server.common.enums;

/**
 * Transport modes for inter-hospital patient transfers in Rwanda.
 */
public enum TransportMode {
    AMBULANCE_SAMU,      // Rwanda SAMU ambulance service
    HOSPITAL_AMBULANCE,
    PRIVATE_VEHICLE,
    HELICOPTER,          // For remote areas
    OTHER
}
