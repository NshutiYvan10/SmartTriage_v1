package com.smartTriage.smartTriage_server.module.quality.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.MetricPeriod;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * QualityMetricSnapshot — persisted point-in-time quality metrics per Rwanda MoH standards.
 *
 * Captures volume, triage distribution, time performance, safety indicators,
 * capacity utilization, and mortality metrics for a hospital on a given date/period.
 * Used for dashboards, trend analysis, and regulatory reporting.
 */
@Entity
@Table(name = "quality_metric_snapshots", indexes = {
        @Index(name = "idx_qms_hospital", columnList = "hospital_id"),
        @Index(name = "idx_qms_date", columnList = "snapshot_date"),
        @Index(name = "idx_qms_period", columnList = "snapshot_period"),
        @Index(name = "idx_qms_hospital_date", columnList = "hospital_id, snapshot_date"),
        @Index(name = "idx_qms_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QualityMetricSnapshot extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_period", nullable = false, length = 15)
    private MetricPeriod snapshotPeriod;

    // ====================================================================
    // VOLUME METRICS
    // ====================================================================

    @Column(name = "total_patients")
    private Integer totalPatients;

    @Column(name = "total_admissions")
    private Integer totalAdmissions;

    @Column(name = "total_discharges")
    private Integer totalDischarges;

    @Column(name = "total_transfers")
    private Integer totalTransfers;

    @Column(name = "total_deaths")
    private Integer totalDeaths;

    @Column(name = "total_left_without_being_seen")
    private Integer totalLeftWithoutBeingSeen;

    @Column(name = "pediatric_patients")
    private Integer pediatricPatients;

    // ====================================================================
    // TRIAGE METRICS
    // ====================================================================

    @Column(name = "red_patients")
    private Integer redPatients;

    @Column(name = "orange_patients")
    private Integer orangePatients;

    @Column(name = "yellow_patients")
    private Integer yellowPatients;

    @Column(name = "green_patients")
    private Integer greenPatients;

    @Column(name = "blue_patients")
    private Integer bluePatients;

    @Column(name = "average_tews_score")
    private Double averageTewsScore;

    @Column(name = "retriage_count")
    private Integer retriageCount;

    @Column(name = "system_triggered_retriages")
    private Integer systemTriggeredRetriages;

    // ====================================================================
    // TIME METRICS (minutes)
    // ====================================================================

    @Column(name = "average_wait_time_minutes")
    private Double averageWaitTimeMinutes;

    @Column(name = "average_door_to_triage_minutes")
    private Double averageDoorToTriageMinutes;

    @Column(name = "average_door_to_physician_minutes")
    private Double averageDoorToPhysicianMinutes;

    @Column(name = "average_total_ed_stay_minutes")
    private Double averageTotalEdStayMinutes;

    @Column(name = "percent_seen_within_target")
    private Double percentSeenWithinTarget;

    @Column(name = "median_wait_time_minutes")
    private Double medianWaitTimeMinutes;

    // ====================================================================
    // SAFETY METRICS
    // ====================================================================

    @Column(name = "sepsis_screening_rate")
    private Double sepsisScreeningRate;

    @Column(name = "sepsis_bundle_compliance_rate")
    private Double sepsisBundleComplianceRate;

    @Column(name = "critical_lab_turnaround_minutes")
    private Double criticalLabTurnaroundMinutes;

    @Column(name = "medication_error_count")
    private Integer medicationErrorCount;

    @Column(name = "safety_incident_count")
    private Integer safetyIncidentCount;

    // ====================================================================
    // CAPACITY METRICS
    // ====================================================================

    @Column(name = "peak_ed_occupancy")
    private Integer peakEdOccupancy;

    @Column(name = "average_ed_occupancy")
    private Double averageEdOccupancy;

    @Column(name = "icu_bed_utilization_percent")
    private Double icuBedUtilizationPercent;

    @Column(name = "ed_bed_utilization_percent")
    private Double edBedUtilizationPercent;

    // ====================================================================
    // MORTALITY
    // ====================================================================

    @Column(name = "ed_mortality_rate")
    private Double edMortalityRate;

    @Column(name = "mortality_within_24_hours")
    private Integer mortalityWithin24Hours;
}
