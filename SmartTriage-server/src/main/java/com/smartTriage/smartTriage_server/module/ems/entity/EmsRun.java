package com.smartTriage.smartTriage_server.module.ems.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.EmsRunStatus;
import com.smartTriage.smartTriage_server.common.enums.EmsService;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Pre-hospital run record. One row per ambulance dispatch.
 *
 * Captures the paramedic's MIST/ATMIST handover: mechanism, injuries,
 * field triage, signs (vitals on scene), treatments given, and the
 * receiving nurse's transfer-of-care acknowledgement.
 *
 * The Visit linkage is nullable on purpose — pre-arrival pings can
 * exist briefly without a Visit, and an EmsRun for an unidentified
 * arrival creates a placeholder Visit at preregister time.
 */
@Entity
@Table(name = "ems_runs", indexes = {
        @Index(name = "idx_ems_run_hospital_status", columnList = "hospital_id,status"),
        @Index(name = "idx_ems_run_visit", columnList = "visit_id"),
        @Index(name = "idx_ems_run_paramedic", columnList = "paramedic_user_id"),
        @Index(name = "idx_ems_run_dispatched_at", columnList = "dispatched_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmsRun extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paramedic_user_id")
    private User paramedic;

    @Column(name = "paramedic_name", length = 255)
    private String paramedicName;

    @Enumerated(EnumType.STRING)
    @Column(name = "service", nullable = false, length = 40)
    @Builder.Default
    private EmsService service = EmsService.OTHER;

    @Column(name = "unit_callsign", length = 40)
    private String unitCallsign;

    // ── Run timeline

    @Column(name = "dispatched_at", nullable = false)
    private Instant dispatchedAt;

    @Column(name = "scene_arrived_at")
    private Instant sceneArrivedAt;

    @Column(name = "scene_left_at")
    private Instant sceneLeftAt;

    @Column(name = "ed_arrived_at")
    private Instant edArrivedAt;

    @Column(name = "handed_off_at")
    private Instant handedOffAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    // ── Patient context

    @Column(name = "patient_age_years")
    private Integer patientAgeYears;

    @Column(name = "patient_sex", length = 10)
    private String patientSex;          // MALE / FEMALE / UNKNOWN

    @Column(name = "incident_location", length = 255)
    private String incidentLocation;

    @Column(name = "mechanism", length = 500)
    private String mechanism;

    @Column(name = "history_summary", columnDefinition = "TEXT")
    private String historySummary;

    @Column(name = "injuries_observed", columnDefinition = "TEXT")
    private String injuriesObserved;

    // ── Field triage call

    @Column(name = "field_triage_category", length = 20)
    private String fieldTriageCategory;     // RED / ORANGE / YELLOW / GREEN / BLUE

    @Column(name = "field_triage_reason", length = 500)
    private String fieldTriageReason;

    // ── Field vitals snapshot

    @Column(name = "field_gcs")
    private Integer fieldGcs;

    @Column(name = "field_resp_rate")
    private Integer fieldRespRate;

    @Column(name = "field_hr")
    private Integer fieldHr;

    @Column(name = "field_sbp")
    private Integer fieldSbp;

    @Column(name = "field_dbp")
    private Integer fieldDbp;

    @Column(name = "field_spo2")
    private Integer fieldSpo2;

    @Column(name = "field_temp", precision = 4, scale = 1)
    private BigDecimal fieldTemp;

    @Column(name = "field_glucose", precision = 5, scale = 2)
    private BigDecimal fieldGlucose;

    // ── Workflow state

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private EmsRunStatus status = EmsRunStatus.DISPATCHED;

    // ── Transfer of care

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handed_off_to_user_id")
    private User handedOffTo;

    @Column(name = "handed_off_to_name", length = 255)
    private String handedOffToName;

    @Column(name = "handover_acknowledgement_text", columnDefinition = "TEXT")
    private String handoverAcknowledgementText;

    @Column(name = "eta_minutes")
    private Integer etaMinutes;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
