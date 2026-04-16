package com.smartTriage.smartTriage_server.module.isolation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for assigning an isolation room.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssignRoomRequest {

    @NotBlank(message = "Room number is required")
    private String roomNumber;
}
