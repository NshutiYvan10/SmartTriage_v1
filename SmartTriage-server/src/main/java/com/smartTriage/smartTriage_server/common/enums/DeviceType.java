package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Types of IoT monitoring devices supported by SmartTriage.
 */
@Getter
@RequiredArgsConstructor
public enum DeviceType {

    /** ESP32-based multi-parameter patient monitor (primary SmartTriage device) */
    ESP32_MONITOR("ESP32 Multi-Parameter Monitor"),

    /** Standalone pulse oximeter (SpO2 + HR) */
    PULSE_OXIMETER("Pulse Oximeter"),

    /** Standalone ECG monitor */
    ECG_MONITOR("ECG Monitor"),

    /** Blood pressure monitor */
    BP_MONITOR("Blood Pressure Monitor"),

    /** Temperature probe / thermometer */
    TEMPERATURE_PROBE("Temperature Probe"),

    /** Blood glucose meter */
    GLUCOMETER("Glucometer"),

    /** Ambulance monitor (pre-hospital) */
    AMBULANCE_MONITOR("Ambulance Monitor"),

    /** Generic / other device */
    OTHER("Other Device");

    private final String description;
}
