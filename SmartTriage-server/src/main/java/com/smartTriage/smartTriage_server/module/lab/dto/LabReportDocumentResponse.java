package com.smartTriage.smartTriage_server.module.lab.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for an attached lab report document — deliberately WITHOUT the file
 * bytes (those stream only from the dedicated download endpoint), so listing
 * documents never pulls large blobs into memory.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabReportDocumentResponse {
    private UUID id;
    private UUID labOrderId;
    private String fileName;
    private String contentType;
    private long sizeBytes;
    private String uploadedByName;
    private String description;
    private Instant uploadedAt;
}
