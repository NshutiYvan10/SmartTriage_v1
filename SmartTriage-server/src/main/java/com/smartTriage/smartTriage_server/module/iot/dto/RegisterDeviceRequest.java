package com.smartTriage.smartTriage_server.module.iot.dto;

import com.smartTriage.smartTriage_server.common.enums.DeviceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to register a new IoT device in the system.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterDeviceRequest {

    @NotBlank(message = "Serial number is required")
    private String serialNumber;

    @NotBlank(message = "Device name is required")
    private String deviceName;

    @NotNull(message = "Device type is required")
    private DeviceType deviceType;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    private String firmwareVersion;
    private String macAddress;
    private String location;
    private Integer heartbeatTimeoutSeconds;
    private Integer dataIntervalSeconds;
    private String notes;
}
