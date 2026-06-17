package com.smartTriage.smartTriage_server.module.lab.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.CriticalValueType;
import jakarta.persistence.*;
import lombok.*;

/**
 * LabResultComponent — one analyte's actual value within a multi-analyte (panel)
 * lab result. Each component is independently flagged abnormal/critical against its
 * own reference range, so a single critical analyte (e.g. K+ 6.8 inside a U&E, or
 * pO2 6.0 kPa inside a blood gas) is detected even when the rest of the panel is normal.
 *
 * The parent LabOrder's isCritical/isAbnormal roll up from its components.
 */
@Entity
@Table(name = "lab_result_component", indexes = {
        @Index(name = "idx_result_component_order", columnList = "lab_order_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResultComponent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lab_order_id", nullable = false)
    private LabOrder labOrder;

    @Column(name = "analyte_name", nullable = false, length = 120)
    private String analyteName;

    @Column(name = "analyte_code", length = 40)
    private String analyteCode;

    /** Raw entered value as text (carries non-numeric results e.g. "Positive"). */
    @Column(name = "result_value", length = 255)
    private String resultValue;

    /** Parsed numeric value for comparison against the reference/critical thresholds. */
    @Column(name = "result_numeric")
    private Double resultNumeric;

    @Column(name = "result_unit", length = 30)
    private String resultUnit;

    @Column(name = "reference_low")
    private Double referenceLow;

    @Column(name = "reference_high")
    private Double referenceHigh;

    @Column(name = "is_abnormal", nullable = false)
    @Builder.Default
    private boolean isAbnormal = false;

    @Column(name = "is_critical", nullable = false)
    @Builder.Default
    private boolean isCritical = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "critical_value_type", length = 40)
    private CriticalValueType criticalValueType;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;
}
