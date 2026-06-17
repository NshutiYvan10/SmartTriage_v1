package com.smartTriage.smartTriage_server.module.lab.dto;

import com.smartTriage.smartTriage_server.module.lab.entity.LabResultComponent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One analyte's actual result, with its independent abnormal/critical flags — for
 * chart / handover / lab-board display of panel results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabResultComponentResponse {

    private String analyteName;
    private String analyteCode;
    private String resultValue;
    private Double resultNumeric;
    private String resultUnit;
    private Double referenceLow;
    private Double referenceHigh;
    private boolean isAbnormal;
    private boolean isCritical;
    private String criticalValueType;
    private int displayOrder;

    public static LabResultComponentResponse from(LabResultComponent c) {
        return LabResultComponentResponse.builder()
                .analyteName(c.getAnalyteName())
                .analyteCode(c.getAnalyteCode())
                .resultValue(c.getResultValue())
                .resultNumeric(c.getResultNumeric())
                .resultUnit(c.getResultUnit())
                .referenceLow(c.getReferenceLow())
                .referenceHigh(c.getReferenceHigh())
                .isAbnormal(c.isAbnormal())
                .isCritical(c.isCritical())
                .criticalValueType(c.getCriticalValueType() != null ? c.getCriticalValueType().name() : null)
                .displayOrder(c.getDisplayOrder())
                .build();
    }
}
