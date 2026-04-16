package com.smartTriage.smartTriage_server.module.referral.dto;

import com.smartTriage.smartTriage_server.common.enums.TransportMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for recording patient departure for transfer.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepartureRequest {

    private TransportMode transportMode;
    private Boolean escortRequired;
    private String escortName;
    private String escortDesignation;
    private String samuRequestNumber;
    private String notes;
}
