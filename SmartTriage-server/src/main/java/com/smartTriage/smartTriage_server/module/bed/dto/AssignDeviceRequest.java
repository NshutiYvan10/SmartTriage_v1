package com.smartTriage.smartTriage_server.module.bed.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Assign an IoTDevice to this bed permanently, so patient placement auto-
 * creates a monitoring session. Passing a null deviceId detaches whatever
 * device was previously assigned (the device becomes portable again).
 *
 * Admin-only action.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignDeviceRequest {

    /** Device to assign. Null / omitted → detach any currently-assigned device. */
    private UUID deviceId;
}
