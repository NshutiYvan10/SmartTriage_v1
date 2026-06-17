package com.smartTriage.smartTriage_server.module.lab.dto;

import com.smartTriage.smartTriage_server.module.lab.entity.LabPanelComponent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Panel-component definition — drives the multi-row result-entry form (which analytes
 * the panel contains, each one's unit + reference range, for pre-fill).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabPanelComponentResponse {

    private String analyteName;
    private String analyteCode;
    private String resultUnit;
    private Double referenceLow;
    private Double referenceHigh;
    private int displayOrder;

    public static LabPanelComponentResponse from(LabPanelComponent c) {
        return LabPanelComponentResponse.builder()
                .analyteName(c.getAnalyteName())
                .analyteCode(c.getAnalyteCode())
                .resultUnit(c.getResultUnit())
                .referenceLow(c.getReferenceLow())
                .referenceHigh(c.getReferenceHigh())
                .displayOrder(c.getDisplayOrder())
                .build();
    }
}
