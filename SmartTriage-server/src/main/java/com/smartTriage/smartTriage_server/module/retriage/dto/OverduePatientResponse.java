package com.smartTriage.smartTriage_server.module.retriage.dto;

import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for patients overdue for reassessment or who have exceeded wait times.
 * Used by the retriage dashboard to display the overdue patient list.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OverduePatientResponse {

    private UUID visitId;
    private String visitNumber;
    private String patientName;
    private TriageCategory currentCategory;
    private Integer tewsScore;
    private Instant lastTriageTime;
    private Instant nextReassessmentDue;
    private long waitTimeMinutes;
    private int maxWaitMinutes;
    private long overdueByMinutes;
    private String alertSeverity;
}
