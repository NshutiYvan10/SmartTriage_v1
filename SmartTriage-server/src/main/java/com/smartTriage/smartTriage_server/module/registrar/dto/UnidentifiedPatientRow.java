package com.smartTriage.smartTriage_server.module.registrar.dto;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * One unidentified ("John Doe") patient still awaiting identity resolution (R11 reconciliation
 * queue). The registrar follow-up surface — the longer {@code placeholderAssignedAt} ago, the more
 * overdue. {@code patientId} lets the UI deep-link to resolve identity via the existing workflow.
 */
@Builder
public record UnidentifiedPatientRow(
        UUID patientId,
        String placeholderLabel,
        Instant placeholderAssignedAt,
        Long hoursWaiting) {
}
