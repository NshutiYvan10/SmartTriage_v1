package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Status of an IoT monitoring device in the SmartTriage system.
 */
@Getter
@RequiredArgsConstructor
public enum DeviceStatus {

    /** Device registered but never connected */
    REGISTERED("Registered"),

    /** Device is online and actively streaming */
    ONLINE("Online"),

    /** Device was online but stopped sending heartbeats */
    OFFLINE("Offline"),

    /** Device connected to a patient visit and actively monitoring */
    MONITORING("Monitoring"),

    /** Device encountered an error (sensor failure, low battery) */
    ERROR("Error"),

    /** Device decommissioned / retired */
    DECOMMISSIONED("Decommissioned");

    private final String description;
}
