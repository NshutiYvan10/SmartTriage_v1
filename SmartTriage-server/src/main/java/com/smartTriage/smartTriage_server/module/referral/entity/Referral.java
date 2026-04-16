package com.smartTriage.smartTriage_server.module.referral.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.ReferralStatus;
import com.smartTriage.smartTriage_server.common.enums.ReferralType;
import com.smartTriage.smartTriage_server.common.enums.TransportMode;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Referral entity — represents an inter-hospital referral or transfer.
 *
 * Aligned with Rwanda's structured national referral system:
 * Health Centers -> District Hospitals -> Provincial Referral Hospitals -> National Referral Hospitals (CHUK, CHUB, RMH).
 *
 * Tracks the full lifecycle from initiation through stabilization, transport, and completion.
 */
@Entity
@Table(name = "referrals", indexes = {
        @Index(name = "idx_referral_visit", columnList = "visit_id"),
        @Index(name = "idx_referral_referring_hospital", columnList = "referring_hospital_id"),
        @Index(name = "idx_referral_status", columnList = "status"),
        @Index(name = "idx_referral_type", columnList = "referral_type"),
        @Index(name = "idx_referral_active", columnList = "is_active"),
        @Index(name = "idx_referral_initiated_at", columnList = "initiated_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Referral extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Enumerated(EnumType.STRING)
    @Column(name = "referral_type", nullable = false, length = 25)
    private ReferralType referralType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 35)
    @Builder.Default
    private ReferralStatus status = ReferralStatus.INITIATED;

    // ====================================================================
    // REFERRING FACILITY
    // ====================================================================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referring_hospital_id", nullable = false)
    private Hospital referringHospital;

    @Column(name = "referring_clinician", nullable = false)
    private String referringClinician;

    @Column(name = "referring_clinician_phone", length = 20)
    private String referringClinicianPhone;

    // ====================================================================
    // RECEIVING FACILITY
    // ====================================================================

    @Column(name = "receiving_hospital_name", nullable = false)
    private String receivingHospitalName;

    @Column(name = "receiving_hospital_code", length = 20)
    private String receivingHospitalCode;

    @Column(name = "receiving_clinician")
    private String receivingClinician;

    @Column(name = "receiving_clinician_phone", length = 20)
    private String receivingClinicianPhone;

    // ====================================================================
    // CLINICAL DETAILS
    // ====================================================================

    @Column(name = "referral_reason", nullable = false, columnDefinition = "TEXT")
    private String referralReason;

    @Column(name = "clinical_summary", nullable = false, columnDefinition = "TEXT")
    private String clinicalSummary;

    @Column(name = "current_diagnosis")
    private String currentDiagnosis;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_triage_category", length = 10)
    private TriageCategory currentTriageCategory;

    @Column(name = "current_tews_score")
    private Integer currentTewsScore;

    @Column(name = "interventions_given", columnDefinition = "TEXT")
    private String interventionsGiven;

    @Column(name = "ongoing_treatment", columnDefinition = "TEXT")
    private String ongoingTreatment;

    // ====================================================================
    // STABILIZATION CHECKLIST
    // ====================================================================

    @Column(name = "airway_secured")
    private Boolean airwaySecured;

    @Column(name = "breathing_stable")
    private Boolean breathingStable;

    @Column(name = "circulation_stable")
    private Boolean circulationStable;

    @Column(name = "iv_access_established")
    private Boolean ivAccessEstablished;

    @Column(name = "medications_documented")
    private Boolean medicationsDocumented;

    @Column(name = "allergies_documented")
    private Boolean allergiesDocumented;

    @Column(name = "blood_type_documented")
    private Boolean bloodTypeDocumented;

    @Column(name = "consent_obtained")
    private Boolean consentObtained;

    @Column(name = "referral_form_completed")
    private Boolean referralFormCompleted;

    @Column(name = "patient_id_band_applied")
    private Boolean patientIdBandApplied;

    // ====================================================================
    // TRANSFER LOGISTICS
    // ====================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", length = 20)
    private TransportMode transportMode;

    @Column(name = "escort_required")
    private Boolean escortRequired;

    @Column(name = "escort_name")
    private String escortName;

    @Column(name = "escort_designation", length = 30)
    private String escortDesignation;

    @Column(name = "estimated_transfer_time_minutes")
    private Integer estimatedTransferTimeMinutes;

    @Column(name = "departed_at")
    private Instant departedAt;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "actual_transfer_time_minutes")
    private Integer actualTransferTimeMinutes;

    // ====================================================================
    // TIMESTAMPS
    // ====================================================================

    @Column(name = "initiated_at")
    private Instant initiatedAt;

    @Column(name = "receiving_contacted_at")
    private Instant receivingContactedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "stabilized_at")
    private Instant stabilizedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // ====================================================================
    // RWANDA RHMIS INTEGRATION
    // ====================================================================

    @Column(name = "rhmis_case_number", length = 50)
    private String rhmisCaseNumber;

    @Column(name = "samu_request_number", length = 50)
    private String samuRequestNumber;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
