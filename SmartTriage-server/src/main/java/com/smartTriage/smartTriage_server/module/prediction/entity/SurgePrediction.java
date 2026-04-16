package com.smartTriage.smartTriage_server.module.prediction.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.SurgeRiskLevel;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * SurgePrediction — AI-assisted forecast of ED surge risk and ICU bed demand.
 *
 * Uses statistical heuristics based on historical patterns, current arrival rates,
 * seasonal factors (malaria/rainy season in Rwanda), and current occupancy to
 * predict surge risk and resource needs within a specified time horizon.
 */
@Entity
@Table(name = "surge_predictions", indexes = {
        @Index(name = "idx_surge_hospital", columnList = "hospital_id"),
        @Index(name = "idx_surge_predicted_at", columnList = "predicted_at"),
        @Index(name = "idx_surge_risk_level", columnList = "surge_risk_level"),
        @Index(name = "idx_surge_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SurgePrediction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Column(name = "predicted_at", nullable = false)
    private Instant predictedAt;

    @Column(name = "prediction_horizon_hours", nullable = false)
    private Integer predictionHorizonHours;

    // ====================================================================
    // PREDICTIONS
    // ====================================================================

    @Column(name = "predicted_ed_admissions")
    private Integer predictedEdAdmissions;

    @Column(name = "predicted_icu_demand")
    private Integer predictedIcuDemand;

    @Column(name = "predicted_red_patients")
    private Integer predictedRedPatients;

    @Column(name = "current_ed_occupancy")
    private Integer currentEdOccupancy;

    @Column(name = "current_icu_occupancy")
    private Integer currentIcuOccupancy;

    @Column(name = "ed_capacity")
    private Integer edCapacity;

    @Column(name = "icu_capacity")
    private Integer icuCapacity;

    @Column(name = "surge_risk_score")
    private Double surgeRiskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "surge_risk_level", length = 15)
    private SurgeRiskLevel surgeRiskLevel;

    // ====================================================================
    // BASIS FOR PREDICTION
    // ====================================================================

    @Column(name = "historical_avg_for_period")
    private Double historicalAvgForPeriod;

    @Column(name = "current_arrival_rate")
    private Double currentArrivalRate;

    @Column(name = "trend_direction", length = 20)
    private String trendDirection;

    @Column(name = "seasonal_factor")
    private Double seasonalFactor;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ====================================================================
    // POST-HOC VALIDATION
    // ====================================================================

    @Column(name = "was_accurate")
    private Boolean wasAccurate;

    @Column(name = "actual_value")
    private Integer actualValue;
}
