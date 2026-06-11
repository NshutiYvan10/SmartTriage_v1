package com.smartTriage.smartTriage_server.module.medication.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One order plus its full dose timeline (V67) — the structured form of
 * the per-patient medication audit trail. The handover report renders
 * the same data as text; the visit page renders this as a timeline.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicationOrderAuditResponse {

    private MedicationResponse order;
    private List<MedicationDoseResponse> doses;
}
