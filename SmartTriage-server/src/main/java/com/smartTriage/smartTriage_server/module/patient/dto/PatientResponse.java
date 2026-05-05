package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.Gender;
import com.smartTriage.smartTriage_server.common.enums.PregnancyStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientResponse {

    private UUID id;
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
    private int ageInYears;

    /**
     * Whether the patient is pediatric.
     *
     * NOTE: Jackson's default bean introspection strips the `is` prefix from
     * boolean getters, so a Lombok-generated `isPediatric()` getter would
     * serialize as `"pediatric": true`. The @JsonProperty on the field forces
     * the `isPediatric` key in the JSON output to match the frontend contract.
     */
    @JsonProperty("isPediatric")
    private boolean isPediatric;

    private Gender gender;
    private String nationalId;
    private String passportNumber;
    private String birthCertificateNumber;
    private String medicalRecordNumber;
    private String phoneNumber;
    private String address;
    private String emergencyContactName;
    private String emergencyContactPhone;
    private String bloodType;
    private String knownAllergies;
    private String chronicConditions;

    /**
     * Phase 13b — structured pregnancy status. Drives the teratogen
     * safety check at prescribe time. NULL means "never recorded";
     * the frontend safety check falls back to a free-text scan of
     * chronicConditions in that case.
     */
    private PregnancyStatus pregnancyStatus;
    private Instant pregnancyStatusRecordedAt;

    // ── Guardian (pediatric attribution) ──
    private String guardianNationalId;
    private String guardianPhone;
    private String guardianName;
    private String guardianRelationship;

    private UUID hospitalId;
    private Instant createdAt;
    private Instant updatedAt;
}
