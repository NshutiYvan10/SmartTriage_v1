package com.smartTriage.smartTriage_server.module.reporting.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.MohReportType;
import com.smartTriage.smartTriage_server.common.enums.ReportStatus;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * MoH Report — de-identified aggregate health statistics for Rwanda Ministry of Health.
 * Contains only aggregate counts and averages; no patient-identifiable information.
 */
@Entity
@Table(name = "moh_reports", indexes = {
        @Index(name = "idx_moh_report_hospital", columnList = "hospital_id"),
        @Index(name = "idx_moh_report_type", columnList = "report_type"),
        @Index(name = "idx_moh_report_period_start", columnList = "report_period_start")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MohReport extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 30)
    private MohReportType reportType;

    @Column(name = "report_period_start", nullable = false)
    private Instant reportPeriodStart;

    @Column(name = "report_period_end", nullable = false)
    private Instant reportPeriodEnd;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "generated_by_name")
    private String generatedByName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ReportStatus status = ReportStatus.DRAFT;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "submitted_by_name")
    private String submittedByName;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    // ---- Report data fields (aggregate statistics only, no PII) ----

    @Column(name = "total_ed_visits")
    private Integer totalEdVisits;

    @Column(name = "total_triaged")
    private Integer totalTriaged;

    @Column(name = "triage_category_breakdown", columnDefinition = "TEXT")
    private String triageCategoryBreakdown;

    @Column(name = "average_wait_time_minutes")
    private Double averageWaitTimeMinutes;

    @Column(name = "mortality_count")
    private Integer mortalityCount;

    @Column(name = "left_without_being_seen_count")
    private Integer leftWithoutBeingSeenCount;

    @Column(name = "admission_count")
    private Integer admissionCount;

    @Column(name = "icu_admission_count")
    private Integer icuAdmissionCount;

    @Column(name = "transfer_count")
    private Integer transferCount;

    @Column(name = "top_diagnoses", columnDefinition = "TEXT")
    private String topDiagnoses;

    @Column(name = "top_chief_complaints", columnDefinition = "TEXT")
    private String topChiefComplaints;

    @Column(name = "pediatric_visit_count")
    private Integer pediatricVisitCount;

    @Column(name = "malaria_positive_count")
    private Integer malariaPositiveCount;

    @Column(name = "sepsis_screened_count")
    private Integer sepsisScreenedCount;

    @Column(name = "isolation_activated_count")
    private Integer isolationActivatedCount;

    @Column(name = "average_length_of_stay_minutes")
    private Double averageLengthOfStayMinutes;

    @Column(name = "report_data_json", columnDefinition = "TEXT")
    private String reportDataJson;
}
