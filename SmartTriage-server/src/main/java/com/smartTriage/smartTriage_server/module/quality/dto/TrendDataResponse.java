package com.smartTriage.smartTriage_server.module.quality.dto;

import com.smartTriage.smartTriage_server.common.enums.MetricPeriod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrendDataResponse {

    private UUID hospitalId;
    private String hospitalName;
    private MetricPeriod period;
    private int dataPointCount;
    private List<QualityMetricSnapshotResponse> dataPoints;
}
