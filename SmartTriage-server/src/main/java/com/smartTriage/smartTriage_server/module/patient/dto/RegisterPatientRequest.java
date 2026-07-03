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

    /**
     * RFID card UID (V95) — a system-wide identity anchor stored on the shared PersonIdentity, not
     * on this hospital-local row. Optional: entered/tap-captured at registration. Lets the patient
     * be found by a card tap at any SmartTriage hospital.
     */
    @Size(max = 64)
    private String rfidCardId;

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

    /** Legacy free-text (kept for back-compat + display). Prefer the structured {@link #allergies} below. */
    private String knownAllergies;
    /** Legacy free-text (kept for back-compat + display). Prefer the structured {@link #conditions} below. */
    private String chronicConditions;

    /**
     * STRUCTURED allergies captured at the desk (V102). When present, each entry becomes a
     * {@code PatientAllergy} row the medication-safety engine reads by FK/severity — no more
     * typo misses on a comma-joined string. Optional; the free-text field above still populates
     * for display and un-migrated flows. {@code @Valid} cascades the per-element size limits so
     * an over-length allergen/reaction is a clean 400 at the boundary — never a constraint
     * violation that surfaces at commit and rolls back the whole registration.
     */
    @jakarta.validation.Valid
    private java.util.List<StructuredAllergy> allergies;

    /** STRUCTURED chronic conditions captured at the desk (V102) → {@code PatientChronicCondition} rows. */
    @jakarta.validation.Valid
    private java.util.List<StructuredCondition> conditions;

    /** One desk-recorded allergy. allergenName is the only hard requirement; severity defaults to UNKNOWN. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructuredAllergy {
        @Size(max = 200) private String allergenName;
        private com.smartTriage.smartTriage_server.common.enums.AllergySeverity severity;
        @Size(max = 500) private String reaction;
    }

    /** One desk-recorded chronic condition. conditionName is the only hard requirement; status defaults to ACTIVE. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructuredCondition {
        @Size(max = 200) private String conditionName;
        @Size(max = 40)  private String conditionCode;
        private com.smartTriage.smartTriage_server.common.enums.ChronicConditionStatus status;
        @Size(max = 500) private String notes;
    }

    /**
     * S8 — optional body weight in kg, captured at registration. Used as a
     * durable weight datum (display / reference). NOT consumed by automatic
     * dose validation — see Patient.weightKg.
     */
    @jakarta.validation.constraints.DecimalMin(value = "0.0", inclusive = false, message = "Weight must be positive")
    @jakarta.validation.constraints.DecimalMax(value = "999.99", message = "Weight is implausibly high")
    private java.math.BigDecimal weightKg;

    // ── Structured location (Rwanda admin hierarchy) ──
    // V46+ — optional. Frontend's RwandaLocationPicker submits the IDs
    // for whichever levels the user picked; the service resolves and
    // sets the FKs. The free-text {address} above stays for street-
    // level detail (building number, nearby landmark).
    private UUID provinceId;
    private UUID districtId;
    private UUID sectorId;
    private UUID cellId;
    private UUID villageId;

    // ── Guardian fields (pediatric) ──
    @Size(max = 30) private String guardianNationalId;
    @Size(max = 20) private String guardianPhone;
    @Size(max = 200) private String guardianName;
    @Size(max = 50)  private String guardianRelationship;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    // ── Visit fields ──

    private ArrivalMode arrivalMode;
    private String chiefComplaint;
    private String referringFacility;
}
