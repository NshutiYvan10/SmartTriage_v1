package com.smartTriage.smartTriage_server.module.bed.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Aggregated zone snapshot for the bed-grid UI.
 *
 * Includes every bed in the zone plus simple capacity metrics so the top
 * of the zone view can show "6 of 8 occupied — 1 cleaning — 1 out of
 * service" without a separate endpoint call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZoneOccupancyResponse {

    private EdZone zone;
    private String zoneLabel;
    private int totalBeds;
    private int occupied;
    private int available;
    private int cleaning;
    private int outOfService;
    private List<BedResponse> beds;
}
