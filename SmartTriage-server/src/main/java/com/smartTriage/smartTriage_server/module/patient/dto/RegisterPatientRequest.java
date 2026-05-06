package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.ArrivalMode;
import com.smartTriage.smartTriage_server.common.enums.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Combined registration request — creates BOTH a Patient AND a Visit
 * in a single atomic transaction. This guarantees that you never end up
 * with a patient row but no corresponding visit row.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterPatientRequest {

    // ── Patient fields ──

    @NotBlank(message = "First name is required")
    @Size(max = 100)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 100)
    private String lastName;

    private LocalDate dateOfBirth;
    private Gender gender;

    @Size(max = 30)
    private String nationalId;

    @Size(max = 20)
    private String phoneNumber;

    private String address;
    private String emergencyContactName;
    private String emergencyContactPhone;

    /** Legal guardian — required for pediatric patients, NULL for adults. */
    private String guardianName;
    private String guardianPhone;
    /** mother / father / grandparent / aunt / uncle / other */
    private String guardianRelationship;
    private String guardianNationalId;

    private String bloodType;
    private String knownAllergies;
    private String chronicConditions;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    // ── Visit fields ──

    private ArrivalMode arrivalMode;
    private String chiefComplaint;
    private String referringFacility;
}
