package com.smartTriage.smartTriage_server.module.iot.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A paramedic's self-registration of their OWN field monitor (V98).
 *
 * <p>Deliberately NOT {@code RegisterDeviceRequest}: the paramedic supplies only
 * the serial + a friendly name (and optionally MAC / notes). The device type is
 * forced to PARAMEDIC_MONITOR and the hospital is taken from the caller — both
 * server-side — so this request must NOT require (or accept) them, unlike the
 * admin register path which @NotNull-requires deviceType + hospitalId.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfRegisterMonitorRequest {

    @NotBlank(message = "Serial number is required")
    private String serialNumber;

    @NotBlank(message = "Device name is required")
    private String deviceName;

    private String macAddress;
    private String notes;
}
