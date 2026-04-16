package com.smartTriage.smartTriage_server.module.offline.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for a batch sync operation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncBatchResponse {

    private int totalRecords;
    private int successCount;
    private int conflictCount;
    private int failedCount;
    private List<OfflineSyncRecordResponse> results;
}
