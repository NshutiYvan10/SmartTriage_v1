package com.smartTriage.smartTriage_server.module.reporting.dto;

import com.smartTriage.smartTriage_server.common.enums.MohReportType;
import com.smartTriage.smartTriage_server.common.enums.ReportLevel;
import com.smartTriage.smartTriage_server.common.enums.ReportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for MoH report data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MohReportResponse {

    private UUID id;
    private UUID hospitalId;
    private String hospitalName;
    private ReportLevel reportLevel;
    private Integer includedHospitalCount;

    private MohReportType reportType;
    private Instant reportPeriodStart;
    private Instant reportPeriodEnd;
    private Instant generatedAt;
    private String generatedByName;
    private ReportStatus status;
    private Instant submittedAt;
    private String submittedByName;
    private String rejectionReason;

    // Aggregate statistics
    private Integer totalEdVisits;
    private Integer totalTriaged;
    private String triageCategoryBreakdown;
    private Double averageWaitTimeMinutes;
    private Integer mortalityCount;
    private Integer leftWithoutBeingSeenCount;
    private Integer admissionCount;
    private Integer icuAdmissionCount;
    private Integer transferCount;
    private String topDiagnoses;
    private String topChiefComplaints;
    private Integer pediatricVisitCount;
    private Integer malariaPositiveCount;
    private Integer sepsisScreenedCount;
    private Integer isolationActivatedCount;
    private Double averageLengthOfStayMinutes;
    private String reportDataJson;

    private Instant createdAt;
}
