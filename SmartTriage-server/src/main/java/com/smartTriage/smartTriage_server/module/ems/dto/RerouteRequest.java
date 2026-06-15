package com.smartTriage.smartTriage_server.module.ems.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Redirect an in-flight run to a different destination hospital
 * (hospital change mid-transport).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RerouteRequest {

    @NotNull(message = "New destination hospital is required")
    private UUID hospitalId;

    /** Why the crew is rerouting (e.g. "closest ED on divert"). */
    private String reason;
}
