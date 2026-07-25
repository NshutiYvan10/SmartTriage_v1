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

    /** ESP32 + RFID reader at the registration desk — tap-to-identify (V95) */
    RFID_READER("RFID Card Reader"),

    /** Ward gateway appliance (Raspberry Pi): the hospital-owned hub that
     *  fronts the bedside monitors and the demo console. Registered by the
     *  HOSPITAL_ADMIN like any device, with its own API key — which is the
     *  ONLY credential the gateway uses against the backend (it never holds
     *  staff credentials). Its key unlocks exactly one extra read:
     *  the hospital device registry (sans keys) via
     *  GET /iot/stream/hospital-registry. */
    GATEWAY("Ward Gateway"),

    /** A paramedic's own portable field monitor, self-registered and owned by
     *  the crew member (user-owned / hospital-agnostic — works at any
     *  destination hospital). Feeds the paramedic's "pull from my monitor"
     *  field-vitals snapshot. */
    PARAMEDIC_MONITOR("Paramedic Field Monitor"),

    /** Generic / other device */
    OTHER("Other Device");

    private final String description;
}
