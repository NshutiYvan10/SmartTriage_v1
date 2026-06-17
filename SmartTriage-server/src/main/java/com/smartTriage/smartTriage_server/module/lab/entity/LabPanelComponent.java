package com.smartTriage.smartTriage_server.module.lab.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * LabPanelComponent — reference definition of one analyte within a panel test
 * (e.g. Potassium within "Urea and Electrolytes"). Drives the multi-row result-entry
 * form and supplies the per-analyte unit + reference range + critical thresholds used
 * to flag each component. Seeded (V83) for the common ED panels; SUPER_ADMIN-extensible.
 */
@Entity
@Table(name = "lab_panel_component", indexes = {
        @Index(name = "idx_panel_component_panel", columnList = "panel_test_name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabPanelComponent extends BaseEntity {

    /** The panel's catalog test name this analyte belongs to (matches LabTestCatalog.testName). */
    @Column(name = "panel_test_name", nullable = false, length = 200)
    private String panelTestName;

    @Column(name = "analyte_name", nullable = false, length = 120)
    private String analyteName;

    @Column(name = "analyte_code", length = 40)
    private String analyteCode;

    @Column(name = "result_unit", length = 30)
    private String resultUnit;

    @Column(name = "reference_low")
    private Double referenceLow;

    @Column(name = "reference_high")
    private Double referenceHigh;

    @Column(name = "critical_low")
    private Double criticalLow;

    @Column(name = "critical_high")
    private Double criticalHigh;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;
}
