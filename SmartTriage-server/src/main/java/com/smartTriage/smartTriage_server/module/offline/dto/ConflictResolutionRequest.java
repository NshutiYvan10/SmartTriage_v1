package com.smartTriage.smartTriage_server.module.offline.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for resolving a sync conflict.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConflictResolutionRequest {

    @NotNull(message = "Resolution strategy is required")
    private ConflictResolution strategy;

    public enum ConflictResolution {
        ACCEPT_SERVER,
        ACCEPT_CLIENT,
        MERGE
    }
}
