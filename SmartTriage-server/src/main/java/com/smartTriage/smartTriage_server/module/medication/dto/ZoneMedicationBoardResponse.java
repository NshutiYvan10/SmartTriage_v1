package com.smartTriage.smartTriage_server.module.medication.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Zone medication board (V67) — everything a nurse needs to run their
 * zone's medication workload in one payload:
 * <ul>
 *   <li>{@code dueDoses} — open DUE doses (the frontend splits
 *       overdue / due-now / upcoming by {@code dueAt});</li>
 *   <li>{@code recentlyGiven} — administrations in the trailing
 *       window (default 8 h), newest first;</li>
 *   <li>{@code prnOrders} — live PRN orders for the zone's patients,
 *       enriched with given-count + next-allowed time, so a nurse can
 *       quick-give when the condition arises;</li>
 *   <li>{@code activeInfusions} — live CONTINUOUS orders with their
 *       latest infusion event;</li>
 *   <li>{@code pendingApproval} — high-alert orders awaiting the
 *       charge nurse (visible to all, actionable by charge).</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZoneMedicationBoardResponse {

    private List<MedicationDoseResponse> dueDoses;
    private List<MedicationDoseResponse> recentlyGiven;
    private List<MedicationOrderAuditResponse> prnOrders;
    private List<MedicationOrderAuditResponse> activeInfusions;
    private List<MedicationResponse> pendingApproval;
}
