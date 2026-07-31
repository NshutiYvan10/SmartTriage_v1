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
        /** Presenting picture — so a receiving clinician sees WHY they came, not just when. */
        private String chiefComplaint;
        private String triageCategory;
        private List<String> diagnoses;
        /** Discharge summaries with their full content, so a receiving clinician can
         *  open and read them (a discharge summary is a continuity-of-care document). */
        private List<DischargeSummaryDoc> dischargeSummaries;
        /** All labs/investigations for the visit, each tagged [CRITICAL]/[ABNORMAL] where applicable. */
        private List<String> labs;
        private List<String> keyNotes;
        /** Structured versions of {@code diagnoses}/{@code labs} — ADDITIVE alongside the
         *  legacy flat strings so existing consumers keep working. The UI renders these:
         *  full diagnosis provenance, and lab results with values/status that update live. */
        private List<DeepDiagnosis> diagnosisDetails;
        private List<DeepLab> labDetails;
        /** Safety-engine context for the visit (sepsis screening, infection/isolation
         *  screening) — the "was this patient flagged?" facts a receiving team asks first. */
        private List<SafetyEvent> safetyEvents;
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DeepDiagnosis {
        private String description;
        private String icdCode;
        private boolean primary;
        private String diagnosedByName;
        private Instant diagnosedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DeepLab {
        private String testName;
        private String status;
        /** Findings text (imaging/ECG) or the resulted value (labs); null until resulted. */
        private String result;
        private String resultUnit;
        private boolean critical;
        private boolean abnormal;
        private String priority;
        private Instant orderedAt;
        private Instant resultedAt;
        /** Attached report documents (film/scan/PDF) — metadata only; the bytes are
         *  served by the deep-record document endpoint under the same access gate. */
        private List<DeepLabDocument> documents;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DeepLabDocument {
        private String id;
        private String fileName;
        private long sizeBytes;
        private String uploadedByName;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SafetyEvent {
        /** SEPSIS_SCREENING · INFECTION_SCREENING */
        private String kind;
        private String label;
        private String detail;
        /** CRITICAL · WARNING · INFO — drives the UI colour only. */
        private String severity;
        private Instant at;
    }
}
