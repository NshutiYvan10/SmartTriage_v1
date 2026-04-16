package com.smartTriage.smartTriage_server.module.retriage.dto;

import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Retriage status for a specific visit — used by the retriage dashboard
 * to show current wait time, reassessment status, and overdue indicators.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetriageStatusResponse {

    private UUID visitId;
    private String visitNumber;
    private String patientName;
    private TriageCategory currentCategory;
    private Integer tewsScore;
    private Instant lastTriageTime;
    private Instant nextReassessmentDue;
    private long waitTimeMinutes;
    private int maxWaitMinutes;
    private boolean isOverdue;
    private boolean isWaitTimeExceeded;
}
