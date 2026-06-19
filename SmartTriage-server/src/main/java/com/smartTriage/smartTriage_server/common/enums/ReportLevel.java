package com.smartTriage.smartTriage_server.common.enums;

/**
 * Aggregation level of a MoH report.
 *
 * <ul>
 *   <li>{@code HOSPITAL} — de-identified aggregate for a single hospital (the default).</li>
 *   <li>{@code NATIONAL} — de-identified aggregate pooled across all active hospitals
 *       (hospital_id is null; included_hospital_count records how many were aggregated).
 *       Generated and reviewed by SUPER_ADMIN only.</li>
 * </ul>
 */
public enum ReportLevel {
    HOSPITAL, NATIONAL
}
