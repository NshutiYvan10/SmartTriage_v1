package com.smartTriage.smartTriage_server.module.quality.dto;

import com.smartTriage.smartTriage_server.common.enums.MetricPeriod;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Manual snapshot-generation request from the Quality dashboard.
 *
 * <p>{@code date} anchors the period: DAILY computes that day; WEEKLY
 * computes the week containing it (Monday-anchored); MONTHLY computes
 * its calendar month. Aggregate periods roll up the underlying DAILY
 * snapshots, mirroring the scheduled computation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateSnapshotRequest {

    @NotNull(message = "hospitalId is required")
    private UUID hospitalId;

    @NotNull(message = "date is required")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;

    /** Defaults to DAILY when omitted. */
    private MetricPeriod period;
}
