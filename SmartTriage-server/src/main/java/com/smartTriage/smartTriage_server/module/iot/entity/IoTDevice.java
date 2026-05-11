package com.smartTriage.smartTriage_server.module.iot.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
import com.smartTriage.smartTriage_server.common.enums.DeviceType;
import com.smartTriage.smartTriage_server.module.bed.entity.Bed;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * IoTDevice — registry of all ESP32 and other IoT monitoring devices.
 *
 * Each device is registered to a specific hospital and has a unique hardware
 * serial number. Devices authenticate using a pre-shared API key that is
 * provisioned during device onboarding.
 *
 * A device can be linked to exactly one patient visit at a time via
 * DeviceSession. When a monitoring session ends, the device becomes
 * available for assignment to another patient.
 *
 * Lifecycle:
 *   REGISTERED → ONLINE → MONITORING → ONLINE → OFFLINE → DECOMMISSIONED
 */
@Entity
@Table(name = "iot_devices", indexes = {
        @Index(name = "idx_iot_device_serial", columnList = "serial_number"),
        @Index(name = "idx_iot_device_hospital", columnList = "hospital_id"),
        @Index(name = "idx_iot_device_status", columnList = "status"),
        @Index(name = "idx_iot_device_active", columnList = "is_active")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_iot_device_serial", columnNames = "serial_number"),
        @UniqueConstraint(name = "uk_iot_device_api_key", columnNames = "api_key")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IoTDevice extends BaseEntity {

    /** Manufacturer serial number — unique per physical device */
    @Column(name = "serial_number", nullable = false, length = 100)
    private String serialNumber;

    /** Human-readable device name (e.g., "ESP32-Monitor-ED-Bay-3") */
    @Column(name = "device_name", nullable = false, length = 100)
    private String deviceName;

    /** Device type */
    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, length = 30)
    private DeviceType deviceType;

    /** Hospital this device belongs to */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    /** Pre-shared API key for device authentication */
    @Column(name = "api_key", nullable = false, length = 255)
    private String apiKey;

    /** Current device status */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private DeviceStatus status = DeviceStatus.REGISTERED;

    /** Firmware version running on the device */
    @Column(name = "firmware_version", length = 30)
    private String firmwareVersion;

    /** Last time the device sent a heartbeat */
    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    /** Last time the device sent valid vital data */
    @Column(name = "last_data_at")
    private Instant lastDataAt;

    /** Battery level percentage (0-100, null if wired) */
    @Column(name = "battery_level")
    private Integer batteryLevel;

    /** WiFi signal strength (RSSI in dBm) */
    @Column(name = "wifi_rssi")
    private Integer wifiRssi;

    /** IP address of the device on the hospital network */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /** MAC address for network identification */
    @Column(name = "mac_address", length = 17)
    private String macAddress;

    /**
     * Free-text physical location (e.g., "ED Bay 3", "Resus Room 1").
     * Retained for historical data; new registrations no longer ask
     * for it — the device's assigned bed (and the bed's zone) provide
     * the canonical location signal.
     */
    @Column(name = "location", length = 100)
    private String location;

    /**
     * Admin-controlled inventory flag (V53). TRUE means the device is
     * part of the active monitor pool and can be assigned to a visit.
     * FALSE means the admin has taken it out of service (defective,
     * repair, maintenance). Independent of the runtime DeviceStatus —
     * an OFFLINE device may still be "in service" from inventory's
     * point of view if the admin expects it to come back.
     */
    @Column(name = "in_service", nullable = false)
    @Builder.Default
    private boolean inService = true;

    /**
     * Admin-controlled triage-zone flag (V54). TRUE means this physical
     * device sits in the triage station and is allowed to feed the
     * "Pull from Monitor" flow inside the triage form. The flag is
     * orthogonal to assignedBed — a triage-zone monitor is shared across
     * incoming patients, not pinned to a single bed.
     */
    @Column(name = "triage_monitor", nullable = false)
    @Builder.Default
    private boolean triageMonitor = false;

    /**
     * The bed this device is permanently assigned to (null = portable /
     * unassigned). When a patient is placed in this bed, BedService
     * auto-creates a DeviceSession so vitals flow without a manual nurse
     * pairing step. Clearing this field (set to null) reverts the device
     * to portable / manual-pairing behaviour. A partial unique index
     * (uk_device_one_bed) prevents two devices from claiming the same bed.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_bed_id")
    private Bed assignedBed;

    /** Configuration timeout: seconds after which device is considered disconnected */
    @Column(name = "heartbeat_timeout_seconds", nullable = false)
    @Builder.Default
    private int heartbeatTimeoutSeconds = 30;

    /** How frequently (in seconds) the device sends vital data */
    @Column(name = "data_interval_seconds", nullable = false)
    @Builder.Default
    private int dataIntervalSeconds = 5;

    /** Administrative notes about this device */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
