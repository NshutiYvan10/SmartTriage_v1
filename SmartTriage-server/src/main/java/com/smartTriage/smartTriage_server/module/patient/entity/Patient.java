package com.smartTriage.smartTriage_server.module.patient.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.Gender;
import com.smartTriage.smartTriage_server.common.enums.PregnancyStatus;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
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

    /**
     * Legal guardian for pediatric patients. NULL for adults — see
     * emergencyContactName/Phone for the adult primary contact. Captured
     * at registration; affects consent, disposition, and child-protection
     * escalation paths.
     */
    @Column(name = "guardian_name", length = 200)
    private String guardianName;

    @Column(name = "guardian_phone", length = 20)
    private String guardianPhone;

    @Column(name = "guardian_relationship", length = 50)
    private String guardianRelationship;

    @Column(name = "guardian_national_id", length = 30)
    private String guardianNationalId;

    @Column(name = "blood_type", length = 5)
    private String bloodType;

    @Column(name = "known_allergies", columnDefinition = "TEXT")
    private String knownAllergies;

    @Column(name = "chronic_conditions", columnDefinition = "TEXT")
    private String chronicConditions;

    @Enumerated(EnumType.STRING)
    @Column(name = "pregnancy_status", length = 20)
    private PregnancyStatus pregnancyStatus;

    @Column(name = "pregnancy_status_recorded_at")
    private Instant pregnancyStatusRecordedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

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
