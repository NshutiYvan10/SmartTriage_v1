package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.Gender;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Minimal, cross-hospital SAFETY SUMMARY for a person (Phase 1). Demographics + the life-critical
 * floor (allergies, blood type, active meds, chronic problems, emergency contacts), aggregated
 * across every SmartTriage hospital where the person is registered (linked via shared identity).
 * Deliberately does NOT include the deep clinical record (notes, diagnoses, labs) — that stays
 * hospital-owned and is a later phase. Each item carries its source hospital (provenance).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrossHospitalSafetySummaryResponse {

    /** False when no shared identity / no linked patient exists for the national ID. */
    private boolean found;
    private String nationalId;

    // Demographics (from the most-recently-updated linked record)
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
    private Gender gender;
    private String bloodType;
    private String emergencyContactName;
    private String emergencyContactPhone;

    private int linkedHospitalCount;
    private List<String> sourceHospitals;

    private List<SafetyItem> allergies;
    private List<SafetyItem> chronicConditions;
    private List<SafetyItem> activeMedications;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SafetyItem {
        private String detail;
        private String sourceHospital;
    }
}
