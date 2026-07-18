package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.Gender;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Cross-hospital DEEP clinical-history summary (Phase 2). A bounded, provenance-tagged summary —
 * not the raw record — assembled across every hospital where the person is registered, served ONLY
 * when the data gate allows it ({@code accessBasis}). When access is denied, {@code accessGranted}
 * is false and the clinical sections are empty/absent (only the existence + consentRequired flag).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrossHospitalDeepRecordResponse {

    private boolean found;
    private boolean accessGranted;
    /** CONSENT | BREAK_THE_GLASS | DENIED */
    private String accessBasis;
    private boolean consentRequired;
    private String nationalId;

    // Demographics (from the most-recently-updated linked record) — present when found.
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
    private Gender gender;

    private int linkedHospitalCount;
    private List<HospitalSection> hospitals;
    private List<String> medicationHistory;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class HospitalSection {
        private String sourceHospital;
        private boolean truncated;
        private List<VisitSummary> visits;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VisitSummary {
        private String visitNumber;
        private Instant arrivalTime;
        private String status;
        private List<String> diagnoses;
        /** Discharge summaries with their full content, so a receiving clinician can
         *  open and read them (a discharge summary is a continuity-of-care document). */
        private List<DischargeSummaryDoc> dischargeSummaries;
        /** All labs/investigations for the visit, each tagged [CRITICAL]/[ABNORMAL] where applicable. */
        private List<String> labs;
        private List<String> keyNotes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DischargeSummaryDoc {
        private String title;
        private String content;
        private boolean signed;
    }
}
