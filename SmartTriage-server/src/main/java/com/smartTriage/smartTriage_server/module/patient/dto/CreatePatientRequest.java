package com.smartTriage.smartTriage_server.module.patient.dto;

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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePatientRequest {

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

    @Size(max = 30)
    private String passportNumber;

    @Size(max = 30)
    private String birthCertificateNumber;

    @Size(max = 20)
    private String phoneNumber;

    private String address;
    private String emergencyContactName;
    private String emergencyContactPhone;
    private String bloodType;
    private String knownAllergies;
    private String chronicConditions;

    /** S8 — optional body weight in kg. See Patient.weightKg (additive only). */
    @jakarta.validation.constraints.DecimalMin(value = "0.0", inclusive = false, message = "Weight must be positive")
    @jakarta.validation.constraints.DecimalMax(value = "999.99", message = "Weight is implausibly high")
    private java.math.BigDecimal weightKg;

    // ── Guardian fields (pediatric) ──
    @Size(max = 30) private String guardianNationalId;
    @Size(max = 20) private String guardianPhone;
    @Size(max = 200) private String guardianName;
    @Size(max = 50)  private String guardianRelationship;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    // ── Structured location (Rwanda admin hierarchy) ──
    // V46+ — optional. Pass any subset; the service stores the
    // deepest level supplied. Existing free-text {address} above
    // stays for street/building/landmark detail.
    private UUID provinceId;
    private UUID districtId;
    private UUID sectorId;
    private UUID cellId;
    private UUID villageId;
}
