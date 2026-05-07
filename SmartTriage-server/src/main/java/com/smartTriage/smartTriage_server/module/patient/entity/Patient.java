package com.smartTriage.smartTriage_server.module.patient.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.Gender;
import com.smartTriage.smartTriage_server.common.enums.PregnancyStatus;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Patient entity — the core clinical subject.
 * Patients are scoped to a hospital. Duplicate detection is based on
 * national ID and hospital context.
 *
 * In a life-critical system, patient identity must be unambiguous.
 */
@Entity
@Table(name = "patients", indexes = {
        @Index(name = "idx_patient_hospital", columnList = "hospital_id"),
        @Index(name = "idx_patient_national_id", columnList = "national_id"),
        @Index(name = "idx_patient_mrn", columnList = "medical_record_number"),
        @Index(name = "idx_patient_active", columnList = "is_active"),
        @Index(name = "idx_patient_dob", columnList = "date_of_birth"),
        @Index(name = "idx_patient_name", columnList = "last_name, first_name")
        // NB: partial unique + lookup indexes for the V22 identity expansion
        // (passport_number, birth_certificate_number, phone_number,
        // guardian_national_id, guardian_phone) are declared as PostgreSQL
        // partial indexes with WHERE clauses; JPA cannot express those, so
        // they live in V22__patient_identity_expansion.sql.
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Patient extends BaseEntity {

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 10)
    private Gender gender;

    @Column(name = "national_id", length = 30)
    private String nationalId;

    /**
     * Passport number — primary identifier for foreign nationals who do not
     * have a Rwandan NID. Partial-unique within (hospital_id, is_active=true).
     */
    @Column(name = "passport_number", length = 30)
    private String passportNumber;

    /**
     * Birth certificate number — primary deterministic identifier for
     * pediatric patients before they receive a national ID. Partial-unique
     * within (hospital_id, is_active=true).
     */
    @Column(name = "birth_certificate_number", length = 30)
    private String birthCertificateNumber;

    @Column(name = "medical_record_number", length = 30)
    private String medicalRecordNumber;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "address")
    private String address;

    @Column(name = "emergency_contact_name", length = 200)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", length = 20)
    private String emergencyContactPhone;

    // ── Guardian fields ──
    //
    // Used for pediatric identity. The guardian's NID + the child's first
    // name + DOB form the deterministic-ish key for finding a kid across
    // visits when the kid has no NID/passport/birth-cert of their own.

    @Column(name = "guardian_national_id", length = 30)
    private String guardianNationalId;

    @Column(name = "guardian_phone", length = 20)
    private String guardianPhone;

    @Column(name = "guardian_name", length = 200)
    private String guardianName;

    @Column(name = "guardian_relationship", length = 50)
    private String guardianRelationship;

    @Column(name = "blood_type", length = 5)
    private String bloodType;

    @Column(name = "known_allergies", columnDefinition = "TEXT")
    private String knownAllergies;

    @Column(name = "chronic_conditions", columnDefinition = "TEXT")
    private String chronicConditions;

    /**
     * Phase 13b — structured pregnancy / lactation status. Drives the
     * teratogen safety check at prescribe time. NULL means "never
     * recorded" and the safety check falls back to a free-text scan
     * of {@link #chronicConditions} (legacy behaviour).
     *
     * @see com.smartTriage.smartTriage_server.common.enums.PregnancyStatus
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "pregnancy_status", length = 32)
    private PregnancyStatus pregnancyStatus;

    /**
     * Timestamp the pregnancy_status was last set. Lets the safety
     * officer detect stale flags ("PREGNANT" recorded 18 months ago).
     */
    @Column(name = "pregnancy_status_recorded_at")
    private Instant pregnancyStatusRecordedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    // ═══════════════════════════════════════════════════════════════
    // Unidentified-patient identity placeholder (V44 — Direct Resus)
    //
    // When a Red patient arrives without identity (cardiac arrest at
    // the door, unconscious trauma, etc.), the Direct Resus pathway
    // creates a Patient with first_name="Unknown" and a NATO phonetic
    // last_name ("Alpha", "Bravo", ...). Care begins immediately;
    // identity is back-filled once the resus team has bandwidth or
    // family arrives. The partial index idx_patient_unidentified is
    // defined in the V44 migration directly because JPA @Index does
    // not support WHERE.
    // ═══════════════════════════════════════════════════════════════

    /**
     * TRUE while this patient row is a phonetic placeholder. Flips to
     * FALSE the moment a clinician resolves identity (typing the real
     * name, or merging into an existing MPI record). Drives the
     * "?" badge in the UI and the identity-overdue alerts.
     */
    @Column(name = "is_unidentified", nullable = false)
    @Builder.Default
    private boolean isUnidentified = false;

    /**
     * The phonetic label assigned at admission ("Alpha", "Bravo-2", ...).
     * Preserved as an audit anchor even after identity is resolved —
     * a chart review of "Marie Uwimana" can still see she was admitted
     * as Unknown Alpha at 14:32.
     */
    @Column(name = "placeholder_label", length = 50)
    private String placeholderLabel;

    /** When the placeholder was assigned. Drives the identity-overdue clock. */
    @Column(name = "placeholder_assigned_at")
    private Instant placeholderAssignedAt;

    /** When identity was resolved. NULL while still unidentified. */
    @Column(name = "identified_at")
    private Instant identifiedAt;

    /** The clinician who resolved the identity. NULL while still unidentified. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "identified_by_user_id")
    private User identifiedBy;

    /**
     * Returns true if patient is pediatric (< 13 years old).
     * Pediatric patients require age-adjusted vital thresholds and scoring.
     */
    @Transient
    public boolean isPediatric() {
        if (dateOfBirth == null) return false;
        return dateOfBirth.plusYears(13).isAfter(LocalDate.now());
    }

    /**
     * Returns the patient's age in years.
     */
    @Transient
    public int getAgeInYears() {
        if (dateOfBirth == null) return -1;
        return java.time.Period.between(dateOfBirth, LocalDate.now()).getYears();
    }
}
