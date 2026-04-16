package com.smartTriage.smartTriage_server.module.isolation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for recording a public health notification to Rwanda RBC.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicHealthNotificationRequest {

    private String referenceNumber;
}
